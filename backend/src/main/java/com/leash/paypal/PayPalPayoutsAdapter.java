package com.leash.paypal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * PayPal Payouts adapter.
 *
 * <p>Every request carries {@code PayPal-Request-Id} and a stable {@code sender_batch_id}, so the
 * bounded retry loop cannot create a duplicate payout. Retries happen only for timeouts, HTTP 429,
 * and HTTP 5xx. A 401 is handed to {@link PayPalOAuthClient}, which refreshes and retries once.
 */
public class PayPalPayoutsAdapter implements PayPalPayoutsClient {

  static final int MAX_ATTEMPTS = 3;
  static final Duration INITIAL_BACKOFF = Duration.ofMillis(250);

  private static final Logger log = LoggerFactory.getLogger(PayPalPayoutsAdapter.class);
  private static final String PAYOUTS_PATH = "/v1/payments/payouts";
  private static final String REQUEST_ID_HEADER = "PayPal-Request-Id";
  private static final String DEBUG_ID_HEADER = "PayPal-Debug-Id";
  private static final int MAX_PAYEE_EMAIL_LENGTH = 127;
  private static final int MAX_MEMO_LENGTH = 4000;

  private final RestClient restClient;
  private final PayPalOAuthClient oauthClient;
  private final Sleeper sleeper;

  public PayPalPayoutsAdapter(RestClient restClient, PayPalOAuthClient oauthClient) {
    this(restClient, oauthClient, Sleeper.system());
  }

  PayPalPayoutsAdapter(RestClient restClient, PayPalOAuthClient oauthClient, Sleeper sleeper) {
    this.restClient = restClient;
    this.oauthClient = oauthClient;
    this.sleeper = sleeper;
  }

  @Override
  public PayoutSubmission submit(PayoutRequest request) {
    validate(request);

    Map<String, Object> response =
        withAccessToken(token ->
            executeWithRetries(
                "paymentId=" + request.paymentId(),
                () ->
                    restClient
                        .post()
                        .uri(PAYOUTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(token))
                        .header(REQUEST_ID_HEADER, request.paymentId())
                        .body(buildPayload(request))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {})));

    Map<String, Object> header = asMap(response.get("batch_header"));
    String batchId = asString(header.get("payout_batch_id"));
    String batchStatus = asString(header.get("batch_status"));
    if (batchId == null || batchId.isBlank() || batchStatus == null || batchStatus.isBlank()) {
      throw new PayPalPayoutException(
          "PayPal payout response was missing batch_header identifiers");
    }

    log.info(
        "PayPal payout accepted: paymentId={} payoutBatchId={} batchStatus={}",
        request.paymentId(),
        batchId,
        batchStatus);
    return new PayoutSubmission(batchId, batchStatus);
  }

  @Override
  public PayPalPayoutStatus lookup(String payoutBatchId) {
    if (payoutBatchId == null || payoutBatchId.isBlank()) {
      throw new IllegalArgumentException("payoutBatchId is required");
    }

    Map<String, Object> response =
        withAccessToken(token ->
            executeWithRetries(
                "payoutBatchId=" + payoutBatchId,
                () ->
                    restClient
                        .get()
                        .uri(PAYOUTS_PATH + "/{id}?page_size=100", payoutBatchId)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {})));

    Map<String, Object> header = asMap(response.get("batch_header"));
    String batchStatus = asString(header.get("batch_status"));
    List<Map<String, Object>> items = asListOfMaps(response.get("items"));
    String itemStatus =
        items.isEmpty() ? null : asString(items.getFirst().get("transaction_status"));

    return new PayPalPayoutStatus(payoutBatchId, batchStatus, itemStatus);
  }

  /**
   * Obtains an access token and runs the call, translating a missing or unusable OAuth
   * configuration into the port's transient failure type. Without this wrapper the exception would
   * escape the payment gateway's error handling and surface as a 500/401 after the row was already
   * committed {@code APPROVED}.
   */
  private <T> T withAccessToken(java.util.function.Function<String, T> call) {
    try {
      return oauthClient.withAccessToken(call);
    } catch (PayPalOAuthException exception) {
      throw new PayPalPayoutException("PayPal OAuth is unavailable", null, null, exception);
    }
  }

  private Map<String, Object> executeWithRetries(
      String correlation, Supplier<Map<String, Object>> call) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return call.get();
      } catch (RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String debugId = debugId(exception);
        log.warn(
            "PayPal payout call failed: {} httpStatus={} debugId={} attempt={}",
            correlation,
            status,
            debugId,
            attempt);

        if (status == 401) {
          throw new PayPalUnauthorizedException("PayPal rejected the access token");
        }
        if (isRetryable(status) && attempt < MAX_ATTEMPTS) {
          sleepBeforeRetry(attempt);
          continue;
        }
        throw new PayPalPayoutException(
            "PayPal payout call failed with HTTP " + status + " (debugId=" + debugId + ")",
            status,
            debugId,
            exception);
      } catch (ResourceAccessException exception) {
        log.warn(
            "PayPal payout call timed out or was unreachable: {} attempt={}", correlation, attempt);
        if (attempt < MAX_ATTEMPTS) {
          sleepBeforeRetry(attempt);
          continue;
        }
        throw new PayPalPayoutException(
            "PayPal payout call failed after " + MAX_ATTEMPTS + " attempts", null, null, exception);
      } catch (RestClientException exception) {
        if (isNetworkFailure(exception) && attempt < MAX_ATTEMPTS) {
          log.warn(
              "PayPal payout call timed out or was unreachable: {} attempt={}",
              correlation,
              attempt);
          sleepBeforeRetry(attempt);
          continue;
        }
        log.warn("PayPal payout call failed without an HTTP response: {}", correlation);
        throw new PayPalPayoutException("PayPal payout call failed", null, null, exception);
      }
    }
    throw new IllegalStateException("retry loop exhausted without returning or throwing");
  }

  private static boolean isNetworkFailure(Throwable throwable) {
    Throwable cause = throwable;
    while (cause != null) {
      if (cause instanceof java.io.IOException) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private void sleepBeforeRetry(int attempt) {
    sleeper.sleep(INITIAL_BACKOFF.multipliedBy(1L << (attempt - 1)));
  }

  private static boolean isRetryable(int status) {
    return status == 429 || status >= 500;
  }

  private static String debugId(RestClientResponseException exception) {
    if (exception.getResponseHeaders() == null) {
      return "none";
    }
    String value = exception.getResponseHeaders().getFirst(DEBUG_ID_HEADER);
    return value == null || value.isBlank() ? "none" : value;
  }

  private static Map<String, Object> buildPayload(PayoutRequest request) {
    Map<String, Object> senderBatchHeader = new LinkedHashMap<>();
    senderBatchHeader.put("sender_batch_id", "leash-" + request.paymentId());
    senderBatchHeader.put("recipient_type", "EMAIL");

    Map<String, Object> amount = new LinkedHashMap<>();
    amount.put("currency", request.amount().currency().name());
    amount.put("value", PayPalAmountFormatter.toDecimalString(request.amount().amountMinor()));

    Map<String, Object> item = new LinkedHashMap<>();
    item.put("recipient_type", "EMAIL");
    item.put("amount", amount);
    item.put("receiver", request.payeeEmail().trim());
    item.put("sender_item_id", request.paymentId());
    if (request.memo() != null && !request.memo().isBlank()) {
      item.put("note", request.memo().trim());
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sender_batch_header", senderBatchHeader);
    payload.put("items", List.of(item));
    return payload;
  }

  private static void validate(PayoutRequest request) {
    if (request.paymentId().length() > 63) {
      throw new IllegalArgumentException("paymentId exceeds PayPal's sender_item_id limit");
    }
    if (request.payeeEmail().trim().length() > MAX_PAYEE_EMAIL_LENGTH) {
      throw new IllegalArgumentException("payeeEmail exceeds PayPal's receiver limit");
    }
    if (request.memo() != null && request.memo().length() > MAX_MEMO_LENGTH) {
      throw new IllegalArgumentException("memo exceeds PayPal's note limit");
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asListOfMaps(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(item -> (Map<String, Object>) item)
        .toList();
  }

  private static String asString(Object value) {
    return value instanceof String string ? string : null;
  }
}

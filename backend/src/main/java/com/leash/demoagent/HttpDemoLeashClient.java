package com.leash.demoagent;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The demo agent's public-HTTP client.
 *
 * <p>This is deliberately the only way the demo agent talks to Leash: it presents its own bearer
 * key and calls the same endpoints any external agent would. There are no service or repository
 * calls from this package.
 */
@Component
@Profile("demo")
public class HttpDemoLeashClient implements DemoLeashClient {

  static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

  private static final Logger log = LoggerFactory.getLogger(HttpDemoLeashClient.class);

  private final RestClient restClient;
  private final DemoAgentCredentials credentials;
  private final DemoLeashBaseUrl baseUrl;
  private final ObjectMapper objectMapper;

  public HttpDemoLeashClient(
      DemoAgentCredentials credentials,
      DemoLeashBaseUrl baseUrl,
      DemoProperties properties,
      ObjectMapper objectMapper) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    this.credentials = credentials;
    this.baseUrl = baseUrl;
    this.objectMapper = objectMapper;
  }

  @Override
  public DemoBudget checkBudget() {
    try {
      ResponseEntity<DemoBudget> response =
          restClient
              .get()
              .uri(baseUrl.current() + "/api/v1/budget")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .toEntity(DemoBudget.class);
      return requireBody(response.getBody(), "budget");
    } catch (RestClientResponseException exception) {
      throw translate("GET /api/v1/budget", exception);
    } catch (RestClientException exception) {
      throw unreachable("GET /api/v1/budget", exception);
    }
  }

  @Override
  public DemoPayment payInvoice(DemoInvoice invoice) {
    PaymentBody body =
        new PaymentBody(
            invoice.payeeEmail(), invoice.amountMinor(), invoice.currency(), invoice.memo());
    try {
      ResponseEntity<PaymentResponseBody> response =
          restClient
              .post()
              .uri(baseUrl.current() + "/api/v1/payments")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .header("Idempotency-Key", invoice.idempotencyKey())
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .toEntity(PaymentResponseBody.class);
      PaymentResponseBody responseBody = requireBody(response.getBody(), "payment");
      boolean replayed =
          "true".equalsIgnoreCase(response.getHeaders().getFirst(IDEMPOTENT_REPLAYED_HEADER));
      return new DemoPayment(
          responseBody.paymentId(),
          responseBody.state(),
          responseBody.decision().outcome(),
          responseBody.decision().reasons().stream()
              .map(reason -> new DemoPayment.DemoReason(reason.code(), reason.message()))
              .toList(),
          responseBody.budget(),
          replayed);
    } catch (RestClientResponseException exception) {
      throw translate("POST /api/v1/payments", exception);
    } catch (RestClientException exception) {
      throw unreachable("POST /api/v1/payments", exception);
    }
  }

  private String bearer() {
    return "Bearer " + credentials.require().apiKey();
  }

  private static <T> T requireBody(T body, String resource) {
    if (body == null) {
      throw new DemoClientException(
          "DEMO_EMPTY_RESPONSE", "Leash returned an empty " + resource + " response", null);
    }
    return body;
  }

  private DemoClientException translate(String operation, RestClientResponseException exception) {
    String code = problemCode(exception.getResponseBodyAsString());
    String detail = problemDetail(exception.getResponseBodyAsString());
    Integer status = exception.getStatusCode().value();
    log.warn("Demo agent call failed: operation={} httpStatus={} code={}", operation, status, code);
    return new DemoClientException(
        status,
        code == null ? "DEMO_REQUEST_REJECTED" : code,
        detail == null ? "Leash rejected the request" : detail);
  }

  private static DemoClientException unreachable(String operation, RestClientException exception) {
    log.warn("Demo agent call was unreachable: operation={}", operation);
    return new DemoClientException(
        "DEMO_UPSTREAM_UNAVAILABLE", "Leash API is unreachable", exception);
  }

  private String problemCode(String body) {
    return problemField(body, "code");
  }

  private String problemDetail(String body) {
    return problemField(body, "detail");
  }

  private String problemField(String body, String field) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(body);
      String value = node.path(field).asString();
      return value == null || value.isBlank() ? null : value;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  record PaymentBody(String payeeEmail, long amountMinor, String currency, String memo) {}

  record PaymentResponseBody(
      UUID paymentId, String state, DecisionBody decision, DemoBudget budget) {}

  record DecisionBody(String outcome, List<ReasonBody> reasons) {}

  record ReasonBody(String code, String message) {}
}

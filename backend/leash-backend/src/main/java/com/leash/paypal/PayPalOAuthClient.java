package com.leash.paypal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Obtains and caches PayPal OAuth access tokens using the client-credentials flow.
 *
 * <p>The cache is thread-safe and refreshes 60 seconds before PayPal's advertised expiry. Token
 * values are never logged. Callers that receive HTTP 401 from a PayPal resource call should use
 * {@link #withAccessToken(Function)}, which refreshes and retries exactly once.
 */
public class PayPalOAuthClient {

  private static final Logger log = LoggerFactory.getLogger(PayPalOAuthClient.class);
  private static final String TOKEN_PATH = "/v1/oauth2/token";
  private static final long REFRESH_SKEW_SECONDS = 60L;

  private final RestClient restClient;
  private final PayPalProperties properties;
  private final Clock clock;
  private final Object refreshLock = new Object();

  private volatile CachedToken cachedToken;

  public PayPalOAuthClient(RestClient restClient, PayPalProperties properties, Clock clock) {
    this.restClient = restClient;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Returns a valid access token, fetching a new one only when the cached token is absent or inside
   * its refresh window.
   */
  public String accessToken() {
    CachedToken current = cachedToken;
    if (isFresh(current)) {
      return current.value();
    }
    synchronized (refreshLock) {
      current = cachedToken;
      if (isFresh(current)) {
        return current.value();
      }
      CachedToken fetched = fetchToken();
      cachedToken = fetched;
      return fetched.value();
    }
  }

  /**
   * Drops the cached token if it is still the one that failed. A concurrent request may already
   * have replaced it; in that case the newer token is kept.
   */
  public void invalidate(String token) {
    synchronized (refreshLock) {
      CachedToken current = cachedToken;
      if (current != null && current.value().equals(token)) {
        cachedToken = null;
      }
    }
  }

  /**
   * Runs a resource call with a bearer token and retries once with a fresh token after a 401.
   *
   * <p>The call must translate an HTTP 401 response into {@link PayPalUnauthorizedException}. A
   * second 401 is propagated to the caller; there is no retry loop.
   */
  public <T> T withAccessToken(Function<String, T> call) {
    String token = accessToken();
    try {
      return call.apply(token);
    } catch (PayPalUnauthorizedException unauthorized) {
      log.warn("PayPal resource call rejected with 401; refreshing token and retrying once");
      invalidate(token);
      return call.apply(accessToken());
    }
  }

  private boolean isFresh(CachedToken token) {
    return token != null && clock.instant().isBefore(token.expiresAt());
  }

  private CachedToken fetchToken() {
    requireCredentials();

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");

    Map<String, Object> response;
    try {
      response =
          restClient
              .post()
              .uri(TOKEN_PATH)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .headers(headers ->
                  headers.setBasicAuth(
                      properties.clientId(), properties.clientSecret(), StandardCharsets.UTF_8))
              .body(form)
              .retrieve()
              .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    } catch (RestClientException exception) {
      throw new PayPalOAuthException("PayPal OAuth token request failed", exception);
    }

    String accessToken = stringValue(response, "access_token");
    if (accessToken == null || accessToken.isBlank()) {
      throw new PayPalOAuthException("PayPal OAuth response did not contain an access_token");
    }
    long expiresInSeconds = longValue(response, "expires_in");
    if (expiresInSeconds <= 0) {
      throw new PayPalOAuthException("PayPal OAuth response contained an invalid expires_in");
    }

    long ttlSeconds = Math.max(expiresInSeconds - REFRESH_SKEW_SECONDS, 0L);
    Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
    log.debug("Obtained PayPal OAuth token; refreshing in {} seconds", ttlSeconds);
    return new CachedToken(accessToken, expiresAt);
  }

  private void requireCredentials() {
    if (isBlank(properties.clientId()) || isBlank(properties.clientSecret())) {
      throw new PayPalOAuthException(
          "PayPal client credentials are not configured. Set PAYPAL_CLIENT_ID and "
              + "PAYPAL_CLIENT_SECRET.");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String stringValue(Map<String, Object> body, String key) {
    if (body == null) {
      return null;
    }
    Object value = body.get(key);
    return value instanceof String string ? string : null;
  }

  private static long longValue(Map<String, Object> body, String key) {
    if (body == null) {
      return 0L;
    }
    Object value = body.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string) {
      try {
        return Long.parseLong(string);
      } catch (NumberFormatException ignored) {
        return 0L;
      }
    }
    return 0L;
  }

  private record CachedToken(String value, Instant expiresAt) {}
}

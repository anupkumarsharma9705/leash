package com.leash.webhook.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.paypal.PayPalWebhookHeaders;
import com.leash.paypal.PayPalWebhookVerifier;
import com.leash.webhook.app.WebhookEventParser;
import com.leash.webhook.app.WebhookEventStore;
import com.leash.webhook.app.WebhookReceiver;
import com.leash.webhook.domain.WebhookEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class PayPalWebhookControllerTest {

  private static final byte[] RAW_EVENT =
      ("{\n"
              + "  \"id\": \"WH-1\",\n"
              + "  \"event_type\": \"PAYMENT.PAYOUTS-ITEM.SUCCEEDED\",\n"
              + "  \"resource\": {\"sender_item_id\": \"pay_123\"}\n"
              + "}")
          .getBytes(StandardCharsets.UTF_8);

  private CapturingVerifier verifier;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    verifier = new CapturingVerifier();
    WebhookReceiver receiver =
        new WebhookReceiver(
            verifier,
            new WebhookEventParser(new ObjectMapper()),
            new InMemoryStore(),
            Optional.of(event -> {}),
            Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new PayPalWebhookController(receiver))
            .setControllerAdvice(new WebhookExceptionHandler())
            .build();
  }

  @Test
  void readsRawBodyAndTransmissionHeadersBeforeParsing() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/paypal")
                .contentType(MediaType.APPLICATION_JSON)
                .header("PAYPAL-TRANSMISSION-ID", "tx-1")
                .header("PAYPAL-TRANSMISSION-TIME", "2026-11-10T00:00:00Z")
                .header("PAYPAL-TRANSMISSION-SIG", "signature")
                .header("PAYPAL-CERT-URL", "https://api.paypal.com/cert.pem")
                .header("PAYPAL-AUTH-ALGO", "SHA256withRSA")
                .content(RAW_EVENT))
        .andExpect(status().isOk());

    assertThat(verifier.rawBody).isEqualTo(RAW_EVENT);
    assertThat(verifier.headers.transmissionId()).isEqualTo("tx-1");
    assertThat(verifier.headers.authAlgorithm()).isEqualTo("SHA256withRSA");
  }

  @Test
  void rejectsUnverifiedRequestWithProblemDetail() throws Exception {
    verifier.result = false;

    mockMvc
        .perform(
            post("/webhooks/paypal")
                .contentType(MediaType.APPLICATION_JSON)
                .header("PAYPAL-TRANSMISSION-ID", "tx-2")
                .header("PAYPAL-TRANSMISSION-TIME", "2026-11-10T00:00:00Z")
                .header("PAYPAL-TRANSMISSION-SIG", "tampered")
                .header("PAYPAL-CERT-URL", "https://api.paypal.com/cert.pem")
                .header("PAYPAL-AUTH-ALGO", "SHA256withRSA")
                .content(RAW_EVENT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WEBHOOK_VERIFICATION_FAILED"))
        .andExpect(jsonPath("$.correlationId").value("tx-2"));
  }

  @Test
  void rejectsEmptyBodyBeforeCallingPayPal() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(new byte[0]))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_EVENT"));

    assertThat(verifier.calls).isZero();
  }

  private static final class CapturingVerifier implements PayPalWebhookVerifier {
    private PayPalWebhookHeaders headers;
    private byte[] rawBody;
    private boolean result = true;
    private int calls;

    @Override
    public boolean verify(PayPalWebhookHeaders headers, byte[] rawEventJson) {
      this.headers = headers;
      this.rawBody = rawEventJson;
      this.calls++;
      return result;
    }
  }

  private static final class InMemoryStore implements WebhookEventStore {
    private final Map<String, WebhookEvent> events = new HashMap<>();
    private final Set<String> processed = new HashSet<>();

    @Override
    public boolean insertIfAbsent(WebhookEvent event) {
      return events.putIfAbsent(event.eventId(), event) == null;
    }

    @Override
    public boolean isProcessed(String eventId) {
      return processed.contains(eventId);
    }

    @Override
    public void markProcessed(String eventId, Instant processedAt) {
      processed.add(eventId);
    }
  }
}

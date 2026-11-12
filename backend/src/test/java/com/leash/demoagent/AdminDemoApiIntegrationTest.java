package com.leash.demoagent;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Proves the demo-only admin surface: the profile-gated controller, the in-memory seeder, the admin
 * authentication and CSRF boundary, the deterministic runner, and the fail-closed LLM mode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@Import({TestcontainersConfiguration.class, AdminDemoApiIntegrationTest.TestConfig.class})
class AdminDemoApiIntegrationTest {

  private static final String ADMIN_USERNAME = "admin";
  private static final String ADMIN_PASSWORD = "admin-test-password";
  private static final String CSRF_COOKIE = "XSRF-TOKEN";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";
  private static final DemoBudget BUDGET = new DemoBudget(200_000, 1_200, 15_000, 183_800);

  @Autowired private MockMvc mockMvc;

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    @Primary
    DemoLeashClient demoLeashClient() {
      return new StubDemoLeashClient();
    }
  }

  @Test
  void theDemoStatusIsReadyAndNeverExposesTheApiKey() throws Exception {
    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/demo/status")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.ready").value(true))
        .andExpect(jsonPath("$.agentId").isNotEmpty())
        .andExpect(jsonPath("$.agentName").value("Invoice Agent"))
        .andExpect(jsonPath("$.invoiceCount").value(3))
        .andExpect(jsonPath("$.llmConfigured").value(false))
        .andExpect(jsonPath("$.modes[0]").value("SCRIPTED"))
        .andExpect(jsonPath("$.modes[1]").value("LLM"))
        .andExpect(jsonPath("$.apiKey").doesNotExist());
  }

  @Test
  void runningTheScriptedScenesReturnsTheAgentsSummary() throws Exception {
    String csrf = csrfToken();

    mockMvc
        .perform(withCsrf(mockAdmin(post("/api/v1/admin/demo/run")), csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("SCRIPTED"))
        .andExpect(jsonPath("$.scenes.length()").value(3))
        .andExpect(jsonPath("$.scenes[0].invoiceId").value("inv-1201"))
        .andExpect(jsonPath("$.scenes[0].outcome").value("APPROVE"))
        .andExpect(jsonPath("$.scenes[1].outcome").value("ESCALATE"))
        .andExpect(jsonPath("$.scenes[2].outcome").value("DENY"))
        .andExpect(jsonPath("$.scenes[2].reasons[0].code").value("PAYEE_NOT_ALLOWED"))
        .andExpect(jsonPath("$.summary", containsString("Processed 3 invoices")))
        .andExpect(jsonPath("$.summary", containsString("1 approved, 1 escalated, 1 denied")))
        .andExpect(jsonPath("$.summary", containsString("$1,838.00")));
  }

  @Test
  void theRunRequiresAValidCsrfToken() throws Exception {
    mockMvc
        .perform(mockAdmin(post("/api/v1/admin/demo/run")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
  }

  @Test
  void llmModeFailsClosedWithAnActionableProblem() throws Exception {
    String csrf = csrfToken();

    mockMvc
        .perform(withCsrf(mockAdmin(post("/api/v1/admin/demo/run").param("mode", "LLM")), csrf))
        .andExpect(status().is(501))
        .andExpect(jsonPath("$.code").value("LLM_NOT_CONFIGURED"))
        .andExpect(jsonPath("$.title").value("LLM mode is not configured"));
  }

  @Test
  void theDemoRoutesAreAdminOnly() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/demo/status"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  private String csrfToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(mockAdmin(get("/api/v1/admin/demo/status")))
            .andExpect(status().isOk())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
    return cookie == null ? "" : cookie.getValue();
  }

  private static MockHttpServletRequestBuilder mockAdmin(MockHttpServletRequestBuilder builder) {
    return builder.with(httpBasic(ADMIN_USERNAME, ADMIN_PASSWORD));
  }

  private static MockHttpServletRequestBuilder withCsrf(
      MockHttpServletRequestBuilder builder, String token) {
    return builder.cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
  }

  private static final class StubDemoLeashClient implements DemoLeashClient {

    @Override
    public DemoBudget checkBudget() {
      return BUDGET;
    }

    @Override
    public DemoPayment payInvoice(DemoInvoice invoice) {
      return switch (invoice.id()) {
        case "inv-1201" ->
            new DemoPayment(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "APPROVED",
                "APPROVE",
                List.of(),
                BUDGET,
                false);
        case "inv-1502" ->
            new DemoPayment(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "PENDING_APPROVAL",
                "ESCALATE",
                List.of(),
                BUDGET,
                false);
        default ->
            new DemoPayment(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "DENIED",
                "DENY",
                List.of(
                    new DemoPayment.DemoReason(
                        "PAYEE_NOT_ALLOWED", "Payee is not on the allow-list")),
                BUDGET,
                false);
      };
    }
  }
}

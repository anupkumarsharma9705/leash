package com.leash.demoagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.ObjectMapper;

class DemoInvoiceLoaderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void loadsTheThreeScriptedScenesInOrder() {
    DemoInvoiceLoader loader = new DemoInvoiceLoader(objectMapper);

    assertThat(loader.invoices()).hasSize(3);
    assertThat(loader.invoices())
        .extracting(DemoInvoice::amountMinor)
        .containsExactly(1_200L, 15_000L, 90_000L);
    assertThat(loader.invoices())
        .extracting(DemoInvoice::payeeEmail)
        .containsExactly("alice@example.com", "alice@example.com", "mallory@example.com");
    assertThat(loader.invoices())
        .extracting(DemoInvoice::idempotencyKey)
        .containsExactly("invoice-inv-1201", "invoice-inv-1502", "invoice-inv-9003");
  }

  @Test
  void rejectsDuplicateInvoiceIds() {
    String json =
        """
        {"invoices":[
          {"id":"same","label":"One","payeeEmail":"a@example.com","amountMinor":100,
           "currency":"USD","memo":"one"},
          {"id":"same","label":"Two","payeeEmail":"b@example.com","amountMinor":200,
           "currency":"USD","memo":"two"}
        ]}
        """;

    assertThatThrownBy(() -> loaderFor(json))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate demo invoice id");
  }

  @Test
  void rejectsAnUnsupportedCurrency() {
    String json =
        """
        {"invoices":[
          {"id":"inv-1","label":"One","payeeEmail":"a@example.com","amountMinor":100,
           "currency":"EUR","memo":"one"}
        ]}
        """;

    assertThatThrownBy(() -> loaderFor(json))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("only USD is supported");
  }

  @Test
  void rejectsAnEmptyInvoiceFile() {
    assertThatThrownBy(() -> loaderFor("{\"invoices\":[]}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no invoices");
  }

  private DemoInvoiceLoader loaderFor(String json) {
    return new DemoInvoiceLoader(
        objectMapper, new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
  }
}

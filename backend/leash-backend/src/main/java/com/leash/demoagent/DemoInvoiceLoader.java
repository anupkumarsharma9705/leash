package com.leash.demoagent;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the bundled demo invoices from {@code demo/invoices.json}.
 *
 * <p>Validation happens once, at startup, so a malformed scene file fails fast instead of during
 * the demo. The file is trusted input, but the same validation would apply to any invoice source.
 */
@Component
@Profile("demo")
public class DemoInvoiceLoader {

  static final String RESOURCE_PATH = "demo/invoices.json";

  private final List<DemoInvoice> invoices;

  @org.springframework.beans.factory.annotation.Autowired
  public DemoInvoiceLoader(ObjectMapper objectMapper) {
    this(objectMapper, new ClassPathResource(RESOURCE_PATH));
  }

  DemoInvoiceLoader(ObjectMapper objectMapper, Resource resource) {
    this.invoices = load(objectMapper, resource);
  }

  public List<DemoInvoice> invoices() {
    return invoices;
  }

  private static List<DemoInvoice> load(ObjectMapper objectMapper, Resource resource) {
    if (resource == null || !resource.exists()) {
      throw new IllegalStateException("Demo invoice file is missing: " + RESOURCE_PATH);
    }
    try (InputStream input = resource.getInputStream()) {
      InvoiceFile file = objectMapper.readValue(input, InvoiceFile.class);
      if (file == null || file.invoices() == null || file.invoices().isEmpty()) {
        throw new IllegalStateException("Demo invoice file contains no invoices");
      }
      Set<String> ids = new HashSet<>();
      for (DemoInvoice invoice : file.invoices()) {
        if (!ids.add(invoice.id())) {
          throw new IllegalStateException("Duplicate demo invoice id: " + invoice.id());
        }
      }
      return List.copyOf(file.invoices());
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read demo invoice file", exception);
    } catch (JacksonException exception) {
      String detail = exception.getOriginalMessage();
      throw new IllegalStateException(
          detail == null || detail.isBlank()
              ? "Demo invoice file is not valid JSON"
              : "Demo invoice file is invalid: " + detail,
          exception);
    }
  }

  /** The JSON envelope. Package-private so Jackson can bind it without exposing it as API. */
  record InvoiceFile(List<DemoInvoice> invoices) {}
}

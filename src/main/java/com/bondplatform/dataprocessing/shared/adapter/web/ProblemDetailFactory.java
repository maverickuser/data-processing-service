package com.bondplatform.dataprocessing.shared.adapter.web;

import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds the problem-details body used for every HTTP error.
 *
 * <p>Each problem gets a fresh correlation identifier, exposed both as {@code correlationId} and as
 * the {@code instance} URI. Server-side failures are logged with the same identifier.
 */
@Component
public class ProblemDetailFactory {

  private static final String CORRELATION_ID = "correlationId";

  private final IdSupplier idSupplier;

  /** Creates a factory that takes correlation identifiers from the given supplier. */
  public ProblemDetailFactory(IdSupplier idSupplier) {
    this.idSupplier = idSupplier;
  }

  /**
   * Creates the problem body for one occurrence.
   *
   * @param type the kind of problem, which fixes the status, title, type URI, and code
   * @param detail an explanation of this occurrence that is safe to show to the caller
   */
  public ProblemDetail create(ProblemType type, String detail) {
    UUID correlationId = idSupplier.nextId();
    ProblemDetail problem = ProblemDetail.forStatus(type.status());
    problem.setType(type.typeUri());
    problem.setTitle(type.title());
    problem.setDetail(detail);
    problem.setInstance(URI.create("urn:uuid:" + correlationId));
    problem.setProperty("code", type.code());
    problem.setProperty(CORRELATION_ID, correlationId.toString());
    return problem;
  }

  /** Returns the correlation identifier of a problem created by this factory. */
  public static String correlationIdOf(ProblemDetail problem) {
    Map<String, Object> properties = problem.getProperties();
    Object correlationId = properties == null ? null : properties.get(CORRELATION_ID);
    if (correlationId == null) {
      throw new IllegalArgumentException("Problem was not created by ProblemDetailFactory");
    }
    return correlationId.toString();
  }
}

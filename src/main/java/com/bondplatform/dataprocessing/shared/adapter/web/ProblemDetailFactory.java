package com.bondplatform.dataprocessing.shared.adapter.web;

import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds the problem-details body used for every HTTP error.
 *
 * <p>Each problem gets a fresh correlation identifier, exposed both as {@code correlationId} and as
 * the {@code instance} URI, so one occurrence can be found in the logs.
 */
@Component
public class ProblemDetailFactory {

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
    problem.setProperty("correlationId", correlationId.toString());
    return problem;
  }
}

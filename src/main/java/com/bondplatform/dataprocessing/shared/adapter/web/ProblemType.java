package com.bondplatform.dataprocessing.shared.adapter.web;

import java.net.URI;
import java.util.Locale;

/**
 * The kinds of HTTP error this service returns as RFC 9457 problem details.
 *
 * <p>The enum name is the stable {@code code} property of the response and is part of the public
 * contract; renaming a constant is a breaking change.
 */
public enum ProblemType {
  INVALID_REQUEST(400, "Invalid Request"),
  FORBIDDEN(403, "Forbidden"),
  NOT_FOUND(404, "Not Found"),
  METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
  NOT_ACCEPTABLE(406, "Not Acceptable"),
  IDEMPOTENCY_CONFLICT(409, "Idempotency Conflict"),
  REQUEST_TOO_LARGE(413, "Request Too Large"),
  UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
  ADMISSION_RATE_LIMITED(429, "Admission Rate Limited"),
  INTERNAL_ERROR(500, "Internal Error"),
  SERVICE_UNAVAILABLE(503, "Service Unavailable");

  private static final String TYPE_PREFIX = "urn:bond-platform:problem:";

  private final int status;
  private final String title;

  ProblemType(int status, String title) {
    this.status = status;
    this.title = title;
  }

  /**
   * Returns the problem type for an error status raised by the web framework itself, such as a
   * missing parameter or an unsupported method.
   *
   * <p>A status with no dedicated type becomes {@link #INVALID_REQUEST} when it is a client error
   * and {@link #INTERNAL_ERROR} otherwise, so the caller always receives a documented type.
   * Statuses that only application code decides (conflict, rate limiting) are never inferred here.
   */
  public static ProblemType forFrameworkStatus(int status) {
    return switch (status) {
      case 404 -> NOT_FOUND;
      case 405 -> METHOD_NOT_ALLOWED;
      case 406 -> NOT_ACCEPTABLE;
      case 413 -> REQUEST_TOO_LARGE;
      case 415 -> UNSUPPORTED_MEDIA_TYPE;
      case 503 -> SERVICE_UNAVAILABLE;
      default -> status >= 400 && status < 500 ? INVALID_REQUEST : INTERNAL_ERROR;
    };
  }

  /** Returns the HTTP status code. */
  public int status() {
    return status;
  }

  /** Returns the short, human-readable summary that is the same for every occurrence. */
  public String title() {
    return title;
  }

  /** Returns the stable machine-readable code, equal to the constant's name. */
  public String code() {
    return name();
  }

  /** Returns the problem type URI, for example {@code urn:bond-platform:problem:not-found}. */
  public URI typeUri() {
    return URI.create(TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }
}

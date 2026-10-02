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
  NOT_FOUND(404, "Not Found"),
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

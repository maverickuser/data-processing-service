package com.bondplatform.dataprocessing.admission.domain;

import org.jspecify.annotations.Nullable;

/**
 * One reason a submission is rejected, in the shape the API's problem response lists them.
 *
 * @param location whether the fault is in the body or in a header
 * @param pointer a JSON Pointer into the body, for body faults
 * @param header the header name, for header faults
 * @param code a stable code for the kind of fault
 * @param message an explanation for the producer
 */
public record SubmissionError(
    Location location,
    @Nullable String pointer,
    @Nullable String header,
    Code code,
    String message) {

  /** Creates a fault at a place in the request body. */
  public static SubmissionError inBody(String pointer, Code code, String message) {
    return new SubmissionError(Location.BODY, pointer, null, code, message);
  }

  /** Creates a fault in a request header. */
  public static SubmissionError inHeader(String header, Code code, String message) {
    return new SubmissionError(Location.HEADER, null, header, code, message);
  }

  /** Where in the request a fault is. */
  public enum Location {
    BODY,
    HEADER
  }

  /** The kinds of fault. The names are part of the API contract. */
  public enum Code {
    /** A required property or header is missing or empty. */
    REQUIRED,
    /** A value has the wrong type or form. */
    INVALID_VALUE,
    /** A value is well formed but not one this service accepts. */
    UNSUPPORTED_VALUE,
    /** A property that the schema does not allow is present. */
    UNKNOWN_PROPERTY,
    /** Two parts of the request that must agree do not. */
    MISMATCH
  }
}

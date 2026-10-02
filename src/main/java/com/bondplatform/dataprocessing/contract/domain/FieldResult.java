package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;

/**
 * The outcome of reading one field: a value, no value, or a rejection with its reason.
 *
 * <p>Bad source data is a result, not an exception, so that every problem in a file can be
 * collected and reported.
 *
 * @param <T> the type of an accepted value
 */
public sealed interface FieldResult<T> {

  /** The field holds an accepted value. */
  record Valid<T>(T value) implements FieldResult<T> {}

  /** The field holds nothing to act on; this is not an error. */
  record NoValue<T>(FieldPresence presence) implements FieldResult<T> {}

  /** The field holds something that was rejected. */
  record Rejected<T>(ErrorCode code, String message) implements FieldResult<T> {}
}

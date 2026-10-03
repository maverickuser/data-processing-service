package com.bondplatform.dataprocessing.source.domain;

/**
 * Why a job's sources cannot be processed. Every code fails the job without a retry: listed objects
 * are immutable, so a retry would meet the same problem.
 *
 * @param detail an explanation for the job's error record; never source data
 */
public record SourceProblem(Code code, String detail) {

  /** The codes, which are part of the public status API. */
  public enum Code {
    /**
     * The manifest is not a well-formed manifest event, lists no usable file set, or describes
     * another run, dataset, or input than the submission; the detail says which.
     */
    INVALID_MANIFEST,
    /** The manifest, a CSV, or the listed JSON files together are larger than allowed. */
    SOURCE_TOO_LARGE
  }
}

package com.bondplatform.dataprocessing.source.domain;

/**
 * Why a job's sources cannot be processed. Every code but {@link Code#SOURCE_UNAVAILABLE} fails the
 * job without a retry: listed objects are immutable, so a retry would meet the same problem.
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
    SOURCE_TOO_LARGE,
    /** A listed object, or the manifest, does not exist or may not be read (LLD section 21). */
    SOURCE_NOT_FOUND,
    /** A listed file's size or SHA-256 differs from the manifest's (LLD section 19). */
    CHECKSUM_MISMATCH,
    /** A listed file's name does not identify its trade date and exchange, or its security. */
    INVALID_SOURCE_FILENAME,
    /** A CSV filename's exchange or trade date differs from the manifest's inputs. */
    TRADE_DATE_MISMATCH,
    /** Storage could not be read for a reason that may pass; the attempt is retried. */
    SOURCE_UNAVAILABLE
  }
}

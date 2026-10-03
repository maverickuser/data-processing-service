package com.bondplatform.dataprocessing.source.domain;

/**
 * The outcome of reading one listed file.
 *
 * <p>A mismatch is a result, not a failure, because what follows depends on the dataset: for a CSV
 * it fails the job, for JSON only that file is skipped (LLD section 19).
 */
public sealed interface SourceRead {

  /** The content matched the manifest. */
  record Verified(VerifiedSource source) implements SourceRead {}

  /**
   * The content's size or SHA-256 differs from the manifest's: {@code CHECKSUM_MISMATCH}, never
   * retried, because listed objects are immutable.
   *
   * @param detail what differs; never the content
   */
  record ChecksumMismatch(ManifestFile file, String detail) implements SourceRead {}
}

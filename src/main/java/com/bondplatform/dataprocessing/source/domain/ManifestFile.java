package com.bondplatform.dataprocessing.source.domain;

import org.jspecify.annotations.Nullable;

/**
 * One file a manifest lists: the exact S3 object to read and what it must contain.
 *
 * @param fetchJobId the fetch-service job that produced the file; provenance only
 * @param key the exact object key; never a prefix
 * @param sha256 the expected SHA-256 of the object, as 64 lowercase hex digits
 * @param sizeBytes the expected object size
 * @param sourceUrl where the fetch service downloaded the file from; provenance only, never read
 */
public record ManifestFile(
    @Nullable String fetchJobId,
    String bucket,
    String key,
    SourceFormat format,
    String sha256,
    long sizeBytes,
    @Nullable String sourceUrl) {

  /** Returns the object's file name: the key after its last slash. */
  public String fileName() {
    return key.substring(key.lastIndexOf('/') + 1);
  }
}

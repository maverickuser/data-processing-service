package com.bondplatform.dataprocessing.job.domain;

import org.jspecify.annotations.Nullable;

/**
 * The exact, immutable S3 object holding a submission's manifest; never a prefix.
 *
 * @param versionId the S3 version to read, when the producer pinned one
 */
public record ManifestLocation(String bucket, String key, @Nullable String versionId) {}

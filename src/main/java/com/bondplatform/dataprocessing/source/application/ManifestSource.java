package com.bondplatform.dataprocessing.source.application;

import com.bondplatform.dataprocessing.job.domain.ManifestLocation;

/** Reads a manifest object from storage. */
public interface ManifestSource {

  /**
   * Returns the whole object at the location.
   *
   * @param maxBytes the largest object accepted
   * @throws com.bondplatform.dataprocessing.job.application.PermanentFailureException with code
   *     {@code SOURCE_NOT_FOUND} if the object does not exist, or {@code SOURCE_TOO_LARGE} if it is
   *     larger than {@code maxBytes}
   * @throws com.bondplatform.dataprocessing.job.application.TemporaryFailureException if storage
   *     could not be read for a reason that may pass
   */
  byte[] read(ManifestLocation location, long maxBytes);
}

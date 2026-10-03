package com.bondplatform.dataprocessing.source.application;

import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceRead;

/** Reads the files a manifest lists, checking each against the manifest as it is read. */
public interface SourceObjectReader {

  /**
   * Reads the whole file and checks its size and SHA-256 against the manifest entry.
   *
   * @return the verified content, or the mismatch
   * @throws com.bondplatform.dataprocessing.job.application.PermanentFailureException with code
   *     {@code SOURCE_NOT_FOUND} if the object does not exist or may not be read, or {@code
   *     INVALID_MANIFEST} if its bucket is not one the service reads from
   * @throws com.bondplatform.dataprocessing.job.application.TemporaryFailureException with code
   *     {@code SOURCE_UNAVAILABLE} if storage could not be read for a reason that may pass
   */
  SourceRead read(ManifestFile file);
}

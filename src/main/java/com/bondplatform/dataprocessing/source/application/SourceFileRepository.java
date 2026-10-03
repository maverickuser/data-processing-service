package com.bondplatform.dataprocessing.source.application;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import java.util.List;

/** Stores the files a request's manifest lists, in the caller's transaction. */
public interface SourceFileRepository {

  /**
   * Records each file for the job unless it is already recorded, so a retried attempt stores
   * nothing twice.
   */
  void saveAll(JobId jobId, List<ManifestFile> files);
}

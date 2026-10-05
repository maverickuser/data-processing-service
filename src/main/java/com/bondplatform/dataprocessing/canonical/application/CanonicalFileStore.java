package com.bondplatform.dataprocessing.canonical.application;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import java.util.List;

/**
 * Where each run's complete canonical output is kept: one immutable JSON Lines object per run,
 * accepted and rejected rows alike (LLD section 17.5).
 */
public interface CanonicalFileStore {

  /** Starts a run's canonical file; nothing is stored until it is committed. */
  CanonicalFile create(CanonicalRun run);

  /**
   * Stores a JSON request's canonical file in one call, one line per source file. A request's JSON
   * files are bounded in total size (LLD section 1), so the file is built in memory.
   *
   * @param files every listed file's evidence, in manifest order
   * @return the object key, to be recorded on the processing run
   * @throws TemporaryFailureException if storage cannot be reached, so the attempt is retried
   */
  String storeJson(JsonCanonicalRun run, List<JsonFileEvidence> files);
}

package com.bondplatform.dataprocessing.canonical.application;

import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import java.util.List;
import java.util.UUID;

/**
 * Where the rejected parts of a JSON request's files and their issues are kept for the error-review
 * API (LLD sections 16 and 17.5).
 */
public interface JsonRejectionStore {

  /**
   * Stores every rejected part of a JSON request's files with its issues, in the caller's
   * transaction. Issues are numbered from 1 in file order, then in each file's review order.
   *
   * @param run the run the files belong to
   * @param processingRunId the attempt's processing run
   * @param files every listed file's evidence, in manifest order
   * @return the number of issues stored, the request's error count
   */
  long saveJson(JsonCanonicalRun run, UUID processingRunId, List<JsonFileEvidence> files);
}

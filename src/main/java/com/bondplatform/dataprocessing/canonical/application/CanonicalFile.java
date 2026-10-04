package com.bondplatform.dataprocessing.canonical.application;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;

/**
 * One run's canonical file while it is being written. Rows are appended in file order and stored
 * together on {@link #commit}; closing without committing discards them.
 */
public interface CanonicalFile extends AutoCloseable {

  /** Appends one canonical row as one line. */
  void append(CanonicalRow row);

  /**
   * Stores the file.
   *
   * @return the object key, to be recorded on the processing run
   * @throws TemporaryFailureException if storage cannot be reached, so the attempt is retried
   */
  String commit();

  /** Releases local resources, discarding the rows if the file was not committed. */
  @Override
  void close();
}

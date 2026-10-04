package com.bondplatform.dataprocessing.canonical.application;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;

/**
 * Where each run's complete canonical output is kept: one immutable JSON Lines object per run,
 * accepted and rejected rows alike (LLD section 17.5).
 */
public interface CanonicalFileStore {

  /** Starts a run's canonical file; nothing is stored until it is committed. */
  CanonicalFile create(CanonicalRun run);
}

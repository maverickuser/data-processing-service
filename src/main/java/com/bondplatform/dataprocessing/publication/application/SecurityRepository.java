package com.bondplatform.dataprocessing.publication.application;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** Stores securities. */
public interface SecurityRepository {

  /**
   * Creates an ISIN-only security for each ISIN that has none, and reports which ones it created.
   *
   * <p>The answer comes from the insert itself, not from a check before it, so two transactions
   * introducing the same ISIN at once cannot both be told they created it. The caller uses the
   * answer to request security details exactly once per new security (LLD section 14.3).
   *
   * @param recordedAt the creation time to store
   * @return the ISINs that did not exist before this call
   */
  Set<Isin> insertMissing(Collection<Isin> isins, Instant recordedAt);
}

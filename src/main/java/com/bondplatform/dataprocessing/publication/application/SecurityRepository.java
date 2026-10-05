package com.bondplatform.dataprocessing.publication.application;

import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
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

  /**
   * Locks the security's row until the transaction ends and returns its stored field values.
   *
   * @return the values by internal field name, such as {@code couponRate}; a field with no value is
   *     absent
   * @throws IllegalStateException if the security does not exist
   * @throws IllegalArgumentException if a stored percentage is one {@link
   *     com.bondplatform.dataprocessing.shared.domain.Percent} cannot hold; only a row edited by
   *     hand can have one, as every write goes through the bounded stage-1 check
   */
  Map<String, SecurityValue> lockValues(Isin isin);

  /**
   * Stores changed security fields: each set field gets its value and source, each cleared field
   * loses both, and the update time becomes {@code changedAt}. Nothing is written when there are no
   * changes.
   *
   * @param changes only fields that differ from the stored values (LLD section 13.5)
   * @throws IllegalStateException if the security does not exist
   */
  void applyChanges(Isin isin, SecurityScalars changes, Instant changedAt);
}

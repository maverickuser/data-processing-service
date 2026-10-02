package com.bondplatform.dataprocessing.publication.application;

import com.bondplatform.dataprocessing.publication.domain.CashFlow;
import com.bondplatform.dataprocessing.publication.domain.CollateralAsset;
import com.bondplatform.dataprocessing.publication.domain.Listing;
import com.bondplatform.dataprocessing.publication.domain.Rating;
import com.bondplatform.dataprocessing.publication.domain.SecurityEntry;
import java.time.Instant;
import java.util.Collection;

/**
 * Stores the append-only collections of a security: cash flows, listings, ratings, and collateral
 * assets (LLD section 13.6).
 *
 * <p>An entry is appended only if the security has no entry with the same values, where two absent
 * values count as equal. An existing entry is never changed or removed, and keeps its original
 * source reference and first-recorded time. Every security must already exist. A caller must not
 * pass an entry whose values are all absent; whether an entry has anything worth storing is decided
 * by the mapper (LLD section 13.4).
 */
public interface SecurityCollectionRepository {

  /**
   * Appends the cash flows the security does not already have.
   *
   * @param recordedAt stored as the first-recorded time of each appended entry
   * @return how many entries were appended
   */
  int appendCashFlows(Collection<SecurityEntry<CashFlow>> entries, Instant recordedAt);

  /** Appends the listings the security does not already have; see {@link #appendCashFlows}. */
  int appendListings(Collection<SecurityEntry<Listing>> entries, Instant recordedAt);

  /** Appends the ratings the security does not already have; see {@link #appendCashFlows}. */
  int appendRatings(Collection<SecurityEntry<Rating>> entries, Instant recordedAt);

  /**
   * Appends the collateral assets the security does not already have; see {@link #appendCashFlows}.
   */
  int appendCollateralAssets(
      Collection<SecurityEntry<CollateralAsset>> entries, Instant recordedAt);
}

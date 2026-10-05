package com.bondplatform.dataprocessing.publication.domain;

import java.util.List;

/**
 * The distinct collection entries one request supplies for a security (LLD section 13.6).
 *
 * <p>Each list holds every entry at most once by value; the entries already stored are not
 * consulted, so publication still skips an entry it has seen before.
 *
 * @param cashFlows scheduled payments
 * @param listings exchange listings
 * @param ratings credit-rating observations, current and earlier
 * @param collateralAssets assets securing the security
 */
public record SecurityCollections(
    List<SecurityEntry<CashFlow>> cashFlows,
    List<SecurityEntry<Listing>> listings,
    List<SecurityEntry<Rating>> ratings,
    List<SecurityEntry<CollateralAsset>> collateralAssets) {

  /** Copies the lists. */
  public SecurityCollections {
    cashFlows = List.copyOf(cashFlows);
    listings = List.copyOf(listings);
    ratings = List.copyOf(ratings);
    collateralAssets = List.copyOf(collateralAssets);
  }

  /** Returns these entries without any collateral asset. */
  public SecurityCollections withoutCollateralAssets() {
    return new SecurityCollections(cashFlows, listings, ratings, List.of());
  }

  /** Returns whether there is no entry at all. */
  public boolean isEmpty() {
    return cashFlows.isEmpty()
        && listings.isEmpty()
        && ratings.isEmpty()
        && collateralAssets.isEmpty();
  }
}

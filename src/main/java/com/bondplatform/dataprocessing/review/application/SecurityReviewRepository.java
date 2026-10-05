package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.List;
import java.util.Optional;

/** Reads a security and its recorded collections, each in a stable order. */
public interface SecurityReviewRepository {

  /** Returns the security's own fields, if it is known. */
  Optional<SecurityView.Scalars> find(Isin isin);

  /** Returns every recorded collateral asset, oldest first. */
  List<SecurityView.CollateralAsset> collateralAssets(Isin isin);

  /** Returns every recorded rating, current and earlier. */
  List<RatingObservation> ratings(Isin isin);

  /** Returns every recorded listing, by listing date. */
  List<SecurityView.Listing> listings(Isin isin);

  /** Returns every recorded cash flow, by due date. */
  List<SecurityView.CashFlow> cashFlows(Isin isin);
}

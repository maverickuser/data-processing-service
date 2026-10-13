package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityMetrics;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Reads a security and its recorded collections, each in a stable order. */
public interface SecurityReviewRepository {

  /** Returns up to {@code limit} ISINs in ascending order, after {@code after} when given. */
  List<String> isins(@Nullable String after, int limit);

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

  /** Returns the security counts, all read in one statement. */
  SecurityMetrics metrics();
}

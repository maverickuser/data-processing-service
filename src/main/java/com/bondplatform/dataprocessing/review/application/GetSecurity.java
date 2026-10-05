package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.mapping.domain.CollateralStatusRule;
import com.bondplatform.dataprocessing.review.domain.CurrentRatingSelector;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.List;
import java.util.Optional;

/**
 * Answers {@code GET /v1/securities/{isin}} (LLD section 20.1).
 *
 * <p>Collateral assets are all shown unless the status is {@code Unsecured}, in any letter case,
 * when none are (section 15.2, owner decision AH-2); current ratings are derived at read time
 * (section 13.6).
 */
public class GetSecurity {

  private final SecurityReviewRepository securities;

  /** Creates the use case. */
  public GetSecurity(SecurityReviewRepository securities) {
    this.securities = securities;
  }

  /** Returns the security, or empty if it is not known. */
  public Optional<SecurityView> find(Isin isin) {
    return securities.find(isin).map(scalars -> view(isin, scalars));
  }

  private SecurityView view(Isin isin, SecurityView.Scalars scalars) {
    CurrentRatingSelector.Selection ratings =
        CurrentRatingSelector.select(securities.ratings(isin));
    return new SecurityView(
        scalars,
        CollateralStatusRule.isUnsecured(scalars.collateralStatus())
            ? List.of()
            : securities.collateralAssets(isin),
        ratings.current(),
        ratings.history(),
        securities.listings(isin),
        securities.cashFlows(isin));
  }
}

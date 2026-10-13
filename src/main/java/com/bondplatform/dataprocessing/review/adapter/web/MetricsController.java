package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.application.GetSecurityMetrics;
import com.bondplatform.dataprocessing.review.domain.SecurityMetrics;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the business metrics (LLD section 20.5). */
@RestController
@ConditionalOnBooleanProperty("data-processing.api.read-routes")
public class MetricsController {

  private final GetSecurityMetrics getSecurityMetrics;

  /** Creates the controller. */
  public MetricsController(GetSecurityMetrics getSecurityMetrics) {
    this.getSecurityMetrics = getSecurityMetrics;
  }

  /** How long a client may reuse the counts: each request reads every security. */
  static final Duration MAX_AGE = Duration.ofMinutes(1);

  /** Returns the security counts as they are now; clients may reuse them for a minute. */
  @GetMapping(path = "/v1/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<MetricsResponse> metrics() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
        .body(MetricsResponse.of(getSecurityMetrics.current()));
  }

  /** The body of {@code GET /v1/metrics}, as the read OpenAPI defines it. */
  record MetricsResponse(
      long securitiesCount,
      long securitiesCountWithoutDetails,
      long securitiesCountWithoutCashFlows,
      long securitiesCountWithoutCurrentRatings,
      long securitiesCountWithoutCouponRate,
      long securitiesCountWithoutCollateralStatus) {

    static MetricsResponse of(SecurityMetrics metrics) {
      return new MetricsResponse(
          metrics.securities(),
          metrics.withoutDetails(),
          metrics.withoutCashFlows(),
          metrics.withoutCurrentRatings(),
          metrics.withoutCouponRate(),
          metrics.withoutCollateralStatus());
    }
  }
}

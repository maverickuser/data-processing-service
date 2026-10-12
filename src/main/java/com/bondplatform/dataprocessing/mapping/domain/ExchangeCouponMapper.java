package com.bondplatform.dataprocessing.mapping.domain;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.Disposition;
import com.bondplatform.dataprocessing.canonical.domain.ValidationStatus;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.publication.domain.ExchangeCoupon;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Takes the coupon rate an accepted bhavcopy row reports for its security, as the mapping
 * contract's {@code securityFields} direct (decision 0004).
 *
 * <p>A row gives a rate only when it is {@link Disposition#ACCEPTED} and its coupon field passed
 * with a value; a blank, zero, or ignored invalid value gives none. A contract that maps no coupon
 * field gives no rates at all.
 */
public final class ExchangeCouponMapper {

  private static final String COUPON_RATE = "couponRate";

  private final @Nullable String couponField;
  private final String isinField;

  /**
   * Creates a mapper for a daily market summary contract.
   *
   * @throws IllegalArgumentException if the contract fills a security field other than the coupon
   *     rate, which this mapper cannot publish
   */
  public ExchangeCouponMapper(MappingContract contract) {
    String coupon = null;
    for (Map.Entry<String, String> mapped : contract.securityFields().entrySet()) {
      if (!mapped.getValue().equals(COUPON_RATE)) {
        throw new IllegalArgumentException(
            "Contract " + contract.id() + " fills security field " + mapped.getValue());
      }
      coupon = mapped.getKey();
    }
    this.couponField = coupon;
    this.isinField =
        contract.primary().fields().entrySet().stream()
            .filter(mapped -> mapped.getValue().equals("isin"))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Contract " + contract.id() + " does not map the ISIN"));
  }

  /**
   * Maps one row's coupon rate, or returns empty when the row gives none.
   *
   * @param run the attempt, which gives the trade date, job, and source file
   */
  public Optional<ExchangeCoupon> map(CanonicalRun run, CanonicalRow row) {
    if (couponField == null || row.disposition() != Disposition.ACCEPTED) {
      return Optional.empty();
    }
    CanonicalField coupon = row.record().field(couponField);
    String rate = coupon.parsedValue();
    String isin = row.record().field(isinField).parsedValue();
    if (coupon.validationStatus() != ValidationStatus.PASSED || rate == null || isin == null) {
      return Optional.empty();
    }
    String key = run.sourceKey();
    return Optional.of(
        new ExchangeCoupon(
            new Isin(isin),
            new TradeDate(run.tradeDate()),
            Percent.of(new BigDecimal(rate)),
            new SourceReference(
                run.jobId(),
                key.substring(key.lastIndexOf('/') + 1),
                Long.toString(row.record().recordNumber()))));
  }
}

package com.bondplatform.dataprocessing.publication.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One scheduled interest or redemption payment of a security, as observed in the source.
 *
 * <p>Every value but the source category is optional. Two cash flows are the same entry when all
 * their values are equal, with two absent values counting as equal (LLD section 13.4). The source
 * category is not part of that identity: a payment listed in both NSDL lists is one entry.
 *
 * @param sourceCategory which NSDL list the cash flow came from
 * @param eventType what the payment is, for example {@code Interest} or {@code Partial Redemption}
 * @param newFaceValue the face value after the event; supplied on partial redemptions
 */
public record CashFlow(
    SourceCategory sourceCategory,
    @Nullable String eventType,
    @Nullable LocalDate recordDate,
    @Nullable LocalDate dueDate,
    @Nullable BigDecimal amountPayable,
    @Nullable LocalDate paymentDate,
    @Nullable BigDecimal newFaceValue) {

  /** Rejects a cash flow without its source category. */
  public CashFlow {
    Objects.requireNonNull(sourceCategory, "sourceCategory");
  }

  /** The NSDL list a cash flow was read from (decision 0007). */
  public enum SourceCategory {
    /** {@code cashFlowSchedule}: the scheduled payments. */
    SCHEDULE,
    /** {@code interestCashFlowSchedule}: the interest payments. */
    INTEREST
  }
}

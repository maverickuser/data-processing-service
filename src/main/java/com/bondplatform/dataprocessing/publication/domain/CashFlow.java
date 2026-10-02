package com.bondplatform.dataprocessing.publication.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One scheduled interest or redemption payment of a security, as observed in the source.
 *
 * <p>Every value is optional. Two cash flows are the same entry when all their values are equal,
 * with two absent values counting as equal (LLD section 13.4).
 *
 * @param eventType what the payment is, for example {@code Interest} or {@code Partial Redemption}
 * @param newFaceValue the face value after the event; supplied on partial redemptions
 */
public record CashFlow(
    @Nullable String eventType,
    @Nullable LocalDate recordDate,
    @Nullable LocalDate dueDate,
    @Nullable BigDecimal amountPayable,
    @Nullable LocalDate paymentDate,
    @Nullable BigDecimal newFaceValue) {}

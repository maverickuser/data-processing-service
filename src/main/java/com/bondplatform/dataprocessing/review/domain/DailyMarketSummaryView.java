package com.bondplatform.dataprocessing.review.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One stored daily market summary as the read API shows it (LLD section 20.2): no source
 * references.
 */
public record DailyMarketSummaryView(
    String isin,
    LocalDate tradeDate,
    String exchangeName,
    @Nullable String securityCode,
    @Nullable BigDecimal openPrice,
    @Nullable BigDecimal highPrice,
    @Nullable BigDecimal lowPrice,
    @Nullable BigDecimal closePrice,
    @Nullable BigInteger tradedVolume,
    @Nullable BigInteger numberOfTrades,
    @Nullable BigDecimal turnover,
    @Nullable BigDecimal faceValue,
    Instant createdAt,
    Instant updatedAt) {}

package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One security's trading on one exchange on one day: the aggregate a bhavcopy row reports.
 *
 * <p>The security, trade date, and exchange identify the summary. Every other value is optional,
 * and an absent value is meaningful: publishing a summary replaces the stored one entirely, so a
 * {@code null} here clears what was stored before (LLD section 7).
 *
 * @param source the canonical row this summary was mapped from
 */
public record DailyMarketSummary(
    Isin isin,
    TradeDate tradeDate,
    ExchangeName exchangeName,
    @Nullable String securityCode,
    @Nullable BigDecimal openPrice,
    @Nullable BigDecimal highPrice,
    @Nullable BigDecimal lowPrice,
    @Nullable BigDecimal closePrice,
    @Nullable BigInteger tradedVolume,
    @Nullable BigInteger numberOfTrades,
    @Nullable BigDecimal turnover,
    @Nullable BigDecimal faceValue,
    SourceReference source) {

  /** Rejects a summary without its identity or its source. */
  public DailyMarketSummary {
    Objects.requireNonNull(isin, "isin");
    Objects.requireNonNull(tradeDate, "tradeDate");
    Objects.requireNonNull(exchangeName, "exchangeName");
    Objects.requireNonNull(source, "source");
  }
}

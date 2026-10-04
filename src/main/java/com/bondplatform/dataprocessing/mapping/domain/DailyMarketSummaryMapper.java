package com.bondplatform.dataprocessing.mapping.domain;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.Disposition;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns accepted CSV rows into daily market summaries, as the mapping contract directs (LLD
 * sections 2.2 and 6.2).
 *
 * <p>The contract only renames: each canonical field's parsed value goes to the internal field the
 * contract names, and an internal field the contract does not map stays {@code null}. The trade
 * date and exchange come from the manifest, never from the row. Rows that are not {@link
 * Disposition#ACCEPTED} are not mapped.
 */
public final class DailyMarketSummaryMapper {

  private static final String ISIN = "isin";

  /** The internal fields this mapper reads; the same set {@link InternalModel} allows. */
  static final Set<String> FIELDS =
      Set.of(
          ISIN,
          "securityCode",
          "openPrice",
          "highPrice",
          "lowPrice",
          "closePrice",
          "tradedVolume",
          "numberOfTrades",
          "turnover",
          "faceValue");

  private final Map<String, String> fields;

  /**
   * Creates a mapper for a daily market summary contract.
   *
   * @throws IllegalArgumentException if the contract targets another table, maps to a field this
   *     mapper does not read, or does not map the ISIN
   */
  public DailyMarketSummaryMapper(MappingContract contract) {
    MappingContract.RecordMapping primary = contract.primary();
    if (!primary.target().equals(InternalModel.DAILY_MARKET_SUMMARIES)) {
      throw new IllegalArgumentException(
          "Contract " + contract.id() + " does not target daily market summaries");
    }
    for (String internal : primary.fields().values()) {
      if (!FIELDS.contains(internal)) {
        throw new IllegalArgumentException(
            "Contract " + contract.id() + " maps to unknown field " + internal);
      }
    }
    if (!primary.fields().containsValue(ISIN)) {
      throw new IllegalArgumentException("Contract " + contract.id() + " does not map the ISIN");
    }
    this.fields = primary.fields();
  }

  /**
   * Maps one row, or returns empty when the row is quarantined.
   *
   * @param run the attempt, which gives the trade date, job, and source file
   * @param exchangeName the exchange the manifest names
   * @param row the canonical row
   */
  public Optional<DailyMarketSummary> map(
      CanonicalRun run, ExchangeName exchangeName, CanonicalRow row) {
    if (row.disposition() != Disposition.ACCEPTED) {
      return Optional.empty();
    }
    Map<String, String> values = new HashMap<>();
    fields.forEach(
        (canonical, internal) -> {
          String parsed = row.record().field(canonical).parsedValue();
          if (parsed != null) {
            values.put(internal, parsed);
          }
        });
    String isin = values.get(ISIN);
    if (isin == null) {
      throw new IllegalArgumentException(
          "Accepted record " + row.record().recordNumber() + " has no ISIN");
    }
    String key = run.sourceKey();
    return Optional.of(
        new DailyMarketSummary(
            new Isin(isin),
            new TradeDate(run.tradeDate()),
            exchangeName,
            values.get("securityCode"),
            decimal(values.get("openPrice")),
            decimal(values.get("highPrice")),
            decimal(values.get("lowPrice")),
            decimal(values.get("closePrice")),
            whole(values.get("tradedVolume")),
            whole(values.get("numberOfTrades")),
            decimal(values.get("turnover")),
            decimal(values.get("faceValue")),
            new SourceReference(
                run.jobId(),
                key.substring(key.lastIndexOf('/') + 1),
                Long.toString(row.record().recordNumber()))));
  }

  private static @Nullable BigDecimal decimal(@Nullable String value) {
    return value == null ? null : new BigDecimal(value);
  }

  private static @Nullable BigInteger whole(@Nullable String value) {
    return value == null ? null : new BigInteger(value);
  }
}

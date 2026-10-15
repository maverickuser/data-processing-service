package com.bondplatform.dataprocessing.publication.adapter.persistence;

import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.publication.application.SecurityRepository;
import com.bondplatform.dataprocessing.publication.domain.CouponSource;
import com.bondplatform.dataprocessing.publication.domain.ExchangeCoupon;
import com.bondplatform.dataprocessing.publication.domain.LockedSecurity;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.publication.domain.StoredCoupon;
import com.bondplatform.dataprocessing.shared.domain.CanonicalJson;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * Stores securities in {@code securities_data.securities}.
 *
 * <p>Each field's source is kept in the {@code field_sources} JSON object under the field's
 * internal name, as {@code {"sourceRequestId", "sourceFile", "sourceLocation"}}: the same reference
 * the collection tables keep in columns (LLD section 17.5). The coupon rate also keeps which
 * dataset supplied it and, for a bhavcopy, its trade date (decision 0004).
 */
@Repository
public class JdbcSecurityRepository implements SecurityRepository {

  /**
   * One statement for the whole set, with the ISINs passed as a single array parameter. They are
   * inserted in sorted order so that two concurrent transactions take their row locks in the same
   * order and cannot deadlock.
   */
  static final String INSERT_MISSING =
      """
      INSERT INTO securities_data.securities (isin, created_at, updated_at)
      SELECT new_security.isin, ?, ?
      FROM unnest(?::text[]) AS new_security (isin)
      ORDER BY new_security.isin
      ON CONFLICT (isin) DO NOTHING
      RETURNING isin
      """;

  /** The security fields and the columns that store them. */
  static final List<Column> COLUMNS =
      List.of(
          new Column("issuerName", FieldType.TEXT, "issuer_name", null),
          new Column("issuerOwnershipType", FieldType.TEXT, "issuer_ownership_type", null),
          new Column("instrumentType", FieldType.TEXT, "instrument_type", null),
          new Column("allotmentDate", FieldType.DATE, "allotment_date", null),
          new Column("redemptionDate", FieldType.DATE, "redemption_date", null),
          new Column("originalFaceValue", FieldType.DECIMAL, "original_face_value", null),
          new Column("collateralStatus", FieldType.TEXT, "collateral_status", null),
          new Column("assetCoverageBasis", FieldType.TEXT, "asset_coverage_basis", null),
          new Column(
              "assetCoverage", FieldType.PERCENT, "asset_coverage_value", "asset_coverage_unit"),
          new Column("couponRate", FieldType.PERCENT, "coupon_rate_value", "coupon_rate_unit"),
          new Column("couponType", FieldType.TEXT, "coupon_type", null),
          new Column("listingStatus", FieldType.TEXT, "listing_status", null),
          new Column("totalIssueSize", FieldType.DECIMAL, "total_issue_size", null),
          new Column("seniorityRepayment", FieldType.TEXT, "seniority_repayment", null));

  /** Locks in ISIN order, the same order {@link #INSERT_MISSING} takes, so it cannot deadlock. */
  static final String LOCK =
      """
      SELECT s.isin, s.coupon_rate_value, s.coupon_rate_source, s.coupon_rate_trade_date,
             s.details_request_count, s.details_requested_at,
             s.collateral_status IS NOT NULL AS has_collateral_status,
             EXISTS (SELECT 1 FROM securities_data.security_cash_flows c
                     WHERE c.isin = s.isin) AS has_cash_flows,
             EXISTS (SELECT 1 FROM securities_data.security_ratings r
                     WHERE r.isin = s.isin AND r.source_category = 'CURRENT')
               AS has_current_ratings
      FROM securities_data.securities s
      WHERE s.isin = ANY(?::text[])
      ORDER BY s.isin
      FOR UPDATE OF s
      """;

  static final String REPLACE_COUPON =
      """
      UPDATE securities_data.securities
      SET coupon_rate_value = ?, coupon_rate_unit = 'PERCENT', coupon_rate_source = 'BSE_BHAVCOPY',
          coupon_rate_trade_date = ?, field_sources = field_sources || ?::jsonb, updated_at = ?
      WHERE isin = ?
      """;

  static final String RECORD_DETAILS_REQUESTS =
      """
      UPDATE securities_data.securities
      SET details_request_count = details_request_count + 1, details_requested_at = ?
      WHERE isin = ANY(?::text[])
      """;

  static final String ADVANCE_COUPON_TRADE_DATE =
      "UPDATE securities_data.securities SET coupon_rate_trade_date = ? WHERE isin = ?";

  private static final String COUPON_RATE = "couponRate";

  static final String LOCK_VALUES =
      "SELECT "
          + COLUMNS.stream().map(Column::value).collect(Collectors.joining(", "))
          + " FROM securities_data.securities WHERE isin = ? FOR UPDATE";

  private static final Map<String, Column> BY_FIELD =
      COLUMNS.stream().collect(Collectors.toUnmodifiableMap(Column::field, Function.identity()));

  private final JdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcSecurityRepository(JdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Set<Isin> insertMissing(Collection<Isin> isins, Instant recordedAt) {
    if (isins.isEmpty()) {
      return Set.of();
    }
    String[] values = isins.stream().map(Isin::value).distinct().toArray(String[]::new);
    OffsetDateTime timestamp = recordedAt.atOffset(ZoneOffset.UTC);
    return jdbc.queryForList(INSERT_MISSING, String.class, timestamp, timestamp, values).stream()
        .map(Isin::new)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Map<String, SecurityValue> lockValues(Isin isin) {
    List<Map<String, SecurityValue>> rows =
        jdbc.query(LOCK_VALUES, (row, number) -> values(row), isin.value());
    if (rows.isEmpty()) {
      throw new IllegalStateException("No security " + isin);
    }
    return rows.getFirst();
  }

  @Override
  public void applyChanges(Isin isin, SecurityScalars changes, Instant changedAt) {
    if (changes.isEmpty()) {
      return;
    }
    List<String> assignments = new ArrayList<>();
    List<Object> arguments = new ArrayList<>();
    Map<String, Object> sources = new LinkedHashMap<>();
    changes
        .fields()
        .forEach(
            (field, value) -> {
              Column column = column(field);
              assignments.add(column.value() + " = ?");
              arguments.add(parameter(value.value()));
              if (column.unit() != null) {
                assignments.add(column.unit() + " = ?");
                arguments.add(Percent.UNIT);
              }
              if (field.equals(COUPON_RATE)) {
                assignments.add("coupon_rate_source = ?");
                arguments.add(CouponSource.NSDL.name());
                assignments.add("coupon_rate_trade_date = NULL");
              }
              sources.put(field, source(value.source()));
            });
    for (String field : changes.cleared()) {
      Column column = column(field);
      assignments.add(column.value() + " = NULL");
      if (column.unit() != null) {
        assignments.add(column.unit() + " = NULL");
      }
      if (field.equals(COUPON_RATE)) {
        assignments.add("coupon_rate_source = NULL");
        assignments.add("coupon_rate_trade_date = NULL");
      }
    }
    // jsonb - text[] removes the cleared fields' sources; || then sets the changed ones.
    assignments.add("field_sources = (field_sources - ?::text[]) || ?::jsonb");
    arguments.add(changes.cleared().toArray(String[]::new));
    arguments.add(CanonicalJson.of(sources));
    assignments.add("updated_at = ?");
    arguments.add(changedAt.atOffset(ZoneOffset.UTC));
    arguments.add(isin.value());
    String sql =
        "UPDATE securities_data.securities SET "
            + String.join(", ", assignments)
            + " WHERE isin = ?";
    if (jdbc.update(sql, arguments.toArray()) == 0) {
      throw new IllegalStateException("No security " + isin);
    }
  }

  @Override
  public Map<Isin, LockedSecurity> lock(Collection<Isin> isins) {
    if (isins.isEmpty()) {
      return Map.of();
    }
    Map<Isin, LockedSecurity> locked = new LinkedHashMap<>();
    jdbc.query(
        LOCK,
        row -> {
          BigDecimal rate = row.getBigDecimal("coupon_rate_value");
          String source = row.getString("coupon_rate_source");
          OffsetDateTime requestedAt = row.getObject("details_requested_at", OffsetDateTime.class);
          locked.put(
              new Isin(row.getString("isin")),
              new LockedSecurity(
                  new StoredCoupon(
                      rate == null ? null : Percent.of(rate),
                      source == null ? null : CouponSource.valueOf(source),
                      row.getObject("coupon_rate_trade_date", LocalDate.class)),
                  row.getInt("details_request_count"),
                  requestedAt == null ? null : requestedAt.toInstant(),
                  row.getBoolean("has_cash_flows"),
                  row.getBoolean("has_current_ratings"),
                  row.getBoolean("has_collateral_status")));
        },
        (Object) distinct(isins));
    return locked;
  }

  @Override
  public void recordDetailsRequests(Collection<Isin> isins, Instant requestedAt) {
    if (isins.isEmpty()) {
      return;
    }
    jdbc.update(RECORD_DETAILS_REQUESTS, requestedAt.atOffset(ZoneOffset.UTC), distinct(isins));
  }

  private static String[] distinct(Collection<Isin> isins) {
    return isins.stream().map(Isin::value).distinct().toArray(String[]::new);
  }

  @Override
  public void storeBhavcopyCoupons(
      List<ExchangeCoupon> replaced, List<ExchangeCoupon> advanced, Instant changedAt) {
    OffsetDateTime timestamp = changedAt.atOffset(ZoneOffset.UTC);
    if (!replaced.isEmpty()) {
      jdbc.batchUpdate(
          REPLACE_COUPON,
          replaced.stream()
              .map(
                  coupon ->
                      new Object[] {
                        coupon.rate().value(),
                        coupon.tradeDate().value(),
                        CanonicalJson.of(Map.of(COUPON_RATE, source(coupon.source()))),
                        timestamp,
                        coupon.isin().value()
                      })
              .toList());
    }
    if (!advanced.isEmpty()) {
      jdbc.batchUpdate(
          ADVANCE_COUPON_TRADE_DATE,
          advanced.stream()
              .map(coupon -> new Object[] {coupon.tradeDate().value(), coupon.isin().value()})
              .toList());
    }
  }

  private static Map<String, SecurityValue> values(ResultSet row) throws SQLException {
    Map<String, SecurityValue> values = new LinkedHashMap<>();
    for (Column column : COLUMNS) {
      SecurityValue value =
          switch (column.type()) {
            case TEXT -> text(row.getString(column.value()));
            case DATE -> date(row.getObject(column.value(), LocalDate.class));
            case DECIMAL -> decimal(row.getBigDecimal(column.value()));
            case PERCENT -> percentage(row.getBigDecimal(column.value()));
            case INTEGER -> throw new IllegalStateException("No security field is an integer");
          };
      if (value != null) {
        values.put(column.field(), value);
      }
    }
    return values;
  }

  private static @Nullable SecurityValue text(@Nullable String value) {
    return value == null ? null : new SecurityValue.Text(value);
  }

  private static @Nullable SecurityValue date(@Nullable LocalDate value) {
    return value == null ? null : new SecurityValue.Date(value);
  }

  private static @Nullable SecurityValue decimal(@Nullable BigDecimal value) {
    return value == null ? null : new SecurityValue.Decimal(value);
  }

  private static @Nullable SecurityValue percentage(@Nullable BigDecimal value) {
    return value == null ? null : new SecurityValue.Percentage(Percent.of(value));
  }

  private static Object parameter(SecurityValue value) {
    return switch (value) {
      case SecurityValue.Text text -> text.value();
      case SecurityValue.Date date -> date.value();
      case SecurityValue.Decimal decimal -> decimal.value();
      case SecurityValue.Percentage percentage -> percentage.value().value();
    };
  }

  private static Map<String, Object> source(SourceReference source) {
    return Map.of(
        "sourceRequestId", source.jobId().toString(),
        "sourceFile", source.sourceFile(),
        "sourceLocation", source.location());
  }

  private static Column column(String field) {
    Column column = BY_FIELD.get(field);
    if (column == null) {
      throw new IllegalArgumentException("A security has no field " + field);
    }
    return column;
  }

  /**
   * Where one security field is stored.
   *
   * @param field the internal field name
   * @param type the field's type
   * @param value the column holding the value
   * @param unit the column holding a percentage's unit, or {@code null}
   */
  record Column(String field, FieldType type, String value, @Nullable String unit) {}
}

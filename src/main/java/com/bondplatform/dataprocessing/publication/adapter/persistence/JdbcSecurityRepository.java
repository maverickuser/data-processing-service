package com.bondplatform.dataprocessing.publication.adapter.persistence;

import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.publication.application.SecurityRepository;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
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
 * the collection tables keep in columns (LLD section 17.5).
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
          new Column("listingStatus", FieldType.TEXT, "listing_status", null));

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
              sources.put(field, source(value.source()));
            });
    for (String field : changes.cleared()) {
      Column column = column(field);
      assignments.add(column.value() + " = NULL");
      if (column.unit() != null) {
        assignments.add(column.unit() + " = NULL");
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

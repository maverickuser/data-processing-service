package com.bondplatform.dataprocessing.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Test cases I-DB-01 (securities part), I-DB-02, I-DB-03, and I-DB-04. The expected shapes below
 * are the LLD section 22.1 definitions written out; a migration that drifts from them fails here.
 */
class SecuritiesSchemaIT extends PostgresIntegrationTest {

  private static final String SCHEMA = "securities_data";
  private static final String ISIN = "INE831R08076";
  private static final List<String> ORIGIN_COLUMNS =
      List.of(
          "source_request_id uuid NOT NULL",
          "source_file text NOT NULL",
          "source_location text NOT NULL");

  private SchemaInspector schema;

  @BeforeEach
  void createSecurity() {
    schema = new SchemaInspector(jdbc);
    jdbc.sql(
            "INSERT INTO securities_data.securities (isin, created_at, updated_at)"
                + " VALUES (?, now(), now())")
        .param(ISIN)
        .update();
  }

  // I-DB-01
  @Test
  void schemaHoldsExactlyTheSixBusinessTables() {
    assertThat(schema.tables(SCHEMA))
        .containsExactly(
            "securities",
            "security_cash_flows",
            "security_collateral_assets",
            "security_daily_market_summaries",
            "security_listings",
            "security_ratings");
  }

  // I-DB-01
  @Test
  void securitiesTableMatchesTheDesign() {
    assertThat(schema.columns(SCHEMA, "securities"))
        .containsExactly(
            "isin text NOT NULL",
            "issuer_name text",
            "issuer_ownership_type text",
            "instrument_type text",
            "allotment_date date",
            "redemption_date date",
            "original_face_value numeric",
            "collateral_status text",
            "asset_coverage_basis text",
            "asset_coverage_value numeric",
            "asset_coverage_unit text",
            "coupon_rate_value numeric",
            "coupon_rate_unit text",
            "coupon_type text",
            "listing_status text",
            "field_sources jsonb NOT NULL DEFAULT '{}'::jsonb",
            "created_at timestamp with time zone NOT NULL",
            "updated_at timestamp with time zone NOT NULL");
    assertThat(schema.indexes(SCHEMA, "securities"))
        .containsExactly(
            "CREATE UNIQUE INDEX securities_pkey ON securities_data.securities"
                + " USING btree (isin)");
  }

  // I-DB-01
  @Test
  void dailyMarketSummariesTableMatchesTheDesign() {
    assertThat(schema.columns(SCHEMA, "security_daily_market_summaries"))
        .containsExactly(
            "isin text NOT NULL",
            "trade_date date NOT NULL",
            "exchange_name text NOT NULL",
            "security_code text",
            "open_price numeric",
            "high_price numeric",
            "low_price numeric",
            "close_price numeric",
            "traded_volume numeric",
            "number_of_trades numeric",
            "turnover numeric",
            "face_value numeric",
            "source_request_id uuid NOT NULL",
            "source_file text NOT NULL",
            "source_location text NOT NULL",
            "created_at timestamp with time zone NOT NULL",
            "updated_at timestamp with time zone NOT NULL");
    assertThat(schema.indexes(SCHEMA, "security_daily_market_summaries"))
        .containsExactly(
            "CREATE INDEX security_daily_market_summaries_by_date"
                + " ON securities_data.security_daily_market_summaries"
                + " USING btree (trade_date, exchange_name, isin)",
            "CREATE UNIQUE INDEX security_daily_market_summaries_pkey"
                + " ON securities_data.security_daily_market_summaries"
                + " USING btree (isin, trade_date, exchange_name)");
  }

  // I-DB-01
  @Test
  void appendOnlyTablesMatchTheDesign() {
    assertThat(schema.columns(SCHEMA, "security_cash_flows"))
        .containsExactlyElementsOf(
            appendOnlyColumns(
                "event_type text",
                "record_date date",
                "due_date date",
                "amount_payable numeric",
                "payment_date date",
                "new_face_value numeric"));
    assertThat(schema.columns(SCHEMA, "security_listings"))
        .containsExactlyElementsOf(appendOnlyColumns("exchange_name text", "listing_date date"));
    assertThat(schema.columns(SCHEMA, "security_ratings"))
        .containsExactlyElementsOf(
            appendOnlyColumns(
                "source_category text NOT NULL",
                "rating_agency_name text",
                "rating text",
                "outlook text",
                "rating_action text",
                "rating_date date",
                "rating_change_date date",
                "verification_date date"));
    assertThat(schema.columns(SCHEMA, "security_collateral_assets"))
        .containsExactlyElementsOf(
            appendOnlyColumns("asset_type text", "collateral_description text", "remarks text"));
  }

  // I-DB-01, I-DB-02
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          security_cash_flows|isin, event_type, record_date, due_date, amount_payable, payment_date, new_face_value
          security_listings|isin, exchange_name, listing_date
          security_ratings|isin, source_category, rating_agency_name, rating, outlook, rating_action, rating_date, rating_change_date, verification_date
          security_collateral_assets|isin, asset_type, collateral_description, remarks
          """)
  void appendOnlyTableHasOneUniqueIndexTreatingEmptyValuesAsEqual(String table, String columns) {
    assertThat(schema.indexes(SCHEMA, table))
        .containsExactly(
            "CREATE UNIQUE INDEX %s_distinct_entry ON securities_data.%s USING btree (%s)"
                    .formatted(table, table, columns)
                + " NULLS NOT DISTINCT",
            "CREATE UNIQUE INDEX %s_pkey ON securities_data.%s USING btree (id)"
                .formatted(table, table));
  }

  // I-DB-04
  @Test
  void everyChildTableRefusesToLoseItsSecurity() {
    assertThat(schema.foreignKeys(SCHEMA))
        .containsExactly(
            "security_cash_flows.isin -> securities.isin ON DELETE RESTRICT",
            "security_collateral_assets.isin -> securities.isin ON DELETE RESTRICT",
            "security_daily_market_summaries.isin -> securities.isin ON DELETE RESTRICT",
            "security_listings.isin -> securities.isin ON DELETE RESTRICT",
            "security_ratings.isin -> securities.isin ON DELETE RESTRICT");
  }

  // I-DB-02
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          security_cash_flows|event_type|'Interest'
          security_listings|exchange_name|'NSE'
          security_collateral_assets|asset_type|'Book Debts'
          """)
  void entriesDifferingOnlyByEmptyFieldsAreOneEntry(String table, String column, String value) {
    String insert =
        "INSERT INTO securities_data.%s (id, isin, %s, %s) VALUES (?, ?, %s, %s)"
                .formatted(table, column, originColumns(), value, originValues())
            + " ON CONFLICT DO NOTHING";

    int first = jdbc.sql(insert).params(UUID.randomUUID(), ISIN).update();
    int second = jdbc.sql(insert).params(UUID.randomUUID(), ISIN).update();

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(count(table)).isEqualTo(1);
  }

  // I-DB-03
  @Test
  void numericallyEqualAmountsAreTheSameEntry() {
    String insert =
        "INSERT INTO securities_data.security_cash_flows (id, isin, amount_payable, %s)"
                .formatted(originColumns())
            + " VALUES (?, ?, ?::numeric, %s) ON CONFLICT DO NOTHING".formatted(originValues());

    jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "89400").update();
    int sameValue = jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "89400.00").update();
    int otherValue = jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "89400.01").update();

    assertThat(sameValue).isZero();
    assertThat(otherValue).isEqualTo(1);
    assertThat(count("security_cash_flows")).isEqualTo(2);
  }

  @Test
  void numericColumnsKeepEveryDigit() {
    String exact = "123456789012345678901234567890.123456789012345678901234567890";
    jdbc.sql(
            "UPDATE securities_data.securities SET original_face_value = ?::numeric WHERE isin = ?")
        .params(exact, ISIN)
        .update();

    assertThat(
            jdbc.sql("SELECT original_face_value::text FROM securities_data.securities")
                .query(String.class)
                .single())
        .isEqualTo(exact);
  }

  @Test
  void ratingsWithDifferentSourceCategoriesAreDifferentEntries() {
    String insert =
        "INSERT INTO securities_data.security_ratings (id, isin, source_category, rating, %s)"
                .formatted(originColumns())
            + " VALUES (?, ?, ?, 'AAA', %s) ON CONFLICT DO NOTHING".formatted(originValues());

    jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "CURRENT").update();
    jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "EARLIER").update();
    int repeated = jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "CURRENT").update();

    assertThat(repeated).isZero();
    assertThat(count("security_ratings")).isEqualTo(2);
    assertThatThrownBy(() -> jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "LATEST").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // I-DB-04
  @Test
  void securityWithChildRowsCannotBeDeleted() {
    jdbc.sql(
            "INSERT INTO securities_data.security_listings (id, isin, exchange_name, %s)"
                    .formatted(originColumns())
                + " VALUES (?, ?, 'NSE', %s)".formatted(originValues()))
        .params(UUID.randomUUID(), ISIN)
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM securities_data.securities WHERE isin = ?")
                    .param(ISIN)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "open_price",
        "high_price",
        "low_price",
        "close_price",
        "traded_volume",
        "number_of_trades",
        "turnover",
        "face_value"
      })
  void summaryNumbersCannotBeNegative(String column) {
    assertThat(insertSummary(column, "0")).isEqualTo(1);
    assertThatThrownBy(() -> insertSummary(column, "-0.01"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"traded_volume", "number_of_trades"})
  void summaryCountsMustBeWholeNumbers(String column) {
    assertThat(insertSummary(column, "14.00")).isEqualTo(1);
    assertThatThrownBy(() -> insertSummary(column, "14.5"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          original_face_value|0|-1
          coupon_rate_unit|'PERCENT'|'FRACTION'
          asset_coverage_unit|'PERCENT'|'RATIO'
          """)
  void securityChecksRejectNegativeFaceValueAndUnknownUnits(
      String column, String allowed, String refused) {
    String update = "UPDATE securities_data.securities SET %s = %s WHERE isin = ?";

    assertThat(jdbc.sql(update.formatted(column, allowed)).param(ISIN).update()).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.sql(update.formatted(column, refused)).param(ISIN).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private int insertSummary(String column, String value) {
    return jdbc.sql(
            "INSERT INTO securities_data.security_daily_market_summaries"
                + " (isin, trade_date, exchange_name, %s, %s, created_at, updated_at)"
                    .formatted(column, "source_request_id, source_file, source_location")
                + " VALUES (?, DATE '2026-01-01', ?, ?::numeric, ?, 'BSE_fgroup01012026.csv',"
                + " '2', now(), now())")
        .params(ISIN, "X" + UUID.randomUUID(), value, UUID.randomUUID())
        .update();
  }

  private static List<String> appendOnlyColumns(String... businessColumns) {
    List<String> columns = new ArrayList<>(List.of("id uuid NOT NULL", "isin text NOT NULL"));
    columns.addAll(List.of(businessColumns));
    columns.addAll(ORIGIN_COLUMNS);
    columns.add("first_recorded_at timestamp with time zone NOT NULL");
    return columns;
  }

  private static String originColumns() {
    return "source_request_id, source_file, source_location, first_recorded_at";
  }

  private static String originValues() {
    return "'%s', 'INE831R08076_coupon-details.json', '$.x[0]', now()".formatted(UUID.randomUUID());
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM securities_data." + table).query(Long.class).single();
  }
}

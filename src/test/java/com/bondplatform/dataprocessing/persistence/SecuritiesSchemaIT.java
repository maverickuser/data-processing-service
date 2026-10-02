package com.bondplatform.dataprocessing.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test cases I-DB-01 (securities part), I-DB-02, I-DB-03, and I-DB-04. */
class SecuritiesSchemaIT extends PostgresIntegrationTest {

  private static final String ISIN = "INE831R08076";
  private static final String LINEAGE = "'%s', 'INE831R08076_coupon-details.json', '$.x[0]', now()";

  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void emptyTables() {
    jdbc.sql(
            """
            TRUNCATE securities_data.security_cash_flows, securities_data.security_listings,
              securities_data.security_ratings, securities_data.security_collateral_assets,
              securities_data.security_daily_market_summaries, securities_data.securities
            """)
        .update();
    jdbc.sql(
            "INSERT INTO securities_data.securities (isin, created_at, updated_at)"
                + " VALUES (?, now(), now())")
        .param(ISIN)
        .update();
  }

  // I-DB-01
  @Test
  void tablesHaveExactlyTheColumnsOfTheDesign() {
    assertThat(columnsOf("securities"))
        .containsExactly(
            "isin text NO",
            "issuer_name text YES",
            "issuer_ownership_type text YES",
            "instrument_type text YES",
            "allotment_date date YES",
            "redemption_date date YES",
            "original_face_value numeric YES",
            "collateral_status text YES",
            "asset_coverage_basis text YES",
            "asset_coverage_value numeric YES",
            "asset_coverage_unit text YES",
            "coupon_rate_value numeric YES",
            "coupon_rate_unit text YES",
            "coupon_type text YES",
            "listing_status text YES",
            "field_sources jsonb NO",
            "created_at timestamp with time zone NO",
            "updated_at timestamp with time zone NO");
    assertThat(columnsOf("security_daily_market_summaries"))
        .containsExactly(
            "isin text NO",
            "trade_date date NO",
            "exchange_name text NO",
            "security_code text YES",
            "open_price numeric YES",
            "high_price numeric YES",
            "low_price numeric YES",
            "close_price numeric YES",
            "traded_volume numeric YES",
            "number_of_trades numeric YES",
            "turnover numeric YES",
            "face_value numeric YES",
            "source_request_id uuid NO",
            "source_file text NO",
            "source_location text NO",
            "created_at timestamp with time zone NO",
            "updated_at timestamp with time zone NO");
    assertThat(columnsOf("security_cash_flows"))
        .containsExactly(
            "id uuid NO",
            "isin text NO",
            "event_type text YES",
            "record_date date YES",
            "due_date date YES",
            "amount_payable numeric YES",
            "payment_date date YES",
            "new_face_value numeric YES",
            "source_request_id uuid NO",
            "source_file text NO",
            "source_location text NO",
            "first_recorded_at timestamp with time zone NO");
    assertThat(columnsOf("security_listings"))
        .containsExactly(
            "id uuid NO",
            "isin text NO",
            "exchange_name text YES",
            "listing_date date YES",
            "source_request_id uuid NO",
            "source_file text NO",
            "source_location text NO",
            "first_recorded_at timestamp with time zone NO");
    assertThat(columnsOf("security_ratings"))
        .containsExactly(
            "id uuid NO",
            "isin text NO",
            "source_category text NO",
            "rating_agency_name text YES",
            "rating text YES",
            "outlook text YES",
            "rating_action text YES",
            "rating_date date YES",
            "rating_change_date date YES",
            "verification_date date YES",
            "source_request_id uuid NO",
            "source_file text NO",
            "source_location text NO",
            "first_recorded_at timestamp with time zone NO");
    assertThat(columnsOf("security_collateral_assets"))
        .containsExactly(
            "id uuid NO",
            "isin text NO",
            "asset_type text YES",
            "collateral_description text YES",
            "remarks text YES",
            "source_request_id uuid NO",
            "source_file text NO",
            "source_location text NO",
            "first_recorded_at timestamp with time zone NO");
  }

  @Test
  void schemaHoldsExactlyTheSixBusinessTables() {
    List<String> tables =
        jdbc.sql(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'securities_data' ORDER BY table_name")
            .query(String.class)
            .list();

    assertThat(tables)
        .containsExactly(
            "securities",
            "security_cash_flows",
            "security_collateral_assets",
            "security_daily_market_summaries",
            "security_listings",
            "security_ratings");
  }

  // I-DB-02
  @Test
  void entriesDifferingOnlyByAnEmptyFieldAreOneEntry() {
    String insert =
        "INSERT INTO securities_data.security_cash_flows"
            + " (id, isin, event_type, due_date, amount_payable, source_request_id, source_file,"
            + " source_location, first_recorded_at)"
            + " VALUES (?, ?, 'Interest', DATE '2027-06-08', 89400, "
            + LINEAGE.formatted(UUID.randomUUID())
            + ") ON CONFLICT DO NOTHING";

    int first = jdbc.sql(insert).params(UUID.randomUUID(), ISIN).update();
    int second = jdbc.sql(insert).params(UUID.randomUUID(), ISIN).update();

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(count("security_cash_flows")).isEqualTo(1);
  }

  // I-DB-03
  @Test
  void numericallyEqualAmountsAreTheSameEntry() {
    String insert =
        "INSERT INTO securities_data.security_cash_flows"
            + " (id, isin, event_type, amount_payable, source_request_id, source_file,"
            + " source_location, first_recorded_at)"
            + " VALUES (?, ?, 'Interest', ?::numeric, "
            + LINEAGE.formatted(UUID.randomUUID())
            + ") ON CONFLICT DO NOTHING";

    jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "89400").update();
    int second = jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "89400.00").update();
    int different = jdbc.sql(insert).params(UUID.randomUUID(), ISIN, "89400.01").update();

    assertThat(second).isZero();
    assertThat(different).isEqualTo(1);
    assertThat(count("security_cash_flows")).isEqualTo(2);
  }

  @Test
  void ratingsWithDifferentSourceCategoriesAreDifferentEntries() {
    String insert =
        "INSERT INTO securities_data.security_ratings"
            + " (id, isin, source_category, rating_agency_name, rating, source_request_id,"
            + " source_file, source_location, first_recorded_at)"
            + " VALUES (?, ?, ?, 'INDIA RATING', 'AAA', "
            + LINEAGE.formatted(UUID.randomUUID())
            + ") ON CONFLICT DO NOTHING";

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
            "INSERT INTO securities_data.security_listings (id, isin, exchange_name,"
                + " source_request_id, source_file, source_location, first_recorded_at)"
                + " VALUES (?, ?, 'NSE', "
                + LINEAGE.formatted(UUID.randomUUID())
                + ")")
        .params(UUID.randomUUID(), ISIN)
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM securities_data.securities WHERE isin = ?")
                    .param(ISIN)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void summaryRejectsNegativeAndFractionalCounts() {
    String insert =
        "INSERT INTO securities_data.security_daily_market_summaries"
            + " (isin, trade_date, exchange_name, traded_volume, open_price, source_request_id,"
            + " source_file, source_location, created_at, updated_at)"
            + " VALUES (?, DATE '2026-01-01', ?, ?::numeric, ?::numeric, ?,"
            + " 'BSE_fgroup01012026.csv', '2', now(), now())";

    assertThat(jdbc.sql(insert).params(ISIN, "BSE", "14", "114200.00", UUID.randomUUID()).update())
        .isEqualTo(1);
    assertThatThrownBy(
            () -> jdbc.sql(insert).params(ISIN, "NSE", "14.5", "1", UUID.randomUUID()).update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.sql(insert).params(ISIN, "NSE", "14", "-1", UUID.randomUUID()).update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.sql(insert).params(ISIN, "BSE", "15", "1", UUID.randomUUID()).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private List<String> columnsOf(String table) {
    return jdbc
        .sql(
            "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
                + " WHERE table_schema = 'securities_data' AND table_name = ?"
                + " ORDER BY ordinal_position")
        .param(table)
        .query()
        .listOfRows()
        .stream()
        .map(SecuritiesSchemaIT::describe)
        .toList();
  }

  private static String describe(Map<String, Object> column) {
    return column.get("column_name")
        + " "
        + column.get("data_type")
        + " "
        + column.get("is_nullable");
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM securities_data." + table).query(Long.class).single();
  }
}

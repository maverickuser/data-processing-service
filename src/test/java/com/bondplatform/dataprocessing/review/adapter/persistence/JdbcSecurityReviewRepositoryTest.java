package com.bondplatform.dataprocessing.review.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityMetrics;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcSecurityReviewRepositoryTest {

  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final OffsetDateTime AT =
      OffsetDateTime.of(2026, 9, 27, 14, 31, 2, 0, ZoneOffset.UTC);
  private static final Instant INSTANT = AT.toInstant();
  private static final LocalDate DAY = LocalDate.of(2027, 6, 8);

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcSecurityReviewRepository repository = new JdbcSecurityReviewRepository(jdbc);
  private final ResultSet row = mock(ResultSet.class);

  @Test
  void isinQueryBindsTypedPositionAndOrdersByIsin() {
    ArgumentCaptor<MapSqlParameterSource> parameters =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    when(jdbc.queryForList(
            eq(JdbcSecurityReviewRepository.ISINS), parameters.capture(), eq(String.class)))
        .thenReturn(List.of("INE831R08076"));

    assertThat(repository.isins("INE0KH208019", 51)).containsExactly("INE831R08076");
    repository.isins(null, 51);

    MapSqlParameterSource after = parameters.getAllValues().get(0);
    assertThat(after.getValue("after")).isEqualTo("INE0KH208019");
    assertThat(after.getValue("limit")).isEqualTo(51);
    MapSqlParameterSource first = parameters.getAllValues().get(1);
    assertThat(first.getValue("after")).isNull();
    assertThat(first.getSqlType("after")).isEqualTo(Types.VARCHAR);
    assertThat(JdbcSecurityReviewRepository.ISINS).contains("ORDER BY isin");
  }

  @Test
  void securityRowMapsEveryShownColumn() throws SQLException {
    when(row.getString("isin")).thenReturn(ISIN.value());
    when(row.getString("issuer_name")).thenReturn("ABHFL");
    when(row.getString("issuer_ownership_type")).thenReturn("Non PSU");
    when(row.getString("instrument_type")).thenReturn("Debentures");
    when(row.getObject("allotment_date", LocalDate.class)).thenReturn(LocalDate.of(2019, 6, 10));
    when(row.getObject("redemption_date", LocalDate.class)).thenReturn(LocalDate.of(2029, 6, 8));
    when(row.getBigDecimal("original_face_value")).thenReturn(new BigDecimal("1000000"));
    when(row.getBigDecimal("coupon_rate_value")).thenReturn(new BigDecimal("8.94"));
    when(row.getString("coupon_type")).thenReturn("Simple");
    when(row.getString("listing_status")).thenReturn("Listed");
    when(row.getString("collateral_status")).thenReturn("Secured");
    when(row.getString("asset_coverage_basis")).thenReturn("Book Debts");
    when(row.getBigDecimal("asset_coverage_value")).thenReturn(new BigDecimal("100"));
    when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(AT);
    when(row.getObject("updated_at", OffsetDateTime.class)).thenReturn(AT);

    SecurityView.Scalars scalars =
        mapped(
            SecurityView.Scalars.class, JdbcSecurityReviewRepository.FIND, repo -> repo.find(ISIN));

    assertThat(scalars)
        .isEqualTo(
            new SecurityView.Scalars(
                ISIN.value(),
                "ABHFL",
                "Non PSU",
                "Debentures",
                LocalDate.of(2019, 6, 10),
                LocalDate.of(2029, 6, 8),
                new BigDecimal("1000000"),
                new BigDecimal("8.94"),
                "Simple",
                "Listed",
                "Secured",
                "Book Debts",
                new BigDecimal("100"),
                INSTANT,
                INSTANT));
    assertThat(JdbcSecurityReviewRepository.FIND).doesNotContain("field_sources");
  }

  @Test
  void unknownIsinIsEmpty() {
    assertThat(repository.find(ISIN)).isEmpty();
  }

  @Test
  void ratingRowKnowsWhetherItIsCurrent() throws SQLException {
    when(row.getString("source_category")).thenReturn("CURRENT", "EARLIER");
    when(row.getString("rating_agency_name")).thenReturn("ICRA");
    when(row.getString("rating")).thenReturn("AAA");
    when(row.getString("outlook")).thenReturn("Stable");
    when(row.getString("rating_action")).thenReturn("Reaffirm");
    when(row.getObject("rating_date", LocalDate.class)).thenReturn(DAY);
    when(row.getObject("first_recorded_at", OffsetDateTime.class)).thenReturn(AT);

    RatingObservation current =
        mapped(
            RatingObservation.class,
            JdbcSecurityReviewRepository.RATINGS,
            repo -> repo.ratings(ISIN));
    RatingObservation earlier =
        mapped(
            RatingObservation.class,
            JdbcSecurityReviewRepository.RATINGS,
            repo -> repo.ratings(ISIN));

    assertThat(current)
        .isEqualTo(
            new RatingObservation(
                true, "ICRA", "AAA", "Stable", "Reaffirm", DAY, null, null, INSTANT));
    assertThat(earlier.current()).isFalse();
  }

  @Test
  void collectionRowsMapTheirColumns() throws SQLException {
    when(row.getString("asset_type")).thenReturn("Book Debts");
    when(row.getString("exchange_name")).thenReturn("NSE");
    when(row.getObject("listing_date", LocalDate.class)).thenReturn(DAY);
    when(row.getString("event_type")).thenReturn("Interest");
    when(row.getString("source_category")).thenReturn("SCHEDULE");
    when(row.getObject("due_date", LocalDate.class)).thenReturn(DAY);
    when(row.getBigDecimal("amount_payable")).thenReturn(new BigDecimal("89400.00"));
    when(row.getObject("first_recorded_at", OffsetDateTime.class)).thenReturn(AT);

    SecurityView.CollateralAsset asset =
        mapped(
            SecurityView.CollateralAsset.class,
            JdbcSecurityReviewRepository.COLLATERAL_ASSETS,
            repo -> repo.collateralAssets(ISIN));
    SecurityView.Listing listing =
        mapped(
            SecurityView.Listing.class,
            JdbcSecurityReviewRepository.LISTINGS,
            repo -> repo.listings(ISIN));
    SecurityView.CashFlow cashFlow =
        mapped(
            SecurityView.CashFlow.class,
            JdbcSecurityReviewRepository.CASH_FLOWS,
            repo -> repo.cashFlows(ISIN));

    assertThat(asset)
        .isEqualTo(new SecurityView.CollateralAsset("Book Debts", null, null, INSTANT));
    assertThat(listing).isEqualTo(new SecurityView.Listing("NSE", DAY, INSTANT));
    assertThat(cashFlow)
        .isEqualTo(
            new SecurityView.CashFlow(
                "SCHEDULE",
                "Interest",
                null,
                DAY,
                new BigDecimal("89400.00"),
                null,
                null,
                INSTANT));
  }

  /** Runs a read, then maps the row with the mapper it passed for that query. */
  @SuppressWarnings("unchecked")
  private <T> T mapped(Class<T> type, String sql, Consumer<JdbcSecurityReviewRepository> read)
      throws SQLException {
    ArgumentCaptor<RowMapper<T>> mapper = ArgumentCaptor.forClass(RowMapper.class);
    when(jdbc.query(eq(sql), any(SqlParameterSource.class), mapper.capture()))
        .thenReturn(List.of());
    read.accept(repository);
    return type.cast(Objects.requireNonNull(mapper.getValue().mapRow(row, 0)));
  }

  // U-REV-10
  @Test
  @SuppressWarnings("unchecked")
  void metricsAreReadInOneStatementAndMappedByColumn() throws SQLException {
    when(row.getLong("securities")).thenReturn(6L);
    when(row.getLong("without_details")).thenReturn(1L);
    when(row.getLong("without_cash_flows")).thenReturn(2L);
    when(row.getLong("without_current_ratings")).thenReturn(3L);
    when(row.getLong("without_coupon_rate")).thenReturn(4L);
    when(row.getLong("without_collateral_status")).thenReturn(5L);
    when(jdbc.queryForObject(
            eq(JdbcSecurityReviewRepository.METRICS), eq(Map.of()), any(RowMapper.class)))
        .thenAnswer(invocation -> invocation.<RowMapper<?>>getArgument(2).mapRow(row, 0));

    assertThat(repository.metrics()).isEqualTo(new SecurityMetrics(6, 1, 2, 3, 4, 5));
    assertThat(JdbcSecurityReviewRepository.METRICS)
        .contains("r.source_category = 'CURRENT'")
        .contains("FROM securities_data.securities s");
  }
}

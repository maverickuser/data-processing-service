package com.bondplatform.dataprocessing.review.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
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
                "Interest", null, DAY, new BigDecimal("89400.00"), null, null, INSTANT));
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
}

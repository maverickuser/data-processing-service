package com.bondplatform.dataprocessing.review.adapter.persistence;

import com.bondplatform.dataprocessing.review.application.SecurityReviewRepository;
import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * Reads securities and their recorded collections from {@code securities_data}. Only the columns
 * the API shows are selected: never field sources or source references.
 */
@Repository
public class JdbcSecurityReviewRepository implements SecurityReviewRepository {

  static final String FIND =
      """
      SELECT isin, issuer_name, issuer_ownership_type, instrument_type, allotment_date,
             redemption_date, original_face_value, coupon_rate_value, coupon_type, listing_status,
             collateral_status, asset_coverage_basis, asset_coverage_value, created_at, updated_at
      FROM securities_data.securities
      WHERE isin = :isin
      """;

  static final String COLLATERAL_ASSETS =
      """
      SELECT asset_type, collateral_description, remarks, first_recorded_at
      FROM securities_data.security_collateral_assets
      WHERE isin = :isin
      ORDER BY first_recorded_at, id
      """;

  static final String RATINGS =
      """
      SELECT source_category, rating_agency_name, rating, outlook, rating_action, rating_date,
             rating_change_date, verification_date, first_recorded_at
      FROM securities_data.security_ratings
      WHERE isin = :isin
      ORDER BY first_recorded_at, id
      """;

  static final String LISTINGS =
      """
      SELECT exchange_name, listing_date, first_recorded_at
      FROM securities_data.security_listings
      WHERE isin = :isin
      ORDER BY listing_date NULLS LAST, first_recorded_at, id
      """;

  static final String CASH_FLOWS =
      """
      SELECT event_type, record_date, due_date, amount_payable, payment_date, new_face_value,
             first_recorded_at
      FROM securities_data.security_cash_flows
      WHERE isin = :isin
      ORDER BY due_date NULLS LAST, first_recorded_at, id
      """;

  private final NamedParameterJdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcSecurityReviewRepository(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SecurityView.Scalars> find(Isin isin) {
    return jdbc.query(FIND, byIsin(isin), JdbcSecurityReviewRepository::scalars).stream()
        .findFirst();
  }

  @Override
  public List<SecurityView.CollateralAsset> collateralAssets(Isin isin) {
    return jdbc.query(
        COLLATERAL_ASSETS,
        byIsin(isin),
        (row, n) ->
            new SecurityView.CollateralAsset(
                row.getString("asset_type"),
                row.getString("collateral_description"),
                row.getString("remarks"),
                instant(row, "first_recorded_at")));
  }

  @Override
  public List<RatingObservation> ratings(Isin isin) {
    return jdbc.query(
        RATINGS,
        byIsin(isin),
        (row, n) ->
            new RatingObservation(
                "CURRENT".equals(row.getString("source_category")),
                row.getString("rating_agency_name"),
                row.getString("rating"),
                row.getString("outlook"),
                row.getString("rating_action"),
                date(row, "rating_date"),
                date(row, "rating_change_date"),
                date(row, "verification_date"),
                instant(row, "first_recorded_at")));
  }

  @Override
  public List<SecurityView.Listing> listings(Isin isin) {
    return jdbc.query(
        LISTINGS,
        byIsin(isin),
        (row, n) ->
            new SecurityView.Listing(
                row.getString("exchange_name"),
                date(row, "listing_date"),
                instant(row, "first_recorded_at")));
  }

  @Override
  public List<SecurityView.CashFlow> cashFlows(Isin isin) {
    return jdbc.query(
        CASH_FLOWS,
        byIsin(isin),
        (row, n) ->
            new SecurityView.CashFlow(
                row.getString("event_type"),
                date(row, "record_date"),
                date(row, "due_date"),
                row.getBigDecimal("amount_payable"),
                date(row, "payment_date"),
                row.getBigDecimal("new_face_value"),
                instant(row, "first_recorded_at")));
  }

  private static MapSqlParameterSource byIsin(Isin isin) {
    return new MapSqlParameterSource("isin", isin.value());
  }

  private static SecurityView.Scalars scalars(ResultSet row, int rowNumber) throws SQLException {
    return new SecurityView.Scalars(
        row.getString("isin"),
        row.getString("issuer_name"),
        row.getString("issuer_ownership_type"),
        row.getString("instrument_type"),
        date(row, "allotment_date"),
        date(row, "redemption_date"),
        row.getBigDecimal("original_face_value"),
        row.getBigDecimal("coupon_rate_value"),
        row.getString("coupon_type"),
        row.getString("listing_status"),
        row.getString("collateral_status"),
        row.getString("asset_coverage_basis"),
        row.getBigDecimal("asset_coverage_value"),
        instant(row, "created_at"),
        instant(row, "updated_at"));
  }

  private static @Nullable LocalDate date(ResultSet row, String column) throws SQLException {
    return row.getObject(column, LocalDate.class);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }
}

package com.bondplatform.dataprocessing.publication.adapter.persistence;

import com.bondplatform.dataprocessing.publication.application.SecurityCollectionRepository;
import com.bondplatform.dataprocessing.publication.domain.CashFlow;
import com.bondplatform.dataprocessing.publication.domain.CollateralAsset;
import com.bondplatform.dataprocessing.publication.domain.Listing;
import com.bondplatform.dataprocessing.publication.domain.Rating;
import com.bondplatform.dataprocessing.publication.domain.SecurityEntry;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Stores the append-only collections in the four {@code securities_data.security_*} collection
 * tables.
 *
 * <p>Each table has a unique index over the security and every business column that treats empty
 * values as equal, so "append only if new" is one {@code INSERT ... ON CONFLICT DO NOTHING}.
 */
@Repository
public class JdbcSecurityCollectionRepository implements SecurityCollectionRepository {

  /** Rows per database round trip; the caller's transaction spans all of them. */
  static final int BATCH_SIZE = 500;

  /** Declared before the statements built from it; static fields initialize in source order. */
  @SuppressWarnings("InlineFormatString") // one template, four statements
  private static final String INSERT_TEMPLATE =
      """
      INSERT INTO securities_data.%s
        (id, isin, %s, source_request_id, source_file, source_location, first_recorded_at)
      VALUES
        (:id, :isin, %s, :sourceRequestId, :sourceFile, :sourceLocation, :recordedAt)
      ON CONFLICT DO NOTHING
      """;

  static final String INSERT_CASH_FLOW =
      insertInto(
          "security_cash_flows",
          "event_type, record_date, due_date, amount_payable, payment_date, new_face_value",
          ":eventType, :recordDate, :dueDate, :amountPayable, :paymentDate, :newFaceValue");

  static final String INSERT_LISTING =
      insertInto("security_listings", "exchange_name, listing_date", ":exchangeName, :listingDate");

  static final String INSERT_RATING =
      insertInto(
          "security_ratings",
          "source_category, rating_agency_name, rating, outlook, rating_action, rating_date,"
              + " rating_change_date, verification_date",
          ":sourceCategory, :ratingAgencyName, :rating, :outlook, :ratingAction, :ratingDate,"
              + " :ratingChangeDate, :verificationDate");

  static final String INSERT_COLLATERAL_ASSET =
      insertInto(
          "security_collateral_assets",
          "asset_type, collateral_description, remarks",
          ":assetType, :collateralDescription, :remarks");

  private final NamedParameterJdbcOperations jdbc;
  private final IdSupplier idSupplier;

  /** Creates the repository; new entries take their identifiers from the supplier. */
  public JdbcSecurityCollectionRepository(
      NamedParameterJdbcOperations jdbc, IdSupplier idSupplier) {
    this.jdbc = jdbc;
    this.idSupplier = idSupplier;
  }

  @Override
  public int appendCashFlows(Collection<SecurityEntry<CashFlow>> entries, Instant recordedAt) {
    return append(
        INSERT_CASH_FLOW,
        entries,
        recordedAt,
        (cashFlow, parameters) ->
            parameters
                .addValue("eventType", cashFlow.eventType())
                .addValue("recordDate", cashFlow.recordDate())
                .addValue("dueDate", cashFlow.dueDate())
                .addValue("amountPayable", cashFlow.amountPayable())
                .addValue("paymentDate", cashFlow.paymentDate())
                .addValue("newFaceValue", cashFlow.newFaceValue()));
  }

  @Override
  public int appendListings(Collection<SecurityEntry<Listing>> entries, Instant recordedAt) {
    return append(
        INSERT_LISTING,
        entries,
        recordedAt,
        (listing, parameters) ->
            parameters
                .addValue("exchangeName", listing.exchangeName())
                .addValue("listingDate", listing.listingDate()));
  }

  @Override
  public int appendRatings(Collection<SecurityEntry<Rating>> entries, Instant recordedAt) {
    return append(
        INSERT_RATING,
        entries,
        recordedAt,
        (rating, parameters) ->
            parameters
                .addValue("sourceCategory", rating.sourceCategory().name())
                .addValue("ratingAgencyName", rating.ratingAgencyName())
                .addValue("rating", rating.rating())
                .addValue("outlook", rating.outlook())
                .addValue("ratingAction", rating.ratingAction())
                .addValue("ratingDate", rating.ratingDate())
                .addValue("ratingChangeDate", rating.ratingChangeDate())
                .addValue("verificationDate", rating.verificationDate()));
  }

  @Override
  public int appendCollateralAssets(
      Collection<SecurityEntry<CollateralAsset>> entries, Instant recordedAt) {
    return append(
        INSERT_COLLATERAL_ASSET,
        entries,
        recordedAt,
        (asset, parameters) ->
            parameters
                .addValue("assetType", asset.assetType())
                .addValue("collateralDescription", asset.collateralDescription())
                .addValue("remarks", asset.remarks()));
  }

  /** Inserts the entries in batches and returns how many rows the database actually added. */
  private <T> int append(
      String insert,
      Collection<SecurityEntry<T>> entries,
      Instant recordedAt,
      BiConsumer<T, MapSqlParameterSource> bindValue) {
    OffsetDateTime timestamp = recordedAt.atOffset(ZoneOffset.UTC);
    List<SecurityEntry<T>> ordered = List.copyOf(entries);
    int appended = 0;
    for (int start = 0; start < ordered.size(); start += BATCH_SIZE) {
      SqlParameterSource[] batch =
          ordered.subList(start, Math.min(start + BATCH_SIZE, ordered.size())).stream()
              .map(entry -> parametersOf(entry, timestamp, bindValue))
              .toArray(SqlParameterSource[]::new);
      appended += Arrays.stream(jdbc.batchUpdate(insert, batch)).filter(rows -> rows > 0).sum();
    }
    return appended;
  }

  private <T> SqlParameterSource parametersOf(
      SecurityEntry<T> entry,
      OffsetDateTime recordedAt,
      BiConsumer<T, MapSqlParameterSource> bindValue) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", idSupplier.nextId())
            .addValue("isin", entry.isin().value())
            .addValue("sourceRequestId", entry.source().jobId().value())
            .addValue("sourceFile", entry.source().sourceFile())
            .addValue("sourceLocation", entry.source().location())
            .addValue("recordedAt", recordedAt);
    bindValue.accept(entry.value(), parameters);
    return parameters;
  }

  private static String insertInto(String table, String businessColumns, String businessValues) {
    return INSERT_TEMPLATE.formatted(table, businessColumns, businessValues);
  }
}

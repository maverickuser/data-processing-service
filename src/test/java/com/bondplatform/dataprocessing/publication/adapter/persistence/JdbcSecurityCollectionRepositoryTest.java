package com.bondplatform.dataprocessing.publication.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.publication.domain.CashFlow;
import com.bondplatform.dataprocessing.publication.domain.CollateralAsset;
import com.bondplatform.dataprocessing.publication.domain.Listing;
import com.bondplatform.dataprocessing.publication.domain.Rating;
import com.bondplatform.dataprocessing.publication.domain.Rating.SourceCategory;
import com.bondplatform.dataprocessing.publication.domain.SecurityEntry;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the repository sends to JDBC. Whether the SQL does what it should is proven against
 * PostgreSQL in {@code SecurityCollectionRepositoryIT}.
 */
class JdbcSecurityCollectionRepositoryTest {

  private static final Instant RECORDED_AT = Instant.parse("2026-09-27T14:31:02Z");
  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final JobId REQUEST = JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");
  private static final SourceReference SOURCE =
      new SourceReference(REQUEST, "INE831R08076_coupon-details.json", "$.x[0]");

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final AtomicLong nextId = new AtomicLong(1);
  private final JdbcSecurityCollectionRepository repository =
      new JdbcSecurityCollectionRepository(jdbc, () -> new UUID(0, nextId.getAndIncrement()));

  @Test
  void bindsEveryColumnOfCashFlow() {
    databaseAddsEveryRow();
    CashFlow cashFlow =
        new CashFlow(
            "Partial Redemption",
            LocalDate.of(2026, 6, 17),
            LocalDate.of(2026, 7, 2),
            new BigDecimal("1340.41"),
            LocalDate.of(2026, 7, 2),
            new BigDecimal("8750"));

    int appended = repository.appendCashFlows(List.of(entry(cashFlow)), RECORDED_AT);

    SqlParameterSource bound = singleBatch(JdbcSecurityCollectionRepository.INSERT_CASH_FLOW)[0];
    assertThat(appended).isEqualTo(1);
    assertThat(bound.getValue("id")).isEqualTo(new UUID(0, 1));
    assertThat(bound.getValue("isin")).isEqualTo("INE831R08076");
    assertThat(bound.getValue("eventType")).isEqualTo("Partial Redemption");
    assertThat(bound.getValue("recordDate")).isEqualTo(LocalDate.of(2026, 6, 17));
    assertThat(bound.getValue("dueDate")).isEqualTo(LocalDate.of(2026, 7, 2));
    assertThat(bound.getValue("amountPayable")).isEqualTo(new BigDecimal("1340.41"));
    assertThat(bound.getValue("paymentDate")).isEqualTo(LocalDate.of(2026, 7, 2));
    assertThat(bound.getValue("newFaceValue")).isEqualTo(new BigDecimal("8750"));
    assertThat(bound.getValue("sourceRequestId")).isEqualTo(REQUEST.value());
    assertThat(bound.getValue("sourceFile")).isEqualTo("INE831R08076_coupon-details.json");
    assertThat(bound.getValue("sourceLocation")).isEqualTo("$.x[0]");
    assertThat(bound.getValue("recordedAt")).isEqualTo(RECORDED_AT.atOffset(ZoneOffset.UTC));
  }

  @Test
  void bindsAbsentValuesAsNull() {
    databaseAddsEveryRow();

    repository.appendCashFlows(
        List.of(entry(new CashFlow(null, null, null, null, null, null))), RECORDED_AT);

    SqlParameterSource bound = singleBatch(JdbcSecurityCollectionRepository.INSERT_CASH_FLOW)[0];
    for (String column :
        List.of(
            "eventType", "recordDate", "dueDate", "amountPayable", "paymentDate", "newFaceValue")) {
      assertThat(bound.hasValue(column)).as(column).isTrue();
      assertThat(bound.getValue(column)).as(column).isNull();
    }
  }

  @Test
  void bindsEveryColumnOfListing() {
    databaseAddsEveryRow();

    repository.appendListings(
        List.of(entry(new Listing("NSE", LocalDate.of(2019, 6, 14)))), RECORDED_AT);

    SqlParameterSource bound = singleBatch(JdbcSecurityCollectionRepository.INSERT_LISTING)[0];
    assertThat(bound.getValue("exchangeName")).isEqualTo("NSE");
    assertThat(bound.getValue("listingDate")).isEqualTo(LocalDate.of(2019, 6, 14));
  }

  @Test
  void bindsEveryColumnOfRatingWithItsSourceCategoryByName() {
    databaseAddsEveryRow();
    Rating rating =
        new Rating(
            SourceCategory.EARLIER,
            "INDIA RATING AND RESEARCH PVT. LTD",
            "AAA",
            "Stable",
            "Reaffirm",
            LocalDate.of(2019, 2, 15),
            LocalDate.of(2026, 9, 23),
            LocalDate.of(2026, 9, 24));

    repository.appendRatings(List.of(entry(rating)), RECORDED_AT);

    SqlParameterSource bound = singleBatch(JdbcSecurityCollectionRepository.INSERT_RATING)[0];
    assertThat(bound.getValue("sourceCategory")).isEqualTo("EARLIER");
    assertThat(bound.getValue("ratingAgencyName")).isEqualTo("INDIA RATING AND RESEARCH PVT. LTD");
    assertThat(bound.getValue("rating")).isEqualTo("AAA");
    assertThat(bound.getValue("outlook")).isEqualTo("Stable");
    assertThat(bound.getValue("ratingAction")).isEqualTo("Reaffirm");
    assertThat(bound.getValue("ratingDate")).isEqualTo(LocalDate.of(2019, 2, 15));
    assertThat(bound.getValue("ratingChangeDate")).isEqualTo(LocalDate.of(2026, 9, 23));
    assertThat(bound.getValue("verificationDate")).isEqualTo(LocalDate.of(2026, 9, 24));
  }

  @Test
  void bindsEveryColumnOfCollateralAsset() {
    databaseAddsEveryRow();

    repository.appendCollateralAssets(
        List.of(entry(new CollateralAsset("Book Debts / Receivables", "Secured by loans.", null))),
        RECORDED_AT);

    SqlParameterSource bound =
        singleBatch(JdbcSecurityCollectionRepository.INSERT_COLLATERAL_ASSET)[0];
    assertThat(bound.getValue("assetType")).isEqualTo("Book Debts / Receivables");
    assertThat(bound.getValue("collateralDescription")).isEqualTo("Secured by loans.");
    assertThat(bound.hasValue("remarks")).isTrue();
    assertThat(bound.getValue("remarks")).isNull();
  }

  @Test
  void countsOnlyTheRowsTheDatabaseAdded() {
    when(jdbc.batchUpdate(any(String.class), any(SqlParameterSource[].class)))
        .thenReturn(new int[] {1, 0, 1});
    List<SecurityEntry<Listing>> entries =
        List.of(
            entry(new Listing("NSE", null)),
            entry(new Listing("NSE", null)),
            entry(new Listing("BSE", null)));

    assertThat(repository.appendListings(entries, RECORDED_AT)).isEqualTo(2);
  }

  @Test
  void givesEachEntryItsOwnIdentifierAndWritesInBatches() {
    databaseAddsEveryRow();
    List<SecurityEntry<Listing>> entries = new ArrayList<>();
    for (int index = 0; index < 1_201; index++) {
      entries.add(entry(new Listing("X" + index, null)));
    }

    int appended = repository.appendListings(entries, RECORDED_AT);

    ArgumentCaptor<SqlParameterSource[]> batches =
        ArgumentCaptor.forClass(SqlParameterSource[].class);
    verify(jdbc, times(3))
        .batchUpdate(eq(JdbcSecurityCollectionRepository.INSERT_LISTING), batches.capture());
    assertThat(batches.getAllValues())
        .extracting(batch -> batch.length)
        .containsExactly(500, 500, 201);
    assertThat(batches.getAllValues().get(0)[0].getValue("id")).isEqualTo(new UUID(0, 1));
    assertThat(batches.getAllValues().get(2)[200].getValue("id")).isEqualTo(new UUID(0, 1_201));
    assertThat(appended).isEqualTo(1_201);
  }

  @Test
  void doesNotTouchTheDatabaseWhenThereIsNothingToAppend() {
    assertThat(repository.appendCashFlows(List.of(), RECORDED_AT)).isZero();
    assertThat(repository.appendRatings(List.of(), RECORDED_AT)).isZero();

    verifyNoInteractions(jdbc);
  }

  @Test
  void everyStatementAppendsOnlyNewEntriesToItsOwnTable() {
    assertThat(JdbcSecurityCollectionRepository.INSERT_CASH_FLOW)
        .contains(
            "securities_data.security_cash_flows", "new_face_value", "ON CONFLICT DO NOTHING");
    assertThat(JdbcSecurityCollectionRepository.INSERT_LISTING)
        .contains("securities_data.security_listings", "listing_date", "ON CONFLICT DO NOTHING");
    assertThat(JdbcSecurityCollectionRepository.INSERT_RATING)
        .contains("securities_data.security_ratings", "source_category", "ON CONFLICT DO NOTHING");
    assertThat(JdbcSecurityCollectionRepository.INSERT_COLLATERAL_ASSET)
        .contains(
            "securities_data.security_collateral_assets",
            "collateral_description",
            "ON CONFLICT DO NOTHING");
  }

  private void databaseAddsEveryRow() {
    when(jdbc.batchUpdate(any(String.class), any(SqlParameterSource[].class)))
        .thenAnswer(
            invocation -> {
              int[] added = new int[invocation.<SqlParameterSource[]>getArgument(1).length];
              Arrays.fill(added, 1);
              return added;
            });
  }

  private SqlParameterSource[] singleBatch(String statement) {
    ArgumentCaptor<SqlParameterSource[]> batch =
        ArgumentCaptor.forClass(SqlParameterSource[].class);
    verify(jdbc).batchUpdate(eq(statement), batch.capture());
    return batch.getValue();
  }

  private static <T> SecurityEntry<T> entry(T value) {
    return new SecurityEntry<>(ISIN, value, SOURCE);
  }
}

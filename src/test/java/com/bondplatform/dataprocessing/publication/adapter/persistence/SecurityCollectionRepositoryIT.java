package com.bondplatform.dataprocessing.publication.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.publication.application.SecurityCollectionRepository;
import com.bondplatform.dataprocessing.publication.application.SecurityRepository;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The append-only collection repository against PostgreSQL (LLD sections 13.4 and 13.6). */
class SecurityCollectionRepositoryIT extends PostgresIntegrationTest {

  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final Isin OTHER = Isin.of("INE0O7U07046");
  private static final Instant FIRST = Instant.parse("2026-09-27T14:31:02Z");
  private static final Instant LATER = Instant.parse("2026-10-27T09:00:00Z");
  private static final JobId REQUEST = JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");
  private static final JobId LATER_REQUEST = JobId.parse("1c7f1b63-7c2f-4e1d-8a54-3a4b6e2d8f21");

  @Autowired private SecurityRepository securities;
  @Autowired private SecurityCollectionRepository collections;

  @BeforeEach
  void createSecurities() {
    securities.insertMissing(List.of(ISIN, OTHER), FIRST);
  }

  @Test
  void appendsNewCashFlowWithItsValuesSourceAndFirstRecordedTime() {
    CashFlow cashFlow =
        new CashFlow(
            "Partial Redemption",
            LocalDate.of(2026, 6, 17),
            LocalDate.of(2026, 7, 2),
            new BigDecimal("1340.41"),
            LocalDate.of(2026, 7, 2),
            new BigDecimal("8750"));

    int appended = collections.appendCashFlows(List.of(entry(ISIN, cashFlow, "$.x[2]")), FIRST);

    Map<String, Object> row = single("security_cash_flows");
    assertThat(appended).isEqualTo(1);
    assertThat(row.get("event_type")).isEqualTo("Partial Redemption");
    assertThat(row.get("due_date")).hasToString("2026-07-02");
    assertThat((BigDecimal) row.get("amount_payable")).isEqualByComparingTo("1340.41");
    assertThat((BigDecimal) row.get("new_face_value")).isEqualByComparingTo("8750");
    assertThat(row.get("source_request_id")).isEqualTo(REQUEST.value());
    assertThat(row.get("source_location")).isEqualTo("$.x[2]");
    assertThat(firstRecordedAt(row)).isEqualTo(FIRST);
    assertThat(row.get("id")).isNotNull();
  }

  @Test
  void everyColumnOfEveryCollectionIsStoredInItsOwnPlace() {
    collections.appendCashFlows(
        List.of(
            entry(
                ISIN,
                new CashFlow(
                    "Interest",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 2, 2),
                    new BigDecimal("11.5"),
                    LocalDate.of(2026, 3, 3),
                    new BigDecimal("22.5")),
                "$.c[0]")),
        FIRST);
    collections.appendListings(
        List.of(entry(ISIN, new Listing("Nse", LocalDate.of(2019, 6, 14)), "$.l[0]")), FIRST);
    collections.appendRatings(
        List.of(
            entry(
                ISIN,
                new Rating(
                    SourceCategory.EARLIER,
                    "agency",
                    "grade",
                    "view",
                    "action",
                    LocalDate.of(2026, 4, 4),
                    LocalDate.of(2026, 5, 5),
                    LocalDate.of(2026, 6, 6)),
                "$.r[0]")),
        FIRST);
    collections.appendCollateralAssets(
        List.of(entry(ISIN, new CollateralAsset("type", "description", "note"), "$.a[0]")), FIRST);

    assertThat(textOf("security_cash_flows"))
        .isEqualTo(
            "event_type=Interest, record_date=2026-01-01, due_date=2026-02-02,"
                + " amount_payable=11.5, payment_date=2026-03-03, new_face_value=22.5");
    assertThat(textOf("security_listings")).isEqualTo("exchange_name=Nse, listing_date=2019-06-14");
    assertThat(textOf("security_ratings"))
        .isEqualTo(
            "source_category=EARLIER, rating_agency_name=agency, rating=grade, outlook=view,"
                + " rating_action=action, rating_date=2026-04-04, rating_change_date=2026-05-05,"
                + " verification_date=2026-06-06");
    assertThat(textOf("security_collateral_assets"))
        .isEqualTo("asset_type=type, collateral_description=description, remarks=note");
  }

  @Test
  void identicalEntryIsSkippedAndKeepsItsOriginalSourceAndTime() {
    CashFlow cashFlow =
        new CashFlow(
            "Interest", null, LocalDate.of(2027, 6, 8), new BigDecimal("89400"), null, null);
    collections.appendCashFlows(List.of(entry(ISIN, cashFlow, "$.x[0]")), FIRST);

    CashFlow sameWithOtherScale =
        new CashFlow(
            "Interest", null, LocalDate.of(2027, 6, 8), new BigDecimal("89400.00"), null, null);
    int appended =
        collections.appendCashFlows(
            List.of(
                new SecurityEntry<>(
                    ISIN,
                    sameWithOtherScale,
                    new SourceReference(
                        LATER_REQUEST, "INE831R08076_coupon-details.json", "$.x[9]"))),
            LATER);

    Map<String, Object> row = single("security_cash_flows");
    assertThat(appended).isZero();
    assertThat(row.get("source_request_id")).isEqualTo(REQUEST.value());
    assertThat(row.get("source_location")).isEqualTo("$.x[0]");
    assertThat(firstRecordedAt(row)).isEqualTo(FIRST);
  }

  @Test
  void duplicatesWithinOneCallAreStoredOnce() {
    Listing listing = new Listing("NSE", LocalDate.of(2019, 6, 14));

    int appended =
        collections.appendListings(
            List.of(entry(ISIN, listing, "$.l[0]"), entry(ISIN, listing, "$.l[1]")), FIRST);

    assertThat(appended).isEqualTo(1);
    assertThat(count("security_listings")).isEqualTo(1);
  }

  @Test
  void changedCombinationOfValuesIsNewEntry() {
    collections.appendListings(List.of(entry(ISIN, new Listing("NSE", null), "$.l[0]")), FIRST);

    int appended =
        collections.appendListings(
            List.of(entry(ISIN, new Listing("NSE", LocalDate.of(2019, 6, 14)), "$.l[0]")), LATER);

    assertThat(appended).isEqualTo(1);
    assertThat(count("security_listings")).isEqualTo(2);
  }

  @Test
  void sameValuesForAnotherSecurityAreSeparateEntry() {
    Listing listing = new Listing("NSE", LocalDate.of(2019, 6, 14));

    int appended =
        collections.appendListings(
            List.of(entry(ISIN, listing, "$.l[0]"), entry(OTHER, listing, "$.l[0]")), FIRST);

    assertThat(appended).isEqualTo(2);
  }

  @Test
  void sameRatingInCurrentAndEarlierListsAreTwoEntries() {
    int appended =
        collections.appendRatings(
            List.of(
                entry(ISIN, rating(SourceCategory.CURRENT), "$.currentRatings[0]"),
                entry(ISIN, rating(SourceCategory.EARLIER), "$.earlierRatings[0]"),
                entry(ISIN, rating(SourceCategory.CURRENT), "$.currentRatings[1]")),
            FIRST);

    assertThat(appended).isEqualTo(2);
    assertThat(
            jdbc.sql(
                    "SELECT source_category FROM securities_data.security_ratings"
                        + " ORDER BY source_category")
                .query(String.class)
                .list())
        .containsExactly("CURRENT", "EARLIER");
  }

  @Test
  void collateralAssetsDifferingOnlyByAbsentRemarksAreOneEntry() {
    CollateralAsset asset = new CollateralAsset("Book Debts / Receivables", "Secured.", null);

    collections.appendCollateralAssets(List.of(entry(ISIN, asset, "$.a[0]")), FIRST);
    int appended = collections.appendCollateralAssets(List.of(entry(ISIN, asset, "$.a[0]")), LATER);

    assertThat(appended).isZero();
    assertThat(count("security_collateral_assets")).isEqualTo(1);
  }

  @Test
  void entryForUnknownSecurityIsRefused() {
    Isin unknown = Isin.of("UNKNOWN00001");

    assertThatThrownBy(
            () ->
                collections.appendListings(
                    List.of(entry(unknown, new Listing("NSE", null), "$.l[0]")), FIRST))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static Rating rating(SourceCategory category) {
    return new Rating(
        category,
        "INDIA RATING AND RESEARCH PVT. LTD",
        "AAA",
        "Stable",
        "Reaffirm",
        LocalDate.of(2019, 2, 15),
        null,
        null);
  }

  private static <T> SecurityEntry<T> entry(Isin isin, T value, String location) {
    return new SecurityEntry<>(
        isin, value, new SourceReference(REQUEST, "INE831R08076_coupon-details.json", location));
  }

  /** Returns the business columns of a table's single row as {@code column=value} pairs. */
  private String textOf(String table) {
    Set<String> notBusiness =
        Set.of(
            "id",
            "isin",
            "source_request_id",
            "source_file",
            "source_location",
            "first_recorded_at");
    return single(table).entrySet().stream()
        .filter(column -> !notBusiness.contains(column.getKey()))
        .map(column -> column.getKey() + "=" + column.getValue())
        .collect(Collectors.joining(", "));
  }

  private Map<String, Object> single(String table) {
    return jdbc.sql("SELECT * FROM securities_data." + table).query().singleRow();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM securities_data." + table).query(Long.class).single();
  }

  private static Instant firstRecordedAt(Map<String, Object> row) {
    return ((Timestamp) Objects.requireNonNull(row.get("first_recorded_at"))).toInstant();
  }
}

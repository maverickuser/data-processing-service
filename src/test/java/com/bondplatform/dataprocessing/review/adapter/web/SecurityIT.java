package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** I-READ-03 and I-READ-10: the security view and the ISIN list over HTTP, from PostgreSQL. */
class SecurityIT extends PostgresIntegrationTest {

  private static final String ISIN = "INE831R08076";
  private static final List<String> PROPERTIES =
      List.of(
          "isin",
          "issuerName",
          "issuerOwnershipType",
          "instrumentType",
          "allotmentDate",
          "redemptionDate",
          "originalFaceValue",
          "couponRate",
          "couponType",
          "listingStatus",
          "collateralStatus",
          "assetCoverageBasis",
          "assetCoverage",
          "collateralAssets",
          "currentRatings",
          "ratingHistory",
          "listings",
          "cashFlows",
          "createdAt",
          "updatedAt");

  @Autowired private WebApplicationContext context;
  @Autowired private JsonMapper json;

  private MockMvc http;

  @BeforeEach
  void client() {
    http = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void fullSecurityShowsEveryFieldAndCollection() throws Exception {
    jdbc.sql(
            """
            INSERT INTO securities_data.securities
              (isin, issuer_name, issuer_ownership_type, instrument_type, allotment_date,
               redemption_date, original_face_value, collateral_status, asset_coverage_basis,
               asset_coverage_value, asset_coverage_unit, coupon_rate_value, coupon_rate_unit,
               coupon_type, listing_status, field_sources, created_at, updated_at)
            VALUES (:isin, 'ADITYA BIRLA HOUSING FINANCE LIMITED', 'Non PSU', 'Debentures',
               DATE '2019-06-10', DATE '2029-06-08', 1000000, 'Secured', 'Book Debts', 100.0,
               'PERCENT', 8.94, 'PERCENT', 'Simple', 'Listed',
               '{"couponType": {"sourceFile": "a.json"}}', TIMESTAMPTZ '2026-09-21T15:00:00Z',
               TIMESTAMPTZ '2026-09-27T14:31:02Z')
            """)
        .param("isin", ISIN)
        .update();
    insert(
        "security_ratings",
        "source_category, rating_agency_name, rating, rating_date",
        "'CURRENT', 'ICRA', 'AA', DATE '2018-01-01'");
    insert(
        "security_ratings",
        "source_category, rating_agency_name, rating, rating_date",
        "'CURRENT', 'ICRA', 'AAA', DATE '2019-02-15'");
    insert(
        "security_ratings",
        "source_category, rating_agency_name, rating, rating_date",
        "'EARLIER', 'CARE', 'A', DATE '2017-01-01'");
    insert("security_listings", "exchange_name, listing_date", "'NSE', DATE '2019-06-14'");
    insert(
        "security_cash_flows",
        "event_type, due_date, amount_payable",
        "'Interest', DATE '2027-06-08', 89400.00");
    insert("security_collateral_assets", "asset_type", "'Receivables'");

    JsonNode body = security(ISIN);

    assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(PROPERTIES);
    assertThat(body.get("issuerName").asString()).isEqualTo("ADITYA BIRLA HOUSING FINANCE LIMITED");
    assertThat(body.get("allotmentDate").asString()).isEqualTo("2019-06-10");
    assertThat(body.get("originalFaceValue").decimalValue()).isEqualByComparingTo("1000000");
    assertThat(body.at("/couponRate/value").decimalValue()).isEqualByComparingTo("8.94");
    assertThat(body.at("/couponRate/unit").asString()).isEqualTo("PERCENT");
    assertThat(body.at("/assetCoverage/value").decimalValue()).isEqualByComparingTo("100");
    assertThat(body.get("collateralAssets")).hasSize(1);
    assertThat(body.at("/collateralAssets/0/assetType").asString()).isEqualTo("Receivables");
    assertThat(body.get("currentRatings")).hasSize(1);
    assertThat(body.at("/currentRatings/0/rating").asString()).isEqualTo("AAA");
    assertThat(body.at("/currentRatings/0/firstRecordedAt").asString()).endsWith("Z");
    assertThat(body.get("ratingHistory"))
        .extracting(rating -> rating.get("rating").asString())
        .containsExactly("AA", "A");
    assertThat(body.at("/listings/0/listingDate").asString()).isEqualTo("2019-06-14");
    assertThat(body.at("/cashFlows/0/amountPayable").decimalValue())
        .isEqualTo(new java.math.BigDecimal("89400.00"));
    assertThat(body.at("/cashFlows/0/recordDate").isNull()).isTrue();
    assertThat(body.get("createdAt").asString()).isEqualTo("2026-09-21T15:00:00Z");
    assertThat(body.toString()).doesNotContain("sourceFile").doesNotContain("a.json");
  }

  // LLD 15.2: Unsecured hides recorded assets, which stay stored
  @Test
  void unsecuredSecurityShowsNoAssets() throws Exception {
    isinOnly();
    jdbc.sql("UPDATE securities_data.securities SET collateral_status = 'UNSECURED'").update();
    insert("security_collateral_assets", "asset_type", "'Receivables'");

    assertThat(security(ISIN).get("collateralAssets")).isEmpty();
  }

  @Test
  void isinOnlySecurityHasNullsAndEmptyCollections() throws Exception {
    isinOnly();

    JsonNode body = security(" " + ISIN.toLowerCase(java.util.Locale.ROOT) + " ");

    assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(PROPERTIES);
    assertThat(body.get("isin").asString()).isEqualTo(ISIN);
    assertThat(body.get("issuerName").isNull()).isTrue();
    assertThat(body.get("couponRate").isNull()).isTrue();
    assertThat(body.get("assetCoverage").isNull()).isTrue();
    for (String collection :
        List.of("collateralAssets", "currentRatings", "ratingHistory", "listings", "cashFlows")) {
      assertThat(body.get(collection).isArray()).isTrue();
      assertThat(body.get(collection)).isEmpty();
    }
  }

  @Test
  void unknownIsinIsProblem() throws Exception {
    MockHttpServletResponse response =
        http.perform(get("/v1/securities/INE000000000")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).startsWith("application/problem+json");
  }

  // I-READ-10
  @Test
  void isinListIsAscendingAndPaged() throws Exception {
    List<String> isins = new ArrayList<>();
    for (int n = 59; n >= 0; n--) {
      isins.addFirst("INE%09d".formatted(n));
      isinOnly(isins.getFirst());
    }

    JsonNode first = ok("/v1/securities", null);
    JsonNode second = ok("/v1/securities", first.get("nextToken").asString());

    assertThat(first.get("items")).hasSize(50);
    assertThat(first.at("/items/0").asString()).isEqualTo(isins.getFirst());
    assertThat(first.at("/items/49").asString()).isEqualTo(isins.get(49));
    assertThat(second.get("items")).hasSize(10);
    assertThat(second.at("/items/0").asString()).isEqualTo(isins.get(50));
    assertThat(second.get("nextToken").isNull()).isTrue();
  }

  // I-READ-10: no securities is an empty page; a changed token is 400
  @Test
  void isinListWithoutSecuritiesIsEmptyAndBadTokenIsBadRequest() throws Exception {
    JsonNode none = ok("/v1/securities", null);
    assertThat(none.get("items")).isEmpty();
    assertThat(none.get("nextToken").isNull()).isTrue();

    MockHttpServletResponse response =
        http.perform(get("/v1/securities").param("pageToken", "changed")).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
  }

  private JsonNode ok(String path, @Nullable String pageToken) throws Exception {
    var request = get(path);
    if (pageToken != null) {
      request.param("pageToken", pageToken);
    }
    MockHttpServletResponse response = http.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return json.readTree(response.getContentAsString());
  }

  private JsonNode security(String isin) throws Exception {
    MockHttpServletResponse response =
        http.perform(get("/v1/securities/{isin}", isin)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return json.readTree(response.getContentAsString());
  }

  private void isinOnly() {
    isinOnly(ISIN);
  }

  private void isinOnly(String isin) {
    jdbc.sql(
            "INSERT INTO securities_data.securities (isin, created_at, updated_at)"
                + " VALUES (:isin, now(), now())")
        .param("isin", isin)
        .update();
  }

  /** Inserts one collection row for the ISIN with a source reference and a recording time. */
  private void insert(String table, String columns, String values) {
    jdbc.sql(
            "INSERT INTO securities_data."
                + table
                + " (id, isin, "
                + columns
                + ", source_request_id, source_file, source_location, first_recorded_at)"
                + " VALUES (:id, :isin, "
                + values
                + ", :request, 'a.json', '$.x', now())")
        .param("id", UUID.randomUUID())
        .param("isin", ISIN)
        .param("request", UUID.randomUUID())
        .update();
  }
}

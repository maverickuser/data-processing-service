package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** I-READ-04 and I-READ-05: both daily-market-summary endpoints, read from PostgreSQL. */
class DailyMarketSummaryIT extends PostgresIntegrationTest {

  private static final String ISIN = "INE0KH208019";
  private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
  private static final List<String> PROPERTIES =
      List.of(
          "isin",
          "tradeDate",
          "exchangeName",
          "securityCode",
          "openPrice",
          "highPrice",
          "lowPrice",
          "closePrice",
          "tradedVolume",
          "numberOfTrades",
          "turnover",
          "faceValue",
          "createdAt",
          "updatedAt");

  @Autowired private WebApplicationContext context;
  @Autowired private JsonMapper json;

  private MockMvc http;

  @BeforeEach
  void client() {
    http = MockMvcBuilders.webAppContextSetup(context).build();
  }

  // I-READ-04
  @Test
  void securitySummariesAreNewestFirstAndPageBackwards() throws Exception {
    security(ISIN);
    for (int day = 0; day < 30; day++) {
      summary(ISIN, FIRST.plusDays(day), "BSE");
      summary(ISIN, FIRST.plusDays(day), "NSE");
    }

    JsonNode first = ok(get("/v1/securities/{isin}/daily-market-summaries", " ine0kh208019 "));
    JsonNode second =
        ok(
            get("/v1/securities/{isin}/daily-market-summaries", ISIN)
                .param("pageToken", first.get("nextToken").asString()));

    assertThat(first.get("items")).hasSize(50);
    assertThat(first.at("/items/0/tradeDate").asString()).isEqualTo("2026-01-30");
    assertThat(first.at("/items/0/exchangeName").asString()).isEqualTo("BSE");
    assertThat(first.at("/items/1/exchangeName").asString()).isEqualTo("NSE");
    assertThat(second.get("items")).hasSize(10);
    assertThat(second.at("/items/0/tradeDate").asString()).isEqualTo("2026-01-05");
    assertThat(second.at("/items/9/tradeDate").asString()).isEqualTo("2026-01-01");
    assertThat(second.get("nextToken").isNull()).isTrue();
    JsonNode item = first.at("/items/0");
    assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(PROPERTIES);
    assertThat(item.get("openPrice").decimalValue())
        .isEqualTo(new java.math.BigDecimal("114200.00"));
    assertThat(item.get("tradedVolume").isIntegralNumber()).isTrue();
    assertThat(item.get("tradedVolume").asLong()).isEqualTo(14);
    assertThat(item.get("securityCode").isNull()).isTrue();
    assertThat(item.get("createdAt").asString()).isEqualTo("2026-01-01T15:00:00Z");
    assertThat(item.toString()).doesNotContain("source").doesNotContain("bhavcopy.csv");
  }

  // I-READ-04
  @Test
  void securityDateRangeIsInclusiveAndKnownIsinWithoutSummariesIsEmpty() throws Exception {
    security(ISIN);
    security("INE000000001");
    for (int day = 0; day < 5; day++) {
      summary(ISIN, FIRST.plusDays(day), "BSE");
    }

    JsonNode range =
        ok(
            get("/v1/securities/{isin}/daily-market-summaries", ISIN)
                .param("fromDate", "2026-01-02")
                .param("toDate", "2026-01-04"));
    JsonNode none = ok(get("/v1/securities/{isin}/daily-market-summaries", "INE000000001"));

    assertThat(range.get("items"))
        .extracting(item -> item.get("tradeDate").asString())
        .containsExactly("2026-01-04", "2026-01-03", "2026-01-02");
    assertThat(none.get("items")).isEmpty();
    assertThat(none.get("nextToken").isNull()).isTrue();
  }

  // I-READ-04, LLD 20.2: unknown ISIN, reversed dates, bad dates and tokens
  @Test
  void securityProblemsAreNotFoundOrBadRequest() throws Exception {
    security(ISIN);

    problem(get("/v1/securities/{isin}/daily-market-summaries", "INE999999999"), 404);
    problem(
        get("/v1/securities/{isin}/daily-market-summaries", ISIN)
            .param("fromDate", "2026-01-05")
            .param("toDate", "2026-01-01"),
        400);
    problem(
        get("/v1/securities/{isin}/daily-market-summaries", ISIN).param("fromDate", "2026-13-01"),
        400);
    problem(
        get("/v1/securities/{isin}/daily-market-summaries", ISIN).param("pageToken", "changed"),
        400);
  }

  // I-READ-05
  @Test
  void tradeDateSummariesAreByIsinFilteredAndPaged() throws Exception {
    List<String> isins = new ArrayList<>();
    for (int n = 0; n < 30; n++) {
      String isin = "INE%09d".formatted(n);
      isins.add(isin);
      security(isin);
      summary(isin, FIRST, "NSE");
      summary(isin, FIRST, "BSE");
      summary(isin, FIRST.plusDays(1), "BSE");
    }

    JsonNode first = ok(get("/v1/daily-market-summaries").param("tradeDate", "2026-01-01"));
    JsonNode second =
        ok(
            get("/v1/daily-market-summaries")
                .param("tradeDate", "2026-01-01")
                .param("pageToken", first.get("nextToken").asString()));
    JsonNode bse =
        ok(
            get("/v1/daily-market-summaries")
                .param("tradeDate", "2026-01-01")
                .param("exchangeName", " BSE "));
    JsonNode none = ok(get("/v1/daily-market-summaries").param("tradeDate", "2025-01-01"));

    assertThat(first.get("items")).hasSize(50);
    assertThat(first.at("/items/0/isin").asString()).isEqualTo(isins.getFirst());
    assertThat(first.at("/items/0/exchangeName").asString()).isEqualTo("BSE");
    assertThat(first.at("/items/1/exchangeName").asString()).isEqualTo("NSE");
    assertThat(second.get("items")).hasSize(10);
    assertThat(second.at("/items/9/isin").asString()).isEqualTo(isins.getLast());
    assertThat(second.get("nextToken").isNull()).isTrue();
    assertThat(bse.get("items")).hasSize(30);
    assertThat(bse.get("items"))
        .extracting(item -> item.get("exchangeName").asString())
        .containsOnly("BSE");
    assertThat(none.get("items")).isEmpty();
  }

  // I-READ-05: tradeDate is required and must be a date; a token is bound to its filter
  @Test
  void tradeDateProblemsAreBadRequest() throws Exception {
    security(ISIN);
    for (int n = 0; n < 51; n++) {
      summary(ISIN, FIRST.minusDays(n), "BSE");
    }

    problem(get("/v1/daily-market-summaries"), 400);
    problem(get("/v1/daily-market-summaries").param("tradeDate", "01-01-2026"), 400);
    problem(
        get("/v1/daily-market-summaries")
            .param("tradeDate", "2026-01-01")
            .param("exchangeName", ""),
        400);
    String token =
        ok(get("/v1/securities/{isin}/daily-market-summaries", ISIN)).get("nextToken").asString();
    problem(
        get("/v1/daily-market-summaries")
            .param("tradeDate", "2026-01-01")
            .param("pageToken", token),
        400);
  }

  private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = http.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return json.readTree(response.getContentAsString());
  }

  private void problem(MockHttpServletRequestBuilder request, int status) throws Exception {
    MockHttpServletResponse response = http.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getContentType()).startsWith("application/problem+json");
  }

  private void security(String isin) {
    jdbc.sql(
            "INSERT INTO securities_data.securities (isin, created_at, updated_at)"
                + " VALUES (:isin, now(), now())")
        .param("isin", isin)
        .update();
  }

  private void summary(String isin, LocalDate tradeDate, String exchange) {
    jdbc.sql(
            """
            INSERT INTO securities_data.security_daily_market_summaries
              (isin, trade_date, exchange_name, open_price, high_price, low_price, close_price,
               traded_volume, number_of_trades, turnover, face_value, source_request_id,
               source_file, source_location, created_at, updated_at)
            VALUES (:isin, :tradeDate, :exchange, 114200.00, 114200.00, 114200.00, 114200.00,
               14, 1, 1598800.00, 100000.00, :request, 'bhavcopy.csv', 'row 2',
               TIMESTAMPTZ '2026-01-01T15:00:00Z', TIMESTAMPTZ '2026-01-01T15:00:00Z')
            """)
        .param("isin", isin)
        .param("tradeDate", tradeDate)
        .param("exchange", exchange)
        .param("request", UUID.randomUUID())
        .update();
  }
}

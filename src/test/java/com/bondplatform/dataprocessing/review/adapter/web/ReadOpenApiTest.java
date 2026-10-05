package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Proves the checker used by I-READ-06 accepts the documented shapes and rejects others. */
class ReadOpenApiTest {

  private static final String PATH = "/v1/securities/{isin}/daily-market-summaries";
  private static final String SUMMARY =
      """
      {"isin": "INE0KH208019", "tradeDate": "2026-01-01", "exchangeName": "BSE",
       "securityCode": null, "openPrice": 114200.00, "highPrice": null, "lowPrice": null,
       "closePrice": null, "tradedVolume": 14, "numberOfTrades": null, "turnover": null,
       "faceValue": null, "createdAt": "2026-01-01T15:00:00Z",
       "updatedAt": "2026-01-01T15:00:00Z"}
      """;

  @Test
  void documentedPageIsAccepted() {
    String page = "{\"items\": [" + SUMMARY + "], \"nextToken\": null}";

    assertThat(ReadOpenApi.violations(PATH, 200, "application/json", page)).isEmpty();
  }

  @Test
  void wrongShapeIsRejected() {
    String fractionalVolume = SUMMARY.replace("\"tradedVolume\": 14", "\"tradedVolume\": 14.5");
    String badDate = SUMMARY.replace("\"2026-01-01\"", "\"01-01-2026\"");

    assertThat(ReadOpenApi.violations(PATH, 200, "application/json", "{\"items\": []}"))
        .isNotEmpty();
    assertThat(
            ReadOpenApi.violations(
                PATH,
                200,
                "application/json",
                "{\"items\": [" + fractionalVolume + "], \"nextToken\": null}"))
        .isNotEmpty();
    assertThat(
            ReadOpenApi.violations(
                PATH,
                200,
                "application/json",
                "{\"items\": [" + badDate + "], \"nextToken\": null}"))
        .isNotEmpty();
  }

  @Test
  void problemIsCheckedThroughTheSharedResponse() {
    String problem = "{\"type\": \"about:blank\", \"title\": \"Not Found\", \"status\": 404}";

    assertThat(ReadOpenApi.violations(PATH, 404, "application/problem+json;charset=UTF-8", problem))
        .isEmpty();
    assertThat(ReadOpenApi.violations(PATH, 404, "application/problem+json", "{}")).isNotEmpty();
  }

  @Test
  void undocumentedOperationStatusOrTypeIsReported() {
    assertThat(ReadOpenApi.violations("/v1/unknown", 200, "application/json", "{}"))
        .containsExactly("GET /v1/unknown is not documented");
    assertThat(ReadOpenApi.violations(PATH, 418, "application/json", "{}"))
        .containsExactly("Status 418 is not documented for GET " + PATH);
    assertThat(ReadOpenApi.violations(PATH, 200, "text/plain", "{}"))
        .containsExactly("text/plain is not documented for 200 of GET " + PATH);
  }

  @Test
  void everyOperationListsItsResponses() {
    assertThat(ReadOpenApi.documentedResponses())
        .contains("/v1/daily-market-summaries 400", "/v1/processing-jobs/{jobId} 404")
        .doesNotContain("/v1/daily-market-summaries 404");
  }
}

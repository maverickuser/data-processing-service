package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonEvidence;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** I-READ-02: the full error list over HTTP, paged from PostgreSQL. */
class JobErrorsIT extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final String ISIN = "INE831R08076";

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private JsonRejectionStore rejections;
  @Autowired private TransactionOperations transactions;
  @Autowired private WebApplicationContext context;
  @Autowired private JsonMapper json;

  private MockMvc http;

  @BeforeEach
  void client() {
    http = MockMvcBuilders.webAppContextSetup(context).build();
  }

  // 120 skipped listings, one error each: pages of 50, 50 and 20 in sequence order
  @Test
  void hundredTwentyErrorsPageAsFiftyFiftyTwenty() throws Exception {
    JobId job = jobWithListingErrors(120);

    List<JsonNode> pages = allPages(job, null);

    assertThat(pages).extracting(page -> page.get("items").size()).containsExactly(50, 50, 20);
    assertThat(pages.getLast().get("nextToken").isNull()).isTrue();
    List<String> paths = new ArrayList<>();
    pages.forEach(
        page -> page.get("items").forEach(item -> paths.add(item.get("path").asString())));
    assertThat(paths)
        .containsExactlyElementsOf(
            IntStream.range(0, 120)
                .mapToObj(n -> "$.listingDetails[" + n + "].listingDate")
                .toList());
    assertThat(paths).doesNotHaveDuplicates();
    JsonNode first = pages.getFirst().at("/items/0");
    assertThat(first.get("code").asString()).isEqualTo("INVALID_DATE");
    assertThat(first.get("field").asString()).isEqualTo("listing_date");
    assertThat(first.get("rawValue").asString()).isEqualTo("31-02-2020");
    // The same errors, in the same order, as the status preview
    JsonNode status =
        json.readTree(
            http.perform(get("/v1/processing-jobs/" + job))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(status.get("errorCount").asInt()).isEqualTo(120);
    assertThat(status.at("/errors/0/errorId").asString())
        .isEqualTo(first.get("errorId").asString());
  }

  @Test
  void isinFilterIsTrimmedAndUppercased() throws Exception {
    JobId job = jobWithListingErrors(3);

    JsonNode matching = page(get(errors(job)).param("isin", "  ine831r08076 "));
    JsonNode other = page(get(errors(job)).param("isin", "INE0O7U07046"));

    assertThat(matching.get("items")).hasSize(3);
    assertThat(matching.at("/items/0/isin").asString()).isEqualTo(ISIN);
    assertThat(other.get("items")).isEmpty();
    assertThat(other.get("nextToken").isNull()).isTrue();
  }

  // U-REV-03 over HTTP: a changed token, or one from another filter, is a 400 problem
  @Test
  void changedOrForeignTokenIsBadRequest() throws Exception {
    JobId job = jobWithListingErrors(60);
    String token = page(get(errors(job))).get("nextToken").asString();

    // Change a character inside the payload, so only the check value can catch it (AN-5)
    char middle = token.charAt(5);
    String edited = token.substring(0, 5) + (middle == 'A' ? 'B' : 'A') + token.substring(6);
    MockHttpServletResponse changed =
        http.perform(get(errors(job)).param("pageToken", edited)).andReturn().getResponse();
    MockHttpServletResponse foreign =
        http.perform(get(errors(job)).param("pageToken", token).param("isin", ISIN))
            .andReturn()
            .getResponse();

    for (MockHttpServletResponse response : List.of(changed, foreign)) {
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentType()).startsWith("application/problem+json");
    }
  }

  // AN-1: a NUL in the filter is a 400, never a database error
  @Test
  void controlCharacterInFilterIsBadRequest() throws Exception {
    JobId job = jobWithListingErrors(1);

    MockHttpServletResponse response =
        http.perform(get(errors(job)).param("isin", "INE" + (char) 0)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void unknownJobIsNotFound() throws Exception {
    MockHttpServletResponse response =
        http.perform(get("/v1/processing-jobs/" + UUID.randomUUID() + "/errors"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
  }

  private List<JsonNode> allPages(JobId job, @Nullable String isin) throws Exception {
    List<JsonNode> pages = new ArrayList<>();
    String token = null;
    do {
      MockHttpServletRequestBuilder request = get(errors(job));
      if (token != null) {
        request.param("pageToken", token);
      }
      if (isin != null) {
        request.param("isin", isin);
      }
      JsonNode page = page(request);
      pages.add(page);
      token = page.get("nextToken").isNull() ? null : page.get("nextToken").asString();
    } while (token != null && pages.size() < 10);
    return pages;
  }

  private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = http.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return json.readTree(response.getContentAsString());
  }

  private static String errors(JobId job) {
    return "/v1/processing-jobs/" + job + "/errors";
  }

  /** A finished NSDL job whose one file lists {@code count} listings with an impossible date. */
  private JobId jobWithListingErrors(int count) {
    Map<String, Object> event = SubmissionEvents.nsdl("run_e" + count, ISIN);
    JobId job = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    UUID run = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          assertThat(jobRuns.lockForRun(job)).isPresent();
          jobRuns.startRun(job, run, 1, NOW);
        });
    String listings =
        IntStream.range(0, count)
            .mapToObj(n -> "{\"listingDate\": \"31-02-2020\"}")
            .collect(Collectors.joining(",", "{\"listingDetails\": [", "]}"));
    long stored =
        rejections.saveJson(
            new JsonCanonicalRun(
                job, 1, Isin.of(ISIN), "nsdl-security-json-v1", "nsdl-security-mapping-v1"),
            run,
            List.of(JsonEvidence.read(listings, List.of())));
    jobRuns.completeRun(
        job,
        run,
        1,
        new JobOutcome(
            JobStatus.FAILED,
            "{\"filesListed\": 1, \"filesProcessed\": 1, \"filesSkipped\": 0,"
                + " \"scalarFieldsChanged\": 0, \"collectionEntriesAppended\": 0,"
                + " \"fieldsRejected\": "
                + count
                + "}",
            Math.toIntExact(stored)),
        NOW);
    return job;
  }
}

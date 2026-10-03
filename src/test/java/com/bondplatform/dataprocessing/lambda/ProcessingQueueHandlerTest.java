package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse.BatchItemFailure;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.bondplatform.dataprocessing.job.application.RunResult;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessingQueueHandlerTest {

  private static final UUID JOB = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

  private final List<JobId> ran = new ArrayList<>();
  private final Map<String, Duration> delays = new LinkedHashMap<>();

  static Stream<RunResult> acknowledgedResults() {
    return Stream.of(RunResult.FINISHED, RunResult.ALREADY_FINISHED, RunResult.UNKNOWN_JOB);
  }

  @ParameterizedTest
  @MethodSource("acknowledgedResults")
  void messageWhoseJobNeedsNoFurtherAttemptIsAcknowledged(RunResult result) {
    SQSBatchResponse response = handle(jobId -> result, message("m1", body(JOB)));

    assertThat(response.getBatchItemFailures()).isEmpty();
    assertThat(ran).containsExactly(new JobId(JOB));
    assertThat(delays).isEmpty();
  }

  // U-JOB-01, the queue's half
  @Test
  void temporaryFailureIsDeliveredAgainAfterTheRetryDelay() {
    SQSBatchResponse response =
        handle(jobId -> RunResult.retryAfter(Duration.ofMinutes(5)), message("m1", body(JOB)));

    assertThat(failedIds(response)).containsExactly("m1");
    assertThat(delays).containsExactly(Map.entry("receipt-m1", Duration.ofMinutes(5)));
  }

  @Test
  void exhaustedJobIsMadeVisibleAtOnceSoTheQueueMovesItToTheDeadLetterQueue() {
    SQSBatchResponse response =
        handle(jobId -> RunResult.ATTEMPTS_EXHAUSTED, message("m1", body(JOB)));

    assertThat(failedIds(response)).containsExactly("m1");
    assertThat(delays).containsExactly(Map.entry("receipt-m1", Duration.ZERO));
  }

  @Test
  void delayThatCannotBeSetStillReportsTheMessageAsFailed() {
    ProcessingQueueHandler handler =
        new ProcessingQueueHandler(
            jobId -> RunResult.retryAfter(Duration.ofMinutes(1)),
            (receipt, delay) -> {
              throw new IllegalStateException("SQS unavailable");
            });

    SQSBatchResponse response =
        handler.handleRequest(event(message("m1", body(JOB))), new FixedLambdaContext());

    assertThat(failedIds(response)).containsExactly("m1");
  }

  @Test
  void jobThatCannotBeRunIsDeliveredAgainAfterTheVisibilityTimeout() {
    SQSBatchResponse response =
        handle(
            jobId -> {
              throw new IllegalStateException("database unavailable");
            },
            message("m1", body(JOB)));

    assertThat(failedIds(response)).containsExactly("m1");
    assertThat(delays).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "not json", "{}", "{\"jobId\": 42}", "{\"jobId\": \"not-a-uuid\"}", "[]"})
  void messageNamingNoJobIsDroppedWithoutRunningAnything(String body) {
    SQSBatchResponse response = handle(jobId -> RunResult.FINISHED, message("m1", body));

    assertThat(response.getBatchItemFailures()).isEmpty();
    assertThat(ran).isEmpty();
  }

  // Review finding W-1: in a FIFO batch, nothing after a failed message may run before it.
  @Test
  void messagesAfterFailedOneInTheBatchAreReportedFailedWithoutRunning() {
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    SQSBatchResponse response =
        handle(
            jobId ->
                jobId.value().equals(second)
                    ? RunResult.retryAfter(Duration.ofMinutes(1))
                    : RunResult.FINISHED,
            message("m1", body(JOB)),
            message("m2", body(second)),
            message("m3", body(third)));

    assertThat(failedIds(response)).containsExactly("m2", "m3");
    assertThat(ran).containsExactly(new JobId(JOB), new JobId(second));
  }

  @Test
  void readsTheJobIdWhateverTheSpacingOfTheBody() {
    assertThat(ProcessingQueueHandler.jobIdOf("{ \"jobId\" : \"" + JOB + "\" }"))
        .contains(new JobId(JOB));
  }

  private SQSBatchResponse handle(Function<JobId, RunResult> runJob, SQSMessage... messages) {
    return new ProcessingQueueHandler(
            jobId -> {
              ran.add(jobId);
              return runJob.apply(jobId);
            },
            delays::put)
        .handleRequest(event(messages), new FixedLambdaContext());
  }

  private static SQSEvent event(SQSMessage... messages) {
    SQSEvent event = new SQSEvent();
    event.setRecords(List.of(messages));
    return event;
  }

  private static List<String> failedIds(SQSBatchResponse response) {
    return response.getBatchItemFailures().stream()
        .map(BatchItemFailure::getItemIdentifier)
        .toList();
  }

  private static SQSMessage message(String id, String body) {
    SQSMessage message = new SQSMessage();
    message.setMessageId(id);
    message.setReceiptHandle("receipt-" + id);
    message.setBody(body);
    return message;
  }

  private static String body(UUID job) {
    return "{\"jobId\": \"" + job + "\"}";
  }
}

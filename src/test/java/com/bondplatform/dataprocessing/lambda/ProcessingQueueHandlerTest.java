package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse.BatchItemFailure;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.bondplatform.dataprocessing.job.application.RunResult;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessingQueueHandlerTest {

  private static final UUID JOB = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

  private final List<JobId> ran = new ArrayList<>();

  @ParameterizedTest
  @EnumSource(value = RunResult.class, names = "RETRY_LATER", mode = EnumSource.Mode.EXCLUDE)
  void messageWhoseJobNeedsNoFurtherAttemptIsAcknowledged(RunResult result) {
    SQSBatchResponse response = handle(jobId -> result, message("m1", body(JOB)));

    assertThat(response.getBatchItemFailures()).isEmpty();
    assertThat(ran).containsExactly(new JobId(JOB));
  }

  @Test
  void temporaryFailureReportsTheMessageAsFailed() {
    SQSBatchResponse response = handle(jobId -> RunResult.RETRY_LATER, message("m1", body(JOB)));

    assertThat(response.getBatchItemFailures())
        .extracting(BatchItemFailure::getItemIdentifier)
        .containsExactly("m1");
  }

  @Test
  void jobThatCannotBeRunReportsTheMessageAsFailed() {
    SQSBatchResponse response =
        handle(
            jobId -> {
              throw new IllegalStateException("database unavailable");
            },
            message("m1", body(JOB)));

    assertThat(response.getBatchItemFailures())
        .extracting(BatchItemFailure::getItemIdentifier)
        .containsExactly("m1");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "not json", "{}", "{\"jobId\": 42}", "{\"jobId\": \"not-a-uuid\"}", "[]"})
  void messageNamingNoJobIsDroppedWithoutRunningAnything(String body) {
    SQSBatchResponse response = handle(jobId -> RunResult.FINISHED, message("m1", body));

    assertThat(response.getBatchItemFailures()).isEmpty();
    assertThat(ran).isEmpty();
  }

  @Test
  void eachMessageOfBatchIsHandledOnItsOwn() {
    UUID other = UUID.randomUUID();
    SQSBatchResponse response =
        handle(
            jobId -> jobId.value().equals(JOB) ? RunResult.RETRY_LATER : RunResult.FINISHED,
            message("m1", body(JOB)),
            message("m2", body(other)));

    assertThat(response.getBatchItemFailures())
        .extracting(BatchItemFailure::getItemIdentifier)
        .containsExactly("m1");
    assertThat(ran).containsExactly(new JobId(JOB), new JobId(other));
  }

  @Test
  void readsTheJobIdWhateverTheSpacingOfTheBody() {
    assertThat(ProcessingQueueHandler.jobIdOf("{ \"jobId\" : \"" + JOB + "\" }"))
        .contains(new JobId(JOB));
  }

  private SQSBatchResponse handle(Function<JobId, RunResult> runJob, SQSMessage... messages) {
    SQSEvent event = new SQSEvent();
    event.setRecords(List.of(messages));
    return new ProcessingQueueHandler(
            jobId -> {
              ran.add(jobId);
              return runJob.apply(jobId);
            })
        .handleRequest(event, new FixedLambdaContext());
  }

  private static SQSMessage message(String id, String body) {
    SQSMessage message = new SQSMessage();
    message.setMessageId(id);
    message.setBody(body);
    return message;
  }

  private static String body(UUID job) {
    return "{\"jobId\": \"" + job + "\"}";
  }
}

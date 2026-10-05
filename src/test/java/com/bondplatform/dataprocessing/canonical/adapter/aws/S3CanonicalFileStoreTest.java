package com.bondplatform.dataprocessing.canonical.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.canonical.adapter.json.CanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.adapter.json.JsonCanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.application.CanonicalFile;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.canonical.domain.JsonEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3CanonicalFileStoreTest {

  private static final List<CanonicalRow> ROWS = GoldenBhavcopy.rows();

  @TempDir Path workDirectory;

  private final S3Client s3 = mock(S3Client.class);
  private final List<PutObjectRequest> requests = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();

  @Test
  void commitUploadsOneLinePerRowUnderTheRunKey() {
    recordUploads();

    String key;
    try (CanonicalFile file = store().create(GoldenBhavcopy.RUN)) {
      ROWS.forEach(file::append);
      key = file.commit();
    }

    assertThat(key).isEqualTo(GoldenBhavcopy.RUN.objectKey());
    assertThat(requests)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.bucket()).isEqualTo("canonical-bucket");
              assertThat(request.key()).isEqualTo(key);
              assertThat(request.contentType()).isEqualTo("application/x-ndjson");
            });
    assertThat(bodies.get(0).lines().toList())
        .containsExactlyElementsOf(
            ROWS.stream().map(row -> CanonicalLineWriter.line(GoldenBhavcopy.RUN, row)).toList());
    assertThat(bodies.get(0)).endsWith("\n");
    assertThat(workDirectory).isEmptyDirectory();
  }

  @Test
  void closingWithoutCommitDiscardsTheRows() {
    try (CanonicalFile file = store().create(GoldenBhavcopy.RUN)) {
      file.append(ROWS.get(0));
    }

    verifyNoInteractions(s3);
    assertThat(workDirectory).isEmptyDirectory();
  }

  @Test
  @SuppressWarnings("unchecked")
  void failedUploadIsTemporaryWithoutTheMessageFromS3() {
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().message("bucket canonical-bucket is gone").build());

    try (CanonicalFile file = store().create(GoldenBhavcopy.RUN)) {
      file.append(ROWS.get(0));
      assertThatThrownBy(file::commit)
          .isInstanceOfSatisfying(
              TemporaryFailureException.class,
              e -> {
                assertThat(e.code()).isEqualTo("CANONICAL_STORE_UNAVAILABLE");
                assertThat(e.getMessage()).doesNotContain("canonical-bucket");
              });
    }
    assertThat(workDirectory).isEmptyDirectory();
  }

  @Test
  void missingWorkDirectoryIsLocalFailure() {
    S3CanonicalFileStore store =
        new S3CanonicalFileStore(s3, "canonical-bucket", workDirectory.resolve("missing"));

    assertThatThrownBy(() -> store.create(GoldenBhavcopy.RUN))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void appendAfterCommitIsLocalFailure() {
    recordUploads();
    try (CanonicalFile file = store().create(GoldenBhavcopy.RUN)) {
      file.commit();

      assertThatThrownBy(() -> file.append(ROWS.get(0))).isInstanceOf(UncheckedIOException.class);
    }
  }

  private S3CanonicalFileStore store() {
    return new S3CanonicalFileStore(s3, "canonical-bucket", workDirectory);
  }

  @Test
  void jsonRequestUploadsOneLinePerFileUnderTheRunKey() {
    recordUploads();
    List<JsonFileEvidence> files =
        List.of(JsonEvidence.skipped(), JsonEvidence.read(JsonEvidence.WITH_ERRORS, List.of()));

    String key = store().storeJson(JsonEvidence.RUN, files);

    assertThat(key).isEqualTo(JsonEvidence.RUN.objectKey());
    assertThat(requests)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.bucket()).isEqualTo("canonical-bucket");
              assertThat(request.key()).isEqualTo(key);
              assertThat(request.contentType()).isEqualTo("application/x-ndjson");
            });
    assertThat(bodies.get(0).lines().toList())
        .containsExactlyElementsOf(
            files.stream()
                .map(file -> JsonCanonicalLineWriter.line(JsonEvidence.RUN, file))
                .toList());
    assertThat(bodies.get(0)).endsWith("\n");
    assertThat(workDirectory).isEmptyDirectory();
  }

  @Test
  @SuppressWarnings("unchecked")
  void failedJsonUploadIsTemporaryWithoutTheMessageFromS3() {
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().message("bucket canonical-bucket is gone").build());

    assertThatThrownBy(() -> store().storeJson(JsonEvidence.RUN, List.of(JsonEvidence.skipped())))
        .isInstanceOfSatisfying(
            TemporaryFailureException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CANONICAL_STORE_UNAVAILABLE");
              assertThat(e.getMessage()).doesNotContain("canonical-bucket");
            });
  }

  @SuppressWarnings("unchecked")
  private void recordUploads() {
    when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
        .thenAnswer(
            invocation -> {
              PutObjectRequest.Builder builder = PutObjectRequest.builder();
              ((Consumer<PutObjectRequest.Builder>) invocation.getArgument(0)).accept(builder);
              requests.add(builder.build());
              RequestBody body = invocation.getArgument(1);
              try (InputStream in = body.contentStreamProvider().newStream()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
              return PutObjectResponse.builder().build();
            });
  }
}

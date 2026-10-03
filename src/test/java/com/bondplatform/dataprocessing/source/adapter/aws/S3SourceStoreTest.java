package com.bondplatform.dataprocessing.source.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import com.bondplatform.dataprocessing.source.domain.SourceRead;
import com.bondplatform.dataprocessing.source.domain.VerifiedSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Test cases U-SRC-01..03 at the storage boundary, with S3 mocked. */
class S3SourceStoreTest {

  private static final String BUCKET = "data-fetch-service-artifacts";
  private static final byte[] CONTENT = "ISIN,Close Price\n".getBytes(StandardCharsets.UTF_8);
  private static final Instant MODIFIED = Instant.parse("2026-09-21T10:00:00Z");

  private final S3Client s3 =
      mock(S3Client.class, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));
  private final S3SourceStore store = new S3SourceStore(s3, Set.of(BUCKET));

  @Test
  void readsWholeManifestOfTheGivenVersion() {
    returns(CONTENT, (long) CONTENT.length);

    byte[] read = store.read(new ManifestLocation(BUCKET, "runs/r/manifest.json", "v3"), 1024);

    assertThat(read).isEqualTo(CONTENT);
    ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
    org.mockito.Mockito.verify(s3).getObject(request.capture());
    assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(request.getValue().key()).isEqualTo("runs/r/manifest.json");
    assertThat(request.getValue().versionId()).isEqualTo("v3");
  }

  // U-SRC-02, the manifest
  @Test
  void manifestLargerThanTheLimitIsTooLargeWhetherOrNotItsLengthIsKnown() {
    returns(CONTENT, (long) CONTENT.length);
    assertFails(() -> store.read(manifest(), CONTENT.length - 1), "SOURCE_TOO_LARGE");

    returns(CONTENT, null);
    assertFails(() -> store.read(manifest(), CONTENT.length - 1), "SOURCE_TOO_LARGE");
  }

  @Test
  void manifestExactlyAtTheLimitIsRead() {
    returns(CONTENT, null);

    assertThat(store.read(manifest(), CONTENT.length)).isEqualTo(CONTENT);
  }

  @Test
  void verifiedFileCarriesItsContentTimeAndVersion() {
    returns(CONTENT, (long) CONTENT.length);

    SourceRead read = store.read(file(CONTENT.length, sha256(CONTENT)));

    assertThat(read)
        .isInstanceOfSatisfying(
            SourceRead.Verified.class,
            verified -> {
              VerifiedSource source = verified.source();
              assertThat(readAll(source.open())).isEqualTo(CONTENT);
              assertThat(source.size()).isEqualTo(CONTENT.length);
              assertThat(source.lastModified()).contains(MODIFIED);
              assertThat(source.versionId()).contains("v1");
              assertThat(source.file().fileName()).isEqualTo("BSE_fgroup21092026.csv");
            });
  }

  // U-SRC-01
  @Test
  void sizeDifferingFromTheManifestIsMismatchWhetherReportedOrRead() {
    returns(CONTENT, (long) CONTENT.length);
    assertThat(store.read(file(CONTENT.length + 1, sha256(CONTENT))))
        .isInstanceOfSatisfying(
            SourceRead.ChecksumMismatch.class,
            mismatch -> assertThat(mismatch.detail()).contains("size 17 bytes, listed as 18"));

    returns(CONTENT, null);
    assertThat(store.read(file(CONTENT.length - 1, sha256(CONTENT))))
        .isInstanceOfSatisfying(
            SourceRead.ChecksumMismatch.class,
            mismatch -> assertThat(mismatch.detail()).contains("more than 16 bytes"));

    returns(CONTENT, null);
    assertThat(store.read(file(CONTENT.length + 1, sha256(CONTENT))))
        .isInstanceOf(SourceRead.ChecksumMismatch.class);
  }

  // U-SRC-01
  @Test
  void contentWithAnotherHashIsMismatchNamingBothHashes() {
    returns(CONTENT, (long) CONTENT.length);

    assertThat(store.read(file(CONTENT.length, "0".repeat(64))))
        .isInstanceOfSatisfying(
            SourceRead.ChecksumMismatch.class,
            mismatch ->
                assertThat(mismatch.detail())
                    .contains("BSE_fgroup21092026.csv", sha256(CONTENT), "0".repeat(64)));
  }

  // U-SRC-03
  @Test
  void missingOrRefusedObjectIsSourceNotFound() {
    failsWith(s3Error(404, "NoSuchKey"));
    assertFails(() -> store.read(file(1, "0".repeat(64))), "SOURCE_NOT_FOUND")
        .hasMessageContaining("BSE_fgroup21092026.csv could not be read: NoSuchKey (HTTP 404)")
        .hasMessageNotContaining("runs/run_101");

    failsWith(s3Error(403, "AccessDenied"));
    assertFails(() -> store.read(manifest(), 10), "SOURCE_NOT_FOUND");
  }

  // U-SRC-03
  @Test
  void throttlingServerErrorAndTimeoutAreTemporary() {
    failsWith(s3Error(503, "SlowDown"));
    assertThatThrownBy(() -> store.read(file(1, "0".repeat(64))))
        .isInstanceOfSatisfying(
            TemporaryFailureException.class,
            failure -> assertThat(failure.code()).isEqualTo("SOURCE_UNAVAILABLE"))
        .hasMessageContaining("SlowDown (HTTP 503)");

    for (String passing : List.of("ExpiredToken", "RequestTimeTooSkewed", "RequestTimeout")) {
      failsWith(s3Error(passing.equals("ExpiredToken") ? 403 : 400, passing));
      assertThatThrownBy(() -> store.read(manifest(), 10))
          .isInstanceOf(TemporaryFailureException.class)
          .hasMessageContaining(passing);
    }

    failsWith(SdkClientException.create("Read timed out"));
    assertThatThrownBy(() -> store.read(manifest(), 10))
        .isInstanceOfSatisfying(
            TemporaryFailureException.class,
            failure ->
                assertThat(failure.getMessage())
                    .isEqualTo("S3 could not be read: SdkClientException"));
  }

  @Test
  void connectionBrokenWhileReadingIsTemporary() {
    doReturn(
            new ResponseInputStream<>(
                response(null),
                AbortableInputStream.create(
                    new InputStream() {
                      @Override
                      public int read() throws IOException {
                        throw new IOException("Connection reset");
                      }

                      @Override
                      public int read(byte[] buffer, int offset, int length) throws IOException {
                        throw new IOException("Connection reset");
                      }
                    })))
        .when(s3)
        .getObject(any(GetObjectRequest.class));

    assertThatThrownBy(() -> store.read(manifest(), 10))
        .isInstanceOf(TemporaryFailureException.class);
  }

  @Test
  void bucketOutsideTheAllowedOnesIsRefusedWithoutAskingS3() {
    assertFails(
            () -> store.read(new ManifestLocation("someone-elses-bucket", "k", null), 10),
            "INVALID_MANIFEST")
        .hasMessageNotContaining("someone-elses-bucket");

    verifyNoInteractions(s3);
  }

  private void returns(byte[] content, @Nullable Long length) {
    doReturn(
            new ResponseInputStream<>(
                response(length), AbortableInputStream.create(new ByteArrayInputStream(content))))
        .when(s3)
        .getObject(any(GetObjectRequest.class));
  }

  private void failsWith(RuntimeException failure) {
    doThrow(failure).when(s3).getObject(any(GetObjectRequest.class));
  }

  private static GetObjectResponse response(@Nullable Long length) {
    return GetObjectResponse.builder()
        .contentLength(length)
        .lastModified(MODIFIED)
        .versionId("v1")
        .build();
  }

  private static S3Exception s3Error(int status, String code) {
    return (S3Exception)
        S3Exception.builder()
            .statusCode(status)
            .message("arn:aws:s3:::secret-account-detail")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
            .build();
  }

  private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertFails(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    return assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            PermanentFailureException.class, failure -> assertThat(failure.code()).isEqualTo(code))
        .hasMessageNotContaining("secret-account-detail");
  }

  private static ManifestLocation manifest() {
    return new ManifestLocation(BUCKET, "runs/run_101/manifest.json", null);
  }

  private static ManifestFile file(long size, String sha256) {
    return new ManifestFile(
        "debt-bhavcopy",
        BUCKET,
        "runs/run_101/raw/debt-bhavcopy/1/BSE_fgroup21092026.csv",
        SourceFormat.CSV,
        sha256,
        size,
        null);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] readAll(InputStream in) {
    try (in) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

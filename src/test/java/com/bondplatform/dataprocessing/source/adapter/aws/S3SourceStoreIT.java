package com.bondplatform.dataprocessing.source.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.source.domain.Manifest;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import com.bondplatform.dataprocessing.source.domain.SourceRead;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Test cases U-SRC-01..03 against an S3-compatible server. */
@Testcontainers(disabledWithoutDocker = true)
class S3SourceStoreIT {

  private static final String BUCKET = "data-fetch-service-artifacts";
  private static final byte[] CSV =
      "ISIN,Close Price\nINE121A07QY9,100.5\n".getBytes(StandardCharsets.UTF_8);

  private static S3Client s3;
  private static S3SourceStore store;

  @BeforeAll
  static void createBucketAndObjects() {
    s3 = S3Mock.client();
    s3.createBucket(request -> request.bucket(BUCKET));
    put("runs/run_101/raw/debt-bhavcopy/1/BSE_fgroup21092026.csv", CSV);
    put("runs/run_101/manifest.json", "{\"id\": \"m\"}".getBytes(StandardCharsets.UTF_8));
    put("runs/run_102/manifest.json", new byte[(int) Manifest.MAX_BYTES + 1]);
    store = new S3SourceStore(s3, Set.of(BUCKET));
  }

  @AfterAll
  static void closeClient() {
    s3.close();
  }

  @Test
  void listedFileMatchingTheManifestIsVerifiedWithItsTime() {
    SourceRead read = store.read(csv(CSV.length, sha256(CSV)));

    assertThat(read)
        .isInstanceOfSatisfying(
            SourceRead.Verified.class,
            verified -> {
              assertThat(verified.source().size()).isEqualTo(CSV.length);
              assertThat(verified.source().lastModified()).isPresent();
            });
  }

  // U-SRC-01
  @Test
  void listedFileWithAnotherHashOrSizeIsMismatch() {
    assertThat(store.read(csv(CSV.length, "0".repeat(64))))
        .isInstanceOf(SourceRead.ChecksumMismatch.class);
    assertThat(store.read(csv(CSV.length - 1, sha256(CSV))))
        .isInstanceOf(SourceRead.ChecksumMismatch.class);
  }

  @Test
  void manifestIsReadWhole() {
    assertThat(
            new String(
                store.read(new ManifestLocation(BUCKET, "runs/run_101/manifest.json", null), 1024),
                StandardCharsets.UTF_8))
        .isEqualTo("{\"id\": \"m\"}");
  }

  // U-SRC-02, the manifest
  @Test
  void manifestOverOneMebibyteIsTooLarge() {
    assertThatThrownBy(
            () ->
                store.read(
                    new ManifestLocation(BUCKET, "runs/run_102/manifest.json", null),
                    Manifest.MAX_BYTES))
        .isInstanceOfSatisfying(
            PermanentFailureException.class,
            failure -> assertThat(failure.code()).isEqualTo("SOURCE_TOO_LARGE"));
  }

  // U-SRC-03
  @Test
  void missingObjectIsSourceNotFound() {
    assertThatThrownBy(
            () -> store.read(new ManifestLocation(BUCKET, "runs/missing/manifest.json", null), 10))
        .isInstanceOfSatisfying(
            PermanentFailureException.class,
            failure -> assertThat(failure.code()).isEqualTo("SOURCE_NOT_FOUND"));
  }

  private static void put(String key, byte[] content) {
    s3.putObject(request -> request.bucket(BUCKET).key(key), RequestBody.fromBytes(content));
  }

  private static ManifestFile csv(long size, String sha256) {
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
}

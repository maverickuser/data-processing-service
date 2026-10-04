package com.bondplatform.dataprocessing.canonical.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.canonical.application.CanonicalFile;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.source.adapter.aws.S3Mock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Storage of a run's canonical file against an S3-compatible server (LLD section 17.5). */
@Testcontainers(disabledWithoutDocker = true)
class S3CanonicalFileStoreIT {

  private static final String BUCKET = "data-processing-service-canonical";

  private static S3Client s3;

  @TempDir Path workDirectory;

  @BeforeAll
  static void createBucket() {
    s3 = S3Mock.client();
    s3.createBucket(request -> request.bucket(BUCKET));
  }

  @AfterAll
  static void closeClient() {
    s3.close();
  }

  @Test
  void goldenRowsAreStoredAsOneJsonLinesObject() {
    String key;
    try (CanonicalFile file =
        new S3CanonicalFileStore(s3, BUCKET, workDirectory).create(GoldenBhavcopy.RUN)) {
      GoldenBhavcopy.rows().forEach(file::append);
      key = file.commit();
    }

    ResponseBytes<GetObjectResponse> stored =
        s3.getObjectAsBytes(request -> request.bucket(BUCKET).key(key));
    assertThat(stored.response().contentType()).isEqualTo("application/x-ndjson");
    assertThat(stored.asString(StandardCharsets.UTF_8).lines())
        .hasSize(8)
        .allSatisfy(line -> assertThat(line).startsWith("{\"jobId\":"));
  }
}

package com.bondplatform.dataprocessing.canonical.adapter.aws;

import com.bondplatform.dataprocessing.canonical.adapter.json.CanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.adapter.json.JsonCanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.application.CanonicalFile;
import com.bondplatform.dataprocessing.canonical.application.CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Keeps canonical files in the service's own S3 bucket (LLD section 17.5).
 *
 * <p>Lines are written to a local temporary file first, so memory does not grow with the file, and
 * the file is uploaded in one request on commit. A failed upload is {@value #UNAVAILABLE}, a
 * temporary failure; no message from S3 is passed on. A local disk failure is not expected and
 * surfaces as {@link UncheckedIOException}.
 *
 * <p>The upload is a single PUT on the shared S3 client, so it is bound by that client's timeouts
 * and by the 5 GB limit of one request. A bhavcopy's canonical file is a few megabytes; a larger
 * source would need a multipart upload and a longer timeout.
 */
public class S3CanonicalFileStore implements CanonicalFileStore {

  /** The failure code of an upload that did not succeed. */
  static final String UNAVAILABLE = "CANONICAL_STORE_UNAVAILABLE";

  static final String CONTENT_TYPE = "application/x-ndjson";

  private final S3Client s3;
  private final String bucket;
  private final Path workDirectory;

  /** Creates a store writing to {@code bucket}, with temporary files in {@code workDirectory}. */
  public S3CanonicalFileStore(S3Client s3, String bucket, Path workDirectory) {
    this.s3 = s3;
    this.bucket = bucket;
    this.workDirectory = workDirectory;
  }

  @Override
  public CanonicalFile create(CanonicalRun run) {
    try {
      Path path = Files.createTempFile(workDirectory, "canonical-", ".jsonl");
      try {
        return new LocalFile(run, path, Files.newBufferedWriter(path, StandardCharsets.UTF_8));
      } catch (IOException e) {
        Files.deleteIfExists(path);
        throw e;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Builds the JSON request's canonical file in memory and uploads it in one request. */
  @Override
  public String storeJson(JsonCanonicalRun run, List<JsonFileEvidence> files) {
    StringBuilder content = new StringBuilder();
    files.forEach(file -> content.append(JsonCanonicalLineWriter.line(run, file)).append('\n'));
    String key = run.objectKey();
    try {
      s3.putObject(
          request -> request.bucket(bucket).key(key).contentType(CONTENT_TYPE),
          RequestBody.fromString(content.toString(), StandardCharsets.UTF_8));
    } catch (SdkException e) {
      throw new TemporaryFailureException(UNAVAILABLE, "The canonical file could not be stored", e);
    }
    return key;
  }

  /** A canonical file buffered on local disk until it is committed. */
  private final class LocalFile implements CanonicalFile {

    private final CanonicalRun run;
    private final Path path;
    private final BufferedWriter writer;

    LocalFile(CanonicalRun run, Path path, BufferedWriter writer) {
      this.run = run;
      this.path = path;
      this.writer = writer;
    }

    @Override
    public void append(CanonicalRow row) {
      try {
        writer.write(CanonicalLineWriter.line(run, row));
        writer.write('\n');
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public String commit() {
      String key = run.objectKey();
      try {
        writer.close();
        s3.putObject(
            request -> request.bucket(bucket).key(key).contentType(CONTENT_TYPE),
            RequestBody.fromFile(path));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (SdkException e) {
        throw new TemporaryFailureException(
            UNAVAILABLE, "The canonical file could not be stored", e);
      }
      return key;
    }

    @Override
    public void close() {
      try (BufferedWriter closing = writer) {
        Files.deleteIfExists(path);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}

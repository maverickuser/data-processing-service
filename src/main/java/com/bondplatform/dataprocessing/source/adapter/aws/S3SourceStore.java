package com.bondplatform.dataprocessing.source.adapter.aws;

import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.source.application.ManifestSource;
import com.bondplatform.dataprocessing.source.application.SourceObjectReader;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceProblem;
import com.bondplatform.dataprocessing.source.domain.SourceRead;
import com.bondplatform.dataprocessing.source.domain.VerifiedSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Reads manifests and listed files from S3, only from the buckets the service is allowed to read
 * (LLD section 19), and never more than the expected number of bytes.
 *
 * <p>A missing object, or one S3 refuses to show (S3 answers 403 for a missing key when listing is
 * not allowed), is {@code SOURCE_NOT_FOUND}, permanent. Timeouts, throttling, and server errors are
 * {@code SOURCE_UNAVAILABLE}, temporary. No message from S3 is passed on, since it can name the
 * account and request.
 */
public class S3SourceStore implements ManifestSource, SourceObjectReader {

  private static final String SOURCE_NOT_FOUND = SourceProblem.Code.SOURCE_NOT_FOUND.name();
  private static final String SOURCE_UNAVAILABLE = SourceProblem.Code.SOURCE_UNAVAILABLE.name();

  private static final int BUFFER_BYTES = 64 * 1024;

  /** Client errors that pass on their own: an expiring credential, clock skew, a slow upload. */
  private static final Set<String> PASSING_ERROR_CODES =
      Set.of(
          "RequestTimeout",
          "RequestTimeTooSkewed",
          "ExpiredToken",
          "TokenRefreshRequired",
          "InvalidToken");

  private final S3Client s3;
  private final Set<String> allowedBuckets;

  /**
   * Creates the store.
   *
   * @param allowedBuckets the only buckets read from
   */
  public S3SourceStore(S3Client s3, Set<String> allowedBuckets) {
    this.s3 = s3;
    this.allowedBuckets = Set.copyOf(allowedBuckets);
  }

  @Override
  public byte[] read(ManifestLocation location, long maxBytes) {
    requireAllowed(location.bucket());
    try (ResponseInputStream<GetObjectResponse> object =
        get(location.bucket(), location.key(), location.versionId())) {
      Long length = object.response().contentLength();
      if (length != null && length > maxBytes) {
        object.abort();
        throw tooLarge(length, maxBytes);
      }
      Content content = readAtMost(object, maxBytes);
      if (content.bytes().length > maxBytes) {
        object.abort();
        throw tooLarge(content.bytes().length, maxBytes);
      }
      return content.bytes();
    } catch (IOException e) {
      throw unavailable(e);
    }
  }

  @Override
  public SourceRead read(ManifestFile file) {
    requireAllowed(file.bucket());
    try (ResponseInputStream<GetObjectResponse> object = get(file.bucket(), file.key(), null)) {
      Long length = object.response().contentLength();
      if (length != null && length != file.sizeBytes()) {
        object.abort();
        return mismatch(file, "size " + length + " bytes, listed as " + file.sizeBytes());
      }
      Content content = readAtMost(object, file.sizeBytes());
      if (content.bytes().length != file.sizeBytes()) {
        object.abort();
        return mismatch(
            file,
            "size "
                + (content.bytes().length > file.sizeBytes()
                    ? "more than " + file.sizeBytes()
                    : content.bytes().length)
                + " bytes, listed as "
                + file.sizeBytes());
      }
      if (!content.sha256().equals(file.sha256())) {
        return mismatch(file, "SHA-256 " + content.sha256() + ", listed as " + file.sha256());
      }
      Instant lastModified = object.response().lastModified();
      return new SourceRead.Verified(
          new VerifiedSource(file, content.bytes(), lastModified, object.response().versionId()));
    } catch (IOException e) {
      throw unavailable(e);
    }
  }

  private ResponseInputStream<GetObjectResponse> get(
      String bucket, String key, @Nullable String versionId) {
    try {
      return s3.getObject(request -> request.bucket(bucket).key(key).versionId(versionId));
    } catch (S3Exception e) {
      if (e.statusCode() >= 500
          || e.statusCode() == 429
          || PASSING_ERROR_CODES.contains(errorCodeOf(e))) {
        throw unavailable(e);
      }
      throw new PermanentFailureException(
          SOURCE_NOT_FOUND,
          "Object "
              + key.substring(key.lastIndexOf('/') + 1)
              + " could not be read: "
              + errorCodeOf(e)
              + " (HTTP "
              + e.statusCode()
              + ")");
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  /** Reads up to one byte more than the limit, so a longer object is noticed without reading it. */
  private static Content readAtMost(InputStream in, long maxBytes) throws IOException {
    MessageDigest digest = sha256();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[BUFFER_BYTES];
    long remaining = maxBytes + 1;
    int read;
    while (remaining > 0
        && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
      out.write(buffer, 0, read);
      digest.update(buffer, 0, read);
      remaining -= read;
    }
    return new Content(out.toByteArray(), HexFormat.of().formatHex(digest.digest()));
  }

  private void requireAllowed(String bucket) {
    if (!allowedBuckets.contains(bucket)) {
      throw new PermanentFailureException(
          SourceProblem.Code.INVALID_MANIFEST.name(),
          "The manifest names a bucket the service does not read from");
    }
  }

  private static SourceRead mismatch(ManifestFile file, String detail) {
    return new SourceRead.ChecksumMismatch(file, file.fileName() + ": " + detail);
  }

  private static PermanentFailureException tooLarge(long size, long maxBytes) {
    return new PermanentFailureException(
        SourceProblem.Code.SOURCE_TOO_LARGE.name(),
        "The manifest is larger than " + maxBytes + " bytes (at least " + size + ")");
  }

  private static TemporaryFailureException unavailable(Exception cause) {
    String kind =
        cause instanceof S3Exception s3
            ? errorCodeOf(s3) + " (HTTP " + s3.statusCode() + ")"
            : cause.getClass().getSimpleName();
    return new TemporaryFailureException(
        SOURCE_UNAVAILABLE, "S3 could not be read: " + kind, cause);
  }

  private static String errorCodeOf(S3Exception e) {
    return e.awsErrorDetails() == null
        ? "unknown"
        : String.valueOf(e.awsErrorDetails().errorCode());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime provides SHA-256", e);
    }
  }

  /** What was read, with its SHA-256. */
  private static final class Content {

    private final byte[] bytes;
    private final String sha256;

    Content(byte[] bytes, String sha256) {
      this.bytes = bytes;
      this.sha256 = sha256;
    }

    byte[] bytes() {
      return bytes;
    }

    String sha256() {
      return sha256;
    }
  }
}

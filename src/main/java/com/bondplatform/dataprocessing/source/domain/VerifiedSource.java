package com.bondplatform.dataprocessing.source.domain;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A listed file whose content was read in full and matched the manifest's size and SHA-256.
 *
 * <p>The content stays in memory: listed files are at most 10 MiB together (LLD section 1).
 */
public final class VerifiedSource {

  private final ManifestFile file;
  private final byte[] content;
  private final @Nullable Instant lastModified;
  private final @Nullable String versionId;

  /**
   * Creates the source; the content is not copied, so the caller must not change it afterwards.
   *
   * @param lastModified when the object was last written, as storage reports it
   * @param versionId the object version read, when storage versions objects
   */
  public VerifiedSource(
      ManifestFile file,
      byte[] content,
      @Nullable Instant lastModified,
      @Nullable String versionId) {
    this.file = file;
    this.content = content;
    this.lastModified = lastModified;
    this.versionId = versionId;
  }

  /** Returns the manifest entry this content was read for. */
  public ManifestFile file() {
    return file;
  }

  /** Returns a new stream over the content. */
  public InputStream open() {
    return new ByteArrayInputStream(content);
  }

  /** Returns the content's size in bytes. */
  public long size() {
    return content.length;
  }

  /** Returns when the object was last written, if storage reported it. */
  public Optional<Instant> lastModified() {
    return Optional.ofNullable(lastModified);
  }

  /** Returns the object version read, if storage versions objects. */
  public Optional<String> versionId() {
    return Optional.ofNullable(versionId);
  }
}

package com.bondplatform.dataprocessing.outbox.adapter.config;

import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where outbox events are sent.
 *
 * @param region the AWS region of both queues
 * @param endpoint an SQS endpoint to use instead of AWS's, for local testing only; null in AWS
 * @param fileProcessingUrl the URL of this service's FIFO job queue
 * @param securityDetailsUrl the URL of the externally owned security-details queue (LLD 14.2)
 */
@ConfigurationProperties("data-processing.queues")
public record QueueProperties(
    String region, @Nullable URI endpoint, URI fileProcessingUrl, URI securityDetailsUrl) {

  /**
   * Checks that the region and both queue URLs are given, and that the job queue is FIFO.
   *
   * @throws IllegalArgumentException if one is missing, or the job queue URL does not end in {@code
   *     .fifo}
   */
  public QueueProperties(
      @Nullable String region,
      @Nullable URI endpoint,
      @Nullable URI fileProcessingUrl,
      @Nullable URI securityDetailsUrl) {
    if (region == null || region.isBlank()) {
      throw new IllegalArgumentException("data-processing.queues.region is required");
    }
    this.region = region;
    this.endpoint = endpoint;
    this.fileProcessingUrl = required(fileProcessingUrl, "file-processing-url");
    if (!this.fileProcessingUrl.toString().endsWith(".fifo")) {
      // Job order depends on FIFO message groups; a standard queue would lose it silently.
      throw new IllegalArgumentException(
          "data-processing.queues.file-processing-url must be a FIFO queue, ending in .fifo");
    }
    this.securityDetailsUrl = required(securityDetailsUrl, "security-details-url");
  }

  /** Returns the URL of the queue for a destination. */
  public URI urlOf(OutboxDestination destination) {
    return switch (destination) {
      case FILE_PROCESSING -> fileProcessingUrl;
      case SECURITY_DETAILS -> securityDetailsUrl;
    };
  }

  private static URI required(@Nullable URI url, String name) {
    if (url == null || url.toString().isBlank()) {
      throw new IllegalArgumentException("data-processing.queues." + name + " is required");
    }
    return url;
  }
}

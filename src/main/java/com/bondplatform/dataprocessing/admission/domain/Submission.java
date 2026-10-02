package com.bondplatform.dataprocessing.admission.domain;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A submission that has passed every admission rule: the CloudEvent a producer sent, in typed form.
 *
 * @param eventId the CloudEvent {@code id}, unique within the source
 * @param eventSource the CloudEvent {@code source}
 * @param dataset the dataset contract the manifest must be processed under
 * @param time the CloudEvent {@code time}
 * @param subject the CloudEvent {@code subject}, already checked against the inputs
 * @param runId the producer's delivery run, equal to the idempotency key
 * @param fetchEventId the producer's original trigger identity
 * @param inputs what the run was about
 * @param manifest where the manifest is
 * @param datasetFingerprint the producer's fingerprint of the dataset
 */
public record Submission(
    String eventId,
    String eventSource,
    DatasetUrn dataset,
    Instant time,
    String subject,
    String runId,
    String fetchEventId,
    Inputs inputs,
    ManifestReference manifest,
    String datasetFingerprint) {

  /** Returns the group this submission's job runs in. */
  public OrderingGroup orderingGroup() {
    return switch (inputs) {
      case Inputs.Bse bse -> OrderingGroup.forTradeDate(bse.tradeDate());
      case Inputs.Nsdl nsdl -> OrderingGroup.forIsin(nsdl.isin());
    };
  }

  /** What a fetch run was asked for; its kind follows the dataset. */
  public sealed interface Inputs {

    /** A day's bhavcopy from one exchange. */
    record Bse(ExchangeName exchangeName, TradeDate tradeDate) implements Inputs {}

    /** The details of one security. */
    record Nsdl(Isin isin) implements Inputs {}
  }

  /**
   * The exact, immutable S3 object holding the manifest; never a prefix.
   *
   * @param versionId the S3 version to read, when the producer pinned one
   */
  public record ManifestReference(String bucket, String key, @Nullable String versionId) {}
}

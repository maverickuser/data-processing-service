package com.bondplatform.dataprocessing.source.domain;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks that a manifest describes the run that was submitted, and that its files can be processed
 * (LLD sections 1, 2.1, 13.1).
 *
 * <p>The manifest and the submission are separate events with their own IDs and times, so those are
 * not compared. Everything else that both carry must agree: dataset, subject, event type, the fetch
 * trigger's event ID, run ID, inputs, and dataset fingerprint.
 */
public final class ManifestVerifier {

  /** The largest CSV, and the largest total of a request's JSON files, in bytes (LLD section 1). */
  public static final long MAX_SOURCE_BYTES = 10_485_760;

  private ManifestVerifier() {}

  /**
   * Returns the first reason the manifest cannot be processed for this submission, or empty.
   *
   * @param expectedFormat the format the dataset's source contract reads
   */
  public static Optional<SourceProblem> verify(
      Manifest manifest, SubmittedRun submitted, SourceFormat expectedFormat) {
    List<String> disagreements = disagreements(manifest, submitted);
    if (!disagreements.isEmpty()) {
      return Optional.of(
          new SourceProblem(
              SourceProblem.Code.MANIFEST_MISMATCH,
              "The manifest disagrees with the submission on " + String.join(", ", disagreements)));
    }
    return fileSetProblem(manifest.files(), expectedFormat);
  }

  private static List<String> disagreements(Manifest manifest, SubmittedRun submitted) {
    List<String> fields = new ArrayList<>();
    compare(fields, "dataschema", manifest.dataset(), submitted.dataset());
    compare(fields, "subject", manifest.subject(), submitted.subject());
    compare(fields, "data.event_type", manifest.eventType(), submitted.eventType());
    compare(fields, "data.event_id", manifest.fetchEventId(), submitted.fetchEventId());
    compare(fields, "data.run_id", manifest.runId(), submitted.runId());
    compare(fields, "data.inputs", manifest.inputs(), submitted.inputs());
    compare(
        fields,
        "data.dataset_fingerprint",
        manifest.datasetFingerprint(),
        submitted.datasetFingerprint());
    return fields;
  }

  private static void compare(List<String> fields, String name, Object manifest, Object submitted) {
    if (!manifest.equals(submitted)) {
      fields.add(name);
    }
  }

  private static Optional<SourceProblem> fileSetProblem(
      List<ManifestFile> files, SourceFormat expectedFormat) {
    if (files.stream().anyMatch(file -> file.format() != expectedFormat)) {
      return invalid("Every listed file must be " + expectedFormat);
    }
    if (expectedFormat == SourceFormat.CSV && files.size() != 1) {
      return invalid("A CSV manifest must list exactly one file, not " + files.size());
    }
    long total = files.stream().mapToLong(ManifestFile::sizeBytes).sum();
    if (total > MAX_SOURCE_BYTES) {
      return Optional.of(
          new SourceProblem(
              SourceProblem.Code.SOURCE_TOO_LARGE,
              "The listed files total "
                  + total
                  + " bytes, more than the limit of "
                  + MAX_SOURCE_BYTES));
    }
    return Optional.empty();
  }

  private static Optional<SourceProblem> invalid(String detail) {
    return Optional.of(new SourceProblem(SourceProblem.Code.INVALID_MANIFEST, detail));
  }

  /**
   * What the stored submission says the manifest must describe.
   *
   * @param fetchEventId the submission's {@code data.event_id}
   * @param inputs the submission's {@code data.inputs}
   */
  public record SubmittedRun(
      DatasetUrn dataset,
      String subject,
      String eventType,
      String fetchEventId,
      String runId,
      Map<String, String> inputs,
      String datasetFingerprint) {

    /** Copies the inputs. */
    public SubmittedRun {
      inputs = Map.copyOf(inputs);
    }
  }
}

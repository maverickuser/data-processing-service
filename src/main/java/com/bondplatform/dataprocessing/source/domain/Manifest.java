package com.bondplatform.dataprocessing.source.domain;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import java.util.List;
import java.util.Map;

/**
 * The fetch service's manifest of one run: a CloudEvent describing the dataset and listing every
 * file to process (LLD section 19, fetch-service LLD section 11).
 *
 * @param id the manifest CloudEvent's {@code id}; distinct from the submission's
 * @param fetchEventId {@code data.event_id}, the fetch trigger's identity
 * @param inputs {@code data.inputs}: text values only
 * @param files every listed file, in manifest order; never empty
 */
public record Manifest(
    String id,
    DatasetUrn dataset,
    String subject,
    String eventType,
    String fetchEventId,
    String runId,
    Map<String, String> inputs,
    String datasetFingerprint,
    List<ManifestFile> files) {

  /** The largest manifest object read, in bytes (LLD section 1). */
  public static final long MAX_BYTES = 1_048_576;

  /** Copies the inputs and files so the manifest is immutable. */
  public Manifest {
    inputs = Map.copyOf(inputs);
    files = List.copyOf(files);
  }
}

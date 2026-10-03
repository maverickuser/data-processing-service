package com.bondplatform.dataprocessing.source.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds manifests as parsed JSON, in the shape the fetch service writes them, for tests. */
final class Manifests {

  static final String HASH = "a".repeat(64);

  private Manifests() {}

  /** Returns an NSDL manifest for run_202 listing the given JSON file names. */
  static Map<String, Object> nsdl(String... fileNames) {
    Map<String, Object> inputs = new LinkedHashMap<>();
    inputs.put("isin_code", "INE121A07QY9");
    List<Object> files = new ArrayList<>();
    for (String name : fileNames) {
      files.add(file("isin-details", "runs/run_202/raw/isin-details/1/" + name, "json", 1234));
    }
    return event(
        "urn:bond-platform:dataset:nsdl-security",
        "isin/INE121A07QY9",
        "nsdl-bond-data",
        inputs,
        files);
  }

  /** Returns a BSE manifest for run_101 listing one CSV. */
  static Map<String, Object> bse() {
    Map<String, Object> inputs = new LinkedHashMap<>();
    inputs.put("exchangeName", "BSE");
    inputs.put("tradeDate", "2026-09-21");
    List<Object> files = new ArrayList<>();
    files.add(
        file(
            "debt-bhavcopy",
            "runs/run_101/raw/debt-bhavcopy/1/BSE_fgroup21092026.csv",
            "csv",
            99_424));
    return event(
        "urn:bond-platform:dataset:bse-debt-trades",
        "exchange/BSE/trade-date/2026-09-21",
        "daily-bhavcopy",
        inputs,
        files);
  }

  /** Returns one file entry. */
  static Map<String, Object> file(String jobId, String key, String format, long size) {
    Map<String, Object> file = new LinkedHashMap<>();
    file.put("job_id", jobId);
    file.put("bucket", "data-fetch-service-artifacts");
    file.put("key", key);
    file.put("format", format);
    file.put("sha256", HASH);
    file.put("size_bytes", size);
    file.put("source_url", "https://example.invalid/" + jobId);
    return file;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> data(Map<String, Object> manifest) {
    return (Map<String, Object>) Objects.requireNonNull(manifest.get("data"));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> firstFile(Map<String, Object> manifest) {
    return (Map<String, Object>)
        ((List<Object>) Objects.requireNonNull(data(manifest).get("files"))).get(0);
  }

  private static Map<String, Object> event(
      String dataset,
      String subject,
      String eventType,
      Map<String, Object> inputs,
      List<Object> files) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schema_version", 1);
    data.put("event_type", eventType);
    data.put("event_id", "evt_1");
    data.put("run_id", dataset.endsWith("security") ? "run_202" : "run_101");
    data.put("inputs", inputs);
    data.put("config_revision", "sha256:config");
    data.put("dataset_fingerprint", "sha256:" + "b".repeat(64));
    data.put("files", files);
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("specversion", "1.0");
    event.put("id", "urn:bond-platform:manifest:" + data.get("run_id"));
    event.put("source", "urn:bond-platform:service:data-fetch-service");
    event.put("type", "com.bondplatform.dataset.manifest.v1");
    event.put("dataschema", dataset);
    event.put("time", "2026-09-27T14:30:00Z");
    event.put("subject", subject);
    event.put("datacontenttype", "application/json");
    event.put("data", data);
    return event;
  }
}

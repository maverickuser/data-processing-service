package com.bondplatform.dataprocessing.admission;

import com.bondplatform.dataprocessing.admission.domain.Submission;
import com.bondplatform.dataprocessing.admission.domain.SubmissionResult;
import com.bondplatform.dataprocessing.admission.domain.SubmissionValidator;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Builds valid submission events, and their typed form, for tests. */
public final class SubmissionEvents {

  private static final DatasetUrn BSE = new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades");
  private static final DatasetUrn NSDL = new DatasetUrn("urn:bond-platform:dataset:nsdl-security");
  private static final SubmissionValidator VALIDATOR = new SubmissionValidator(Set.of(BSE, NSDL));

  private SubmissionEvents() {}

  /** Returns a valid NSDL event for the given run and ISIN, as mutable parsed JSON. */
  public static Map<String, Object> nsdl(String runId, String isin) {
    Map<String, Object> inputs = new LinkedHashMap<>();
    inputs.put("isin_code", isin);
    return event(runId, NSDL, "isin/" + isin, "nsdl-bond-data", inputs);
  }

  /** Returns a valid BSE event for the given run and trade date, as mutable parsed JSON. */
  public static Map<String, Object> bse(String runId, String tradeDate) {
    Map<String, Object> inputs = new LinkedHashMap<>();
    inputs.put("exchangeName", "BSE");
    inputs.put("tradeDate", tradeDate);
    return event(runId, BSE, "exchange/BSE/trade-date/" + tradeDate, "daily-bhavcopy", inputs);
  }

  /** Returns the typed submission for a valid event whose key is its run ID. */
  public static Submission submissionOf(Map<String, Object> event) {
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) event.get("data");
    SubmissionResult result = VALIDATOR.validate(event, String.valueOf(data.get("run_id")));
    if (result instanceof SubmissionResult.Valid valid) {
      return valid.submission();
    }
    throw new IllegalArgumentException("Not a valid submission event: " + result);
  }

  private static Map<String, Object> event(
      String runId,
      DatasetUrn dataset,
      String subject,
      String eventType,
      Map<String, Object> inputs) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("bucket", "data-fetch-service-artifacts");
    manifest.put("key", "runs/" + runId + "/manifest.json");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schema_version", 1);
    data.put("event_type", eventType);
    data.put("event_id", "evt_" + runId);
    data.put("run_id", runId);
    data.put("inputs", inputs);
    data.put("manifest", manifest);
    data.put("dataset_fingerprint", "sha256:" + "a".repeat(64));
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("specversion", "1.0");
    event.put("id", "urn:bond-platform:submission:" + runId);
    event.put("source", "urn:bond-platform:service:data-fetch-service");
    event.put("type", "com.bondplatform.dataset.manifest.v1");
    event.put("dataschema", dataset.value());
    event.put("time", "2026-09-27T14:30:00Z");
    event.put("subject", subject);
    event.put("datacontenttype", "application/json");
    event.put("data", data);
    return event;
  }
}

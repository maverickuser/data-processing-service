package com.bondplatform.dataprocessing.admission.domain;

import com.bondplatform.dataprocessing.admission.domain.Submission.Inputs;
import com.bondplatform.dataprocessing.admission.domain.Submission.ManifestReference;
import com.bondplatform.dataprocessing.admission.domain.SubmissionError.Code;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks a submission against the admission rules of the submission API (LLD section 2.1 and the
 * OpenAPI document) and turns it into a typed {@link Submission}.
 *
 * <p>The input is the parsed JSON body as plain maps, lists, strings, numbers, and booleans, so
 * this class knows nothing about the JSON library. It reports every fault it finds, each with a
 * JSON Pointer or header name, rather than stopping at the first. It never reads S3: whether the
 * manifest exists and agrees with the submission is checked later by the worker.
 */
public final class SubmissionValidator {

  /** The header carrying the producer's delivery run ID. */
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private static final String SPEC_VERSION = "1.0";
  private static final String FETCH_SERVICE = "urn:bond-platform:service:data-fetch-service";
  private static final String MANIFEST_EVENT_TYPE = "com.bondplatform.dataset.manifest.v1";
  private static final String JSON_CONTENT = "application/json";
  private static final DatasetUrn BSE_DATASET =
      new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades");
  private static final String BSE_EVENT_TYPE = "daily-bhavcopy";
  private static final String NSDL_EVENT_TYPE = "nsdl-bond-data";
  private static final String BSE = "BSE";
  private static final int MAX_RUN_ID_LENGTH = 128;
  private static final int MAX_KEY_BYTES = 1024;
  private static final Set<String> ENVELOPE_ATTRIBUTES =
      Set.of(
          "specversion",
          "id",
          "source",
          "type",
          "dataschema",
          "time",
          "subject",
          "datacontenttype",
          "data");
  private static final Set<String> DATA_PROPERTIES =
      Set.of(
          "schema_version",
          "event_type",
          "event_id",
          "run_id",
          "inputs",
          "manifest",
          "dataset_fingerprint");
  private static final Pattern EXTENSION_NAME = Pattern.compile("[a-z0-9]+");
  private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

  private final Set<DatasetUrn> supportedDatasets;

  /** Creates a validator that accepts the given datasets. */
  public SubmissionValidator(Set<DatasetUrn> supportedDatasets) {
    this.supportedDatasets = Set.copyOf(supportedDatasets);
  }

  /**
   * Validates a parsed submission body and its idempotency key.
   *
   * @param event the CloudEvent as parsed JSON
   * @param idempotencyKey the {@code Idempotency-Key} header, or null when absent
   */
  public SubmissionResult validate(Map<String, Object> event, @Nullable String idempotencyKey) {
    Check check = new Check();
    Envelope envelope = envelope(event, check);
    Payload payload = payload(check.object(event, "", "data"), envelope, idempotencyKey, check);
    if (!check.errors.isEmpty()) {
      return new SubmissionResult.Invalid(check.errors);
    }
    // With no fault recorded, every part was read successfully.
    return new SubmissionResult.Valid(
        new Submission(
            Objects.requireNonNull(envelope.eventId()),
            FETCH_SERVICE,
            Objects.requireNonNull(envelope.dataset()),
            Objects.requireNonNull(envelope.time()),
            Objects.requireNonNull(envelope.subject()),
            Objects.requireNonNull(payload.runId()),
            Objects.requireNonNull(payload.fetchEventId()),
            Objects.requireNonNull(payload.inputs()),
            Objects.requireNonNull(payload.manifest()),
            Objects.requireNonNull(payload.fingerprint())));
  }

  /** Reads the CloudEvent attributes; a part that is missing or wrong is null and recorded. */
  private Envelope envelope(Map<String, Object> event, Check check) {
    check.constant(event, "", "specversion", SPEC_VERSION);
    check.constant(event, "", "source", FETCH_SERVICE);
    check.constant(event, "", "type", MANIFEST_EVENT_TYPE);
    check.constant(event, "", "datacontenttype", JSON_CONTENT);
    extensions(event, check);
    return new Envelope(
        check.text(event, "", "id"),
        dataset(event, check),
        time(event, check),
        check.text(event, "", "subject"));
  }

  /** Reads the event's data; a part that is missing or wrong is null and recorded. */
  private static Payload payload(
      Map<String, Object> data, Envelope envelope, @Nullable String idempotencyKey, Check check) {
    check.onlyProperties(data, "/data", DATA_PROPERTIES);
    schemaVersion(data, check);
    String fingerprint = check.text(data, "/data", "dataset_fingerprint");
    if (fingerprint != null && !FINGERPRINT.matcher(fingerprint).matches()) {
      check.fault(
          "/data/dataset_fingerprint", Code.INVALID_VALUE, "Expected sha256: and 64 hex digits.");
    }
    DatasetUrn dataset = envelope.dataset();
    return new Payload(
        runId(data, idempotencyKey, check),
        check.text(data, "/data", "event_id"),
        dataset == null ? null : inputs(data, dataset, envelope.subject(), check),
        manifest(data, check),
        fingerprint);
  }

  private record Envelope(
      @Nullable String eventId,
      @Nullable DatasetUrn dataset,
      @Nullable Instant time,
      @Nullable String subject) {}

  private record Payload(
      @Nullable String runId,
      @Nullable String fetchEventId,
      @Nullable Inputs inputs,
      @Nullable ManifestReference manifest,
      @Nullable String fingerprint) {}

  private @Nullable DatasetUrn dataset(Map<String, Object> event, Check check) {
    String value = check.text(event, "", "dataschema");
    if (value == null) {
      return null;
    }
    return supportedDatasets.stream()
        .filter(dataset -> dataset.value().equals(value))
        .findFirst()
        .orElseGet(
            () -> {
              check.fault(
                  "/dataschema", Code.UNSUPPORTED_VALUE, "Not a dataset this service accepts.");
              return null;
            });
  }

  private static @Nullable Instant time(Map<String, Object> event, Check check) {
    String value = check.text(event, "", "time");
    if (value == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      check.fault("/time", Code.INVALID_VALUE, "Expected an RFC 3339 timestamp.");
      return null;
    }
  }

  /** CloudEvents extension attributes: lowercase alphanumeric names, simple values. */
  private static void extensions(Map<String, Object> event, Check check) {
    for (Map.Entry<String, Object> attribute : event.entrySet()) {
      String name = attribute.getKey();
      if (ENVELOPE_ATTRIBUTES.contains(name)) {
        continue;
      }
      Object value = attribute.getValue();
      boolean simple = value instanceof String || value instanceof Boolean || isWholeNumber(value);
      if (!EXTENSION_NAME.matcher(name).matches() || !simple) {
        check.fault(
            "/" + name,
            Code.UNKNOWN_PROPERTY,
            "Extension attributes need lowercase alphanumeric names and string, integer, or"
                + " boolean values.");
      }
    }
  }

  private static void schemaVersion(Map<String, Object> data, Check check) {
    Object value = data.get("schema_version");
    if (value == null) {
      check.fault("/data/schema_version", Code.REQUIRED, "Is required.");
    } else if (!isWholeNumber(value)
        || new BigDecimal(value.toString()).compareTo(BigDecimal.ONE) != 0) {
      check.fault("/data/schema_version", Code.UNSUPPORTED_VALUE, "Expected 1.");
    }
  }

  private static @Nullable String runId(
      Map<String, Object> data, @Nullable String idempotencyKey, Check check) {
    String runId = check.text(data, "/data", "run_id");
    if (runId != null && runId.length() > MAX_RUN_ID_LENGTH) {
      check.fault("/data/run_id", Code.INVALID_VALUE, "Expected at most 128 characters.");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      check.errors.add(
          SubmissionError.inHeader(IDEMPOTENCY_KEY_HEADER, Code.REQUIRED, "Is required."));
    } else if (runId != null && !idempotencyKey.equals(runId)) {
      check.errors.add(
          SubmissionError.inHeader(
              IDEMPOTENCY_KEY_HEADER, Code.MISMATCH, "Must equal data.run_id."));
    }
    return runId;
  }

  private static @Nullable ManifestReference manifest(Map<String, Object> data, Check check) {
    Map<String, Object> manifest = check.object(data, "/data", "manifest");
    check.onlyProperties(manifest, "/data/manifest", Set.of("bucket", "key", "version_id"));
    String bucket = check.text(manifest, "/data/manifest", "bucket");
    if (bucket != null && (bucket.length() < 3 || bucket.length() > 63)) {
      check.fault("/data/manifest/bucket", Code.INVALID_VALUE, "Expected 3 to 63 characters.");
    }
    String key = check.text(manifest, "/data/manifest", "key");
    if (key != null && key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
      check.fault("/data/manifest/key", Code.INVALID_VALUE, "Expected at most 1024 UTF-8 bytes.");
    }
    String versionId =
        manifest.containsKey("version_id")
            ? check.text(manifest, "/data/manifest", "version_id")
            : null;
    return bucket == null || key == null ? null : new ManifestReference(bucket, key, versionId);
  }

  private static @Nullable Inputs inputs(
      Map<String, Object> data, DatasetUrn dataset, @Nullable String subject, Check check) {
    boolean bse = dataset.equals(BSE_DATASET);
    check.constant(data, "/data", "event_type", bse ? BSE_EVENT_TYPE : NSDL_EVENT_TYPE);
    Map<String, Object> inputs = check.object(data, "/data", "inputs");
    return bse ? bseInputs(inputs, subject, check) : nsdlInputs(inputs, subject, check);
  }

  private static @Nullable Inputs bseInputs(
      Map<String, Object> inputs, @Nullable String subject, Check check) {
    check.onlyProperties(inputs, "/data/inputs", Set.of("exchangeName", "tradeDate"));
    check.constant(inputs, "/data/inputs", "exchangeName", BSE);
    String dateText = check.text(inputs, "/data/inputs", "tradeDate");
    if (dateText == null) {
      return null;
    }
    TradeDate tradeDate;
    try {
      tradeDate = TradeDate.parseIso(dateText);
    } catch (IllegalArgumentException e) {
      check.fault("/data/inputs/tradeDate", Code.INVALID_VALUE, "Expected a YYYY-MM-DD date.");
      return null;
    }
    if (subject != null && !subject.equals("exchange/" + BSE + "/trade-date/" + tradeDate)) {
      check.fault(
          "/subject", Code.MISMATCH, "Expected exchange/BSE/trade-date/ and the trade date.");
    }
    return new Inputs.Bse(ExchangeName.of(BSE), tradeDate);
  }

  private static @Nullable Inputs nsdlInputs(
      Map<String, Object> inputs, @Nullable String subject, Check check) {
    check.onlyProperties(inputs, "/data/inputs", Set.of("isin_code"));
    String isinText = check.text(inputs, "/data/inputs", "isin_code");
    if (isinText == null) {
      return null;
    }
    Isin isin = Isin.of(isinText);
    String prefix = "isin/";
    boolean subjectMatches =
        subject != null
            && subject.startsWith(prefix)
            && !subject.substring(prefix.length()).isBlank()
            && Isin.of(subject.substring(prefix.length())).equals(isin);
    if (subject != null && !subjectMatches) {
      check.fault("/subject", Code.MISMATCH, "Expected isin/ and the ISIN of the inputs.");
    }
    return new Inputs.Nsdl(isin);
  }

  private static boolean isWholeNumber(@Nullable Object value) {
    return value instanceof Number number
        && new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
  }

  /** Reads properties with type checks and collects every fault found. */
  private static final class Check {

    private final List<SubmissionError> errors = new ArrayList<>();

    void fault(String pointer, Code code, String message) {
      errors.add(SubmissionError.inBody(pointer, code, message));
    }

    /** Returns the non-blank text at a property, or null after recording why it is not. */
    @Nullable String text(Map<String, Object> object, String pointer, String name) {
      Object value = object.get(name);
      if (value == null) {
        fault(pointer + "/" + name, Code.REQUIRED, "Is required.");
        return null;
      }
      if (!(value instanceof String text) || text.isBlank()) {
        fault(pointer + "/" + name, Code.INVALID_VALUE, "Expected non-blank text.");
        return null;
      }
      return text;
    }

    void constant(Map<String, Object> object, String pointer, String name, String expected) {
      String value = text(object, pointer, name);
      if (value != null && !value.equals(expected)) {
        fault(pointer + "/" + name, Code.UNSUPPORTED_VALUE, "Expected " + expected + ".");
      }
    }

    /** Returns the object at a property, or an empty one after recording why it is not. */
    Map<String, Object> object(Map<String, Object> object, String pointer, String name) {
      Object value = object.get(name);
      if (value == null) {
        fault(pointer + "/" + name, Code.REQUIRED, "Is required.");
        return Map.of();
      }
      if (!(value instanceof Map<?, ?> map)) {
        fault(pointer + "/" + name, Code.INVALID_VALUE, "Expected an object.");
        return Map.of();
      }
      Map<String, Object> typed = new LinkedHashMap<>();
      map.forEach((key, entry) -> typed.put(String.valueOf(key), entry));
      return typed;
    }

    void onlyProperties(Map<String, Object> object, String pointer, Set<String> allowed) {
      object.keySet().stream()
          .filter(name -> !allowed.contains(name))
          .sorted()
          .forEach(
              name ->
                  fault(
                      pointer + "/" + name, Code.UNKNOWN_PROPERTY, "Is not an allowed property."));
    }
  }
}

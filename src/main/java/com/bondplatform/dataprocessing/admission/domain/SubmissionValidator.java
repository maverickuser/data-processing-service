package com.bondplatform.dataprocessing.admission.domain;

import com.bondplatform.dataprocessing.admission.domain.Submission.Inputs;
import com.bondplatform.dataprocessing.admission.domain.SubmissionError.Code;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks a submission against the admission rules of the submission API (LLD section 2.1 and the
 * OpenAPI document) and turns it into a typed {@link Submission}.
 *
 * <p>The input is the parsed JSON body as plain maps, lists, strings, numbers, and booleans, so
 * this class knows nothing about the JSON library. It reports every fault it can find, each with a
 * JSON Pointer or header name; the children of a missing or wrongly typed object are not reported
 * separately. Text containing the NUL character is rejected wherever it appears, because the
 * database cannot store it. It never reads S3: whether the manifest exists and agrees with the
 * submission is checked later by the worker.
 */
public final class SubmissionValidator {

  /** The header carrying the producer's delivery run ID. */
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private static final String ROOT = "";
  private static final String DATA = "/data";
  private static final String MANIFEST = "/data/manifest";
  private static final String SPEC_VERSION = "1.0";
  private static final String FETCH_SERVICE = "urn:bond-platform:service:data-fetch-service";
  private static final String MANIFEST_EVENT_TYPE = "com.bondplatform.dataset.manifest.v1";
  private static final String JSON_CONTENT = "application/json";
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
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern RFC_3339 =
      Pattern.compile(
          "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?"
              + "([Zz]|[+-][0-9]{2}:[0-9]{2})");

  private final Set<DatasetUrn> supportedDatasets;

  /**
   * Creates a validator that accepts the given datasets.
   *
   * @throws IllegalArgumentException if a dataset has no admission rules in this service
   */
  public SubmissionValidator(Set<DatasetUrn> supportedDatasets) {
    for (DatasetUrn dataset : supportedDatasets) {
      if (InputRules.forDataset(dataset.value()) == null) {
        throw new IllegalArgumentException("No admission rules exist for dataset " + dataset);
      }
    }
    this.supportedDatasets = Set.copyOf(supportedDatasets);
  }

  /**
   * Validates a parsed submission body and its idempotency key.
   *
   * @param event the CloudEvent as parsed JSON
   * @param idempotencyKey the {@code Idempotency-Key} header, or null when absent
   */
  public SubmissionResult validate(Map<String, Object> event, @Nullable String idempotencyKey) {
    Faults faults = new Faults();
    faults.noNulCharacters(event, ROOT);
    Envelope envelope = envelope(event, faults);
    Optional<Payload> payload =
        faults.object(event, ROOT, "data").map(data -> payload(data, envelope, faults));
    idempotencyKey(idempotencyKey, payload.map(Payload::runId).orElse(null), faults);
    if (!faults.isEmpty() || payload.isEmpty()) {
      return new SubmissionResult.Invalid(faults.errors());
    }
    // With no fault recorded, every part was read successfully.
    Payload data = payload.get();
    return new SubmissionResult.Valid(
        new Submission(
            Objects.requireNonNull(envelope.eventId()),
            FETCH_SERVICE,
            Objects.requireNonNull(envelope.dataset()),
            Objects.requireNonNull(envelope.time()),
            Objects.requireNonNull(envelope.subject()),
            Objects.requireNonNull(data.runId()),
            Objects.requireNonNull(data.fetchEventId()),
            Objects.requireNonNull(data.inputs()),
            Objects.requireNonNull(data.manifest()),
            Objects.requireNonNull(data.fingerprint())));
  }

  /** Reads the CloudEvent attributes; a part that is missing or wrong is null and recorded. */
  private Envelope envelope(Map<String, Object> event, Faults faults) {
    faults.constant(event, ROOT, "specversion", SPEC_VERSION);
    faults.constant(event, ROOT, "source", FETCH_SERVICE);
    faults.constant(event, ROOT, "type", MANIFEST_EVENT_TYPE);
    faults.constant(event, ROOT, "datacontenttype", JSON_CONTENT);
    extensions(event, faults);
    return new Envelope(
        faults.text(event, ROOT, "id"),
        dataset(event, faults),
        time(event, faults),
        faults.text(event, ROOT, "subject"));
  }

  private @Nullable DatasetUrn dataset(Map<String, Object> event, Faults faults) {
    String value = faults.text(event, ROOT, "dataschema");
    if (value == null) {
      return null;
    }
    Optional<DatasetUrn> supported =
        supportedDatasets.stream().filter(dataset -> dataset.value().equals(value)).findFirst();
    if (supported.isEmpty()) {
      faults.inBody("/dataschema", Code.UNSUPPORTED_VALUE, "Not a dataset this service accepts.");
    }
    return supported.orElse(null);
  }

  private static @Nullable Instant time(Map<String, Object> event, Faults faults) {
    String value = faults.text(event, ROOT, "time");
    if (value == null) {
      return null;
    }
    try {
      if (RFC_3339.matcher(value).matches()) {
        return OffsetDateTime.parse(value.toUpperCase(Locale.ROOT)).toInstant();
      }
    } catch (DateTimeParseException e) {
      // Falls through to the fault below: the shape was right but the date or time is not real.
    }
    faults.inBody("/time", Code.INVALID_VALUE, "Expected an RFC 3339 timestamp.");
    return null;
  }

  /** CloudEvents extension attributes: lowercase alphanumeric names, simple values. */
  private static void extensions(Map<String, Object> event, Faults faults) {
    for (String name : new TreeSet<>(event.keySet())) {
      if (ENVELOPE_ATTRIBUTES.contains(name)) {
        continue;
      }
      Object value = event.get(name);
      boolean simple = value instanceof String || value instanceof Boolean || isWholeNumber(value);
      if (!EXTENSION_NAME.matcher(name).matches() || !simple) {
        faults.inBody(
            Faults.child(ROOT, name),
            Code.UNKNOWN_PROPERTY,
            "Extension attributes need lowercase alphanumeric names and string, integer, or"
                + " boolean values.");
      }
    }
  }

  /** Reads the event's data; a part that is missing or wrong is null and recorded. */
  private static Payload payload(Map<String, Object> data, Envelope envelope, Faults faults) {
    faults.onlyProperties(data, DATA, DATA_PROPERTIES);
    schemaVersion(data, faults);
    String fingerprint = faults.text(data, DATA, "dataset_fingerprint");
    if (fingerprint != null && !FINGERPRINT.matcher(fingerprint).matches()) {
      faults.inBody(
          "/data/dataset_fingerprint", Code.INVALID_VALUE, "Expected sha256: and 64 hex digits.");
    }
    String runId = faults.text(data, DATA, "run_id");
    if (runId != null && !RUN_ID.matcher(runId).matches()) {
      faults.inBody(
          "/data/run_id",
          Code.INVALID_VALUE,
          "Expected 1 to 128 letters, digits, dots, underscores, colons, or hyphens.");
    }
    return new Payload(
        runId,
        faults.text(data, DATA, "event_id"),
        inputs(data, envelope, faults),
        faults
            .object(data, DATA, "manifest")
            .map(manifest -> manifest(manifest, faults))
            .orElse(null),
        fingerprint);
  }

  private static void schemaVersion(Map<String, Object> data, Faults faults) {
    Object value = data.get("schema_version");
    if (value == null) {
      faults.inBody("/data/schema_version", Code.REQUIRED, "Is required.");
    } else if (!isWholeNumber(value)
        || new BigDecimal(value.toString()).compareTo(BigDecimal.ONE) != 0) {
      faults.inBody("/data/schema_version", Code.UNSUPPORTED_VALUE, "Expected 1.");
    }
  }

  /**
   * Reads the event type and inputs. They are checked against the dataset's rules when the dataset
   * is known; an unknown dataset is already a fault, and then only their presence is checked.
   */
  private static @Nullable Inputs inputs(
      Map<String, Object> data, Envelope envelope, Faults faults) {
    DatasetUrn dataset = envelope.dataset();
    InputRules rules = dataset == null ? null : InputRules.forDataset(dataset.value());
    if (rules == null) {
      faults.text(data, DATA, "event_type");
      faults.object(data, DATA, "inputs");
      return null;
    }
    faults.constant(data, DATA, "event_type", rules.eventType());
    return faults
        .object(data, DATA, "inputs")
        .map(inputs -> rules.read(inputs, envelope.subject(), faults))
        .orElse(null);
  }

  private static @Nullable ManifestLocation manifest(Map<String, Object> manifest, Faults faults) {
    faults.onlyProperties(manifest, MANIFEST, Set.of("bucket", "key", "version_id"));
    String bucket = faults.text(manifest, MANIFEST, "bucket");
    if (bucket != null && (bucket.length() < 3 || bucket.length() > 63)) {
      faults.inBody("/data/manifest/bucket", Code.INVALID_VALUE, "Expected 3 to 63 characters.");
    }
    String key = faults.text(manifest, MANIFEST, "key");
    if (key != null && key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
      faults.inBody("/data/manifest/key", Code.INVALID_VALUE, "Expected at most 1024 UTF-8 bytes.");
    }
    String versionId =
        manifest.containsKey("version_id") ? faults.text(manifest, MANIFEST, "version_id") : null;
    return bucket == null || key == null ? null : new ManifestLocation(bucket, key, versionId);
  }

  private static void idempotencyKey(
      @Nullable String idempotencyKey, @Nullable String runId, Faults faults) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      faults.inHeader(IDEMPOTENCY_KEY_HEADER, Code.REQUIRED, "Is required.");
    } else if (!RUN_ID.matcher(idempotencyKey).matches()) {
      faults.inHeader(
          IDEMPOTENCY_KEY_HEADER,
          Code.INVALID_VALUE,
          "Expected 1 to 128 letters, digits, dots, underscores, colons, or hyphens.");
    } else if (runId != null && !idempotencyKey.equals(runId)) {
      faults.inHeader(IDEMPOTENCY_KEY_HEADER, Code.MISMATCH, "Must equal data.run_id.");
    }
  }

  private static boolean isWholeNumber(@Nullable Object value) {
    if (!(value instanceof Number number)) {
      return false;
    }
    try {
      return new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
    } catch (NumberFormatException e) {
      return false;
    }
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
      @Nullable ManifestLocation manifest,
      @Nullable String fingerprint) {}
}

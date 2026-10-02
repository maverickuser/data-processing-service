package com.bondplatform.dataprocessing.admission.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import com.bondplatform.dataprocessing.admission.domain.Submission.Inputs;
import com.bondplatform.dataprocessing.admission.domain.SubmissionError.Code;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Test cases U-ADM-01, U-ADM-03, and U-ADM-04. */
class SubmissionValidatorTest {

  private static final DatasetUrn BSE = new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades");
  private static final DatasetUrn NSDL = new DatasetUrn("urn:bond-platform:dataset:nsdl-security");
  private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

  private final SubmissionValidator validator = new SubmissionValidator(Set.of(BSE, NSDL));

  // U-ADM-01
  @Test
  void acceptsValidNsdlSubmission() {
    Submission submission = accepted(nsdlEvent(), "run_202");

    assertThat(submission.eventId()).isEqualTo("urn:bond-platform:submission:run_202");
    assertThat(submission.eventSource()).isEqualTo("urn:bond-platform:service:data-fetch-service");
    assertThat(submission.dataset()).isEqualTo(NSDL);
    assertThat(submission.time()).isEqualTo(Instant.parse("2026-09-27T14:30:00Z"));
    assertThat(submission.subject()).isEqualTo("isin/INE121A07QY9");
    assertThat(submission.runId()).isEqualTo("run_202");
    assertThat(submission.fetchEventId()).isEqualTo("evt_nsdl");
    assertThat(submission.inputs()).isEqualTo(new Inputs.Nsdl(Isin.of("INE121A07QY9")));
    assertThat(submission.manifest().bucket()).isEqualTo("data-fetch-service-artifacts");
    assertThat(submission.manifest().key()).isEqualTo("runs/run_202/manifest.json");
    assertThat(submission.manifest().versionId()).isNull();
    assertThat(submission.datasetFingerprint()).isEqualTo(FINGERPRINT);
  }

  // U-ADM-01
  @Test
  void acceptsValidBseSubmission() {
    Submission submission = accepted(bseEvent(), "run_101");

    assertThat(submission.dataset()).isEqualTo(BSE);
    assertThat(submission.inputs())
        .isEqualTo(new Inputs.Bse(ExchangeName.of("BSE"), TradeDate.parseIso("2026-09-21")));
  }

  // U-ADM-04
  @Test
  void orderingGroupIsTheTradeDateForBseAndTheIsinForNsdl() {
    assertThat(accepted(bseEvent(), "run_101").orderingGroup())
        .hasToString("trade-date:2026-09-21");
    assertThat(accepted(nsdlEvent(), "run_202").orderingGroup()).hasToString("isin:INE121A07QY9");
  }

  @Test
  void acceptsOffsetTimeExtensionAttributesAndPinnedManifestVersion() {
    Map<String, Object> event = nsdlEvent();
    event.put("time", "2026-09-27T20:00:00+05:30");
    event.put("traceparent", "00-abc-def-01");
    event.put("attempt", 2);
    event.put("replayed", false);
    manifest(event).put("version_id", "3HL4kqtJlcpXroDTDmJ.rUPuFZvqcrUB");

    Submission submission = accepted(event, "run_202");

    assertThat(submission.time()).isEqualTo(Instant.parse("2026-09-27T14:30:00Z"));
    assertThat(submission.manifest().versionId()).isEqualTo("3HL4kqtJlcpXroDTDmJ.rUPuFZvqcrUB");
  }

  @Test
  void isinInInputsIsNormalisedAndSubjectIsComparedAfterNormalisation() {
    Map<String, Object> event = nsdlEvent();
    inputs(event).put("isin_code", " ine121a07qy9 ");

    assertThat(accepted(event, "run_202").inputs())
        .isEqualTo(new Inputs.Nsdl(Isin.of("INE121A07QY9")));
  }

  // U-ADM-01
  @ParameterizedTest
  @ValueSource(
      strings = {"specversion", "id", "source", "type", "dataschema", "time", "subject", "data"})
  void everyEnvelopeAttributeIsRequired(String attribute) {
    assertThat(errorsOf(nsdlEvent(), event -> event.remove(attribute)))
        .contains(SubmissionError.inBody("/" + attribute, Code.REQUIRED, "Is required."));
  }

  // U-ADM-01
  @ParameterizedTest
  @ValueSource(
      strings = {
        "schema_version",
        "event_type",
        "event_id",
        "run_id",
        "inputs",
        "manifest",
        "dataset_fingerprint"
      })
  void everyDataPropertyIsRequired(String property) {
    assertThat(errorsOf(nsdlEvent(), event -> data(event).remove(property)))
        .contains(SubmissionError.inBody("/data/" + property, Code.REQUIRED, "Is required."));
  }

  @ParameterizedTest
  @CsvSource({
    "specversion, 0.3",
    "source, urn:bond-platform:service:other",
    "type, com.bondplatform.other.v1",
    "datacontenttype, text/plain"
  })
  void fixedEnvelopeValuesMustMatch(String attribute, String value) {
    assertThat(errorsOf(nsdlEvent(), event -> event.put(attribute, value)))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/" + attribute, Code.UNSUPPORTED_VALUE));
  }

  @Test
  void unknownDatasetIsUnsupported() {
    assertThat(
            errorsOf(nsdlEvent(), event -> event.put("dataschema", "urn:bond-platform:dataset:x")))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/dataschema", Code.UNSUPPORTED_VALUE));
  }

  @Test
  void textWithNulCharacterIsInvalidWhereverItAppears() {
    assertThat(errorsOf(nsdlEvent(), event -> event.put("id", "urn:a\0b")))
        .containsExactly(
            SubmissionError.inBody(
                "/id", Code.INVALID_VALUE, "Must not contain the NUL character."));
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("event_id", "evt\0")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/event_id");
    assertThat(errorsOf(nsdlEvent(), event -> manifest(event).put("key", "runs/\0/manifest.json")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/manifest/key");
    assertThat(errorsOf(nsdlEvent(), event -> event.put("traceparent", "00-\0-01")))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/traceparent", Code.INVALID_VALUE));
    assertThat(errorsOf(nsdlEvent(), event -> event.put("tags", List.of("ok", "\0"))))
        .extracting(SubmissionError::pointer)
        .contains("/tags/1");
  }

  @Test
  void valuesOfTheWrongKindAreInvalid() {
    assertThat(errorsOf(nsdlEvent(), event -> event.put("id", 42)))
        .containsExactly(
            SubmissionError.inBody("/id", Code.INVALID_VALUE, "Expected non-blank text."));
    assertThat(errorsOf(nsdlEvent(), event -> event.put("id", "  ")))
        .extracting(SubmissionError::code)
        .containsExactly(Code.INVALID_VALUE);
    assertThat(errorsOf(nsdlEvent(), event -> event.put("time", "yesterday")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/time");
    assertThat(errorsOf(nsdlEvent(), event -> event.put("data", "text")))
        .extracting(SubmissionError::pointer)
        .contains("/data");
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("manifest", List.of())))
        .extracting(SubmissionError::pointer)
        .contains("/data/manifest");
  }

  @Test
  void schemaVersionMustBeOne() {
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("schema_version", 2)))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/data/schema_version", Code.UNSUPPORTED_VALUE));
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("schema_version", "1"))).hasSize(1);
    assertThat(
            errorsOf(
                nsdlEvent(), event -> data(event).put("schema_version", new BigDecimal("1.5"))))
        .hasSize(1);
    accepted(
        with(nsdlEvent(), event -> data(event).put("schema_version", new BigDecimal("1.0"))),
        "run_202");
    accepted(with(nsdlEvent(), event -> data(event).put("schema_version", 1L)), "run_202");
  }

  @Test
  void propertiesTheSchemaDoesNotAllowAreRejected() {
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("files", List.of())))
        .containsExactly(
            SubmissionError.inBody(
                "/data/files", Code.UNKNOWN_PROPERTY, "Is not an allowed property."));
    assertThat(errorsOf(nsdlEvent(), event -> manifest(event).put("prefix", "runs/")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/manifest/prefix");
    assertThat(errorsOf(nsdlEvent(), event -> inputs(event).put("exchangeName", "BSE")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/inputs/exchangeName");
  }

  @Test
  void extensionAttributesNeedSimpleNamesAndValues() {
    assertThat(errorsOf(nsdlEvent(), event -> event.put("data_base64", "eA==")))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/data_base64", Code.UNKNOWN_PROPERTY));
    assertThat(errorsOf(nsdlEvent(), event -> event.put("extra", Map.of("a", 1)))).hasSize(1);
    assertThat(errorsOf(nsdlEvent(), event -> event.put("ratio", new BigDecimal("0.5"))))
        .hasSize(1);
  }

  @Test
  void manifestReferenceMustBeAnExactObject() {
    assertThat(errorsOf(nsdlEvent(), event -> manifest(event).put("bucket", "ab")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/manifest/bucket");
    assertThat(errorsOf(nsdlEvent(), event -> manifest(event).put("key", "k".repeat(1025))))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/manifest/key");
    assertThat(errorsOf(nsdlEvent(), event -> manifest(event).put("version_id", "")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/manifest/version_id");
  }

  @Test
  void fingerprintAndRunIdHaveFixedForms() {
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("dataset_fingerprint", "md5:abc")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/dataset_fingerprint");
    Map<String, Object> longRun = nsdlEvent();
    data(longRun).put("run_id", "r".repeat(129));
    assertThat(errors(longRun, "r".repeat(129)))
        .extracting(error -> error.pointer() == null ? error.header() : error.pointer())
        .containsExactly("/data/run_id", "Idempotency-Key");
  }

  // U-ADM-03
  @Test
  void bseSubmissionNeedsExchangeNameAndTradeDate() {
    assertThat(errorsOf(bseEvent(), "run_101", event -> inputs(event).remove("exchangeName")))
        .containsExactly(
            SubmissionError.inBody("/data/inputs/exchangeName", Code.REQUIRED, "Is required."));
    assertThat(errorsOf(bseEvent(), "run_101", event -> inputs(event).remove("tradeDate")))
        .containsExactly(
            SubmissionError.inBody("/data/inputs/tradeDate", Code.REQUIRED, "Is required."));
    assertThat(errorsOf(bseEvent(), "run_101", event -> inputs(event).put("exchangeName", "NSE")))
        .extracting(SubmissionError::code)
        .containsExactly(Code.UNSUPPORTED_VALUE);
    assertThat(
            errorsOf(bseEvent(), "run_101", event -> inputs(event).put("tradeDate", "21-09-2026")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/inputs/tradeDate");
  }

  @Test
  void eventTypeMustFitTheDataset() {
    assertThat(
            errorsOf(
                bseEvent(), "run_101", event -> data(event).put("event_type", "nsdl-bond-data")))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/data/event_type", Code.UNSUPPORTED_VALUE));
  }

  @Test
  void subjectMustAgreeWithTheInputs() {
    assertThat(
            errorsOf(
                bseEvent(),
                "run_101",
                event -> event.put("subject", "exchange/BSE/trade-date/2026-09-22")))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/subject", Code.MISMATCH));
    assertThat(errorsOf(nsdlEvent(), event -> event.put("subject", "isin/INE831R08076")))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/subject", Code.MISMATCH));
    assertThat(errorsOf(nsdlEvent(), event -> event.put("subject", "isin/")))
        .extracting(SubmissionError::code)
        .containsExactly(Code.MISMATCH);
    accepted(with(nsdlEvent(), event -> event.put("subject", "isin/ine121a07qy9")), "run_202");
  }

  @Test
  void idempotencyKeyMustBePresentAndEqualTheRunId() {
    assertThat(errors(nsdlEvent(), null))
        .containsExactly(
            SubmissionError.inHeader("Idempotency-Key", Code.REQUIRED, "Is required."));
    assertThat(errors(nsdlEvent(), " "))
        .extracting(SubmissionError::code)
        .containsExactly(Code.REQUIRED);
    assertThat(errors(nsdlEvent(), "run_999"))
        .containsExactly(
            SubmissionError.inHeader("Idempotency-Key", Code.MISMATCH, "Must equal data.run_id."));
  }

  @Test
  void reportsEveryFaultAtOnce() {
    Map<String, Object> event = nsdlEvent();
    event.remove("id");
    event.put("time", "soon");
    data(event).put("schema_version", 9);
    manifest(event).remove("key");

    assertThat(errors(event, null))
        .extracting(error -> error.pointer() == null ? error.header() : error.pointer())
        .containsExactlyInAnyOrder(
            "/id", "/time", "/data/schema_version", "/data/manifest/key", "Idempotency-Key");
  }

  @ParameterizedTest
  @ValueSource(strings = {"run 202", "run/202", "rün_202", "run_202\n", "run_202 "})
  void idempotencyKeyMayContainOnlyTheAllowedCharacters(String key) {
    Map<String, Object> event = nsdlEvent();
    data(event).put("run_id", key);

    assertThat(errors(event, key))
        .extracting(error -> error.pointer() == null ? error.header() : error.pointer())
        .containsExactly("/data/run_id", "Idempotency-Key");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-09-27T14:30Z",
        "2026-09-27T14:30:00+05",
        "2026-09-27T14:30:00+05:30:15",
        "+12026-09-27T14:30:00Z",
        "-0001-09-27T14:30:00Z",
        "2026-09-27",
        "2026-09-27 14:30:00Z",
        "2026-13-27T14:30:00Z",
        "2026-09-27T25:30:00Z"
      })
  void timeMustBeRfc3339(String time) {
    assertThat(errorsOf(nsdlEvent(), event -> event.put("time", time)))
        .containsExactly(
            SubmissionError.inBody("/time", Code.INVALID_VALUE, "Expected an RFC 3339 timestamp."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-09-27T14:30:00.123456Z",
        "2026-09-27t14:30:00z",
        "2026-09-27T20:00:00+05:30"
      })
  void timeAcceptsFractionsLowercaseAndOffsets(String time) {
    accepted(with(nsdlEvent(), event -> event.put("time", time)), "run_202");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "isin/ INE121A07QY9",
        "isin/INE121A07QY9 ",
        "isin/INE121A07QY9\n",
        "isin/INE121\u00A0A07QY9",
        "ISIN/INE121A07QY9",
        "INE121A07QY9"
      })
  void nsdlSubjectMustBeIsinSlashIsinWithoutSpaces(String subject) {
    assertThat(errorsOf(nsdlEvent(), event -> event.put("subject", subject)))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/subject", Code.MISMATCH));
  }

  @Test
  void isinOfOnlyInvisibleCharactersIsInvalid() {
    assertThat(errorsOf(nsdlEvent(), event -> inputs(event).put("isin_code", "\u00A0")))
        .extracting(SubmissionError::pointer, SubmissionError::code)
        .containsExactly(tuple("/data/inputs/isin_code", Code.INVALID_VALUE));
  }

  @Test
  void pointersEscapeTildeAndSlash() {
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("x/y~z", 1)))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/x~1y~0z");
    assertThat(errorsOf(nsdlEvent(), event -> event.put("a/b", "v")))
        .extracting(SubmissionError::pointer)
        .containsExactly("/a~1b");
  }

  @Test
  void missingParentIsOneFaultNotOnePerChild() {
    assertThat(errorsOf(nsdlEvent(), event -> event.remove("data")))
        .containsExactly(SubmissionError.inBody("/data", Code.REQUIRED, "Is required."));
    assertThat(errorsOf(nsdlEvent(), event -> data(event).remove("inputs")))
        .containsExactly(SubmissionError.inBody("/data/inputs", Code.REQUIRED, "Is required."));
    assertThat(errorsOf(nsdlEvent(), event -> data(event).remove("manifest")))
        .containsExactly(SubmissionError.inBody("/data/manifest", Code.REQUIRED, "Is required."));
  }

  @Test
  void eventTypeAndInputsAreStillRequiredWhenTheDatasetIsUnsupported() {
    Map<String, Object> event = nsdlEvent();
    event.put("dataschema", "urn:bond-platform:dataset:other");
    data(event).remove("event_type");
    data(event).remove("inputs");

    assertThat(errors(event, "run_202"))
        .extracting(SubmissionError::pointer)
        .containsExactly("/dataschema", "/data/event_type", "/data/inputs");
  }

  @Test
  void nonFiniteNumbersAreNotWholeNumbers() {
    assertThat(errorsOf(nsdlEvent(), event -> data(event).put("schema_version", nonFiniteNumber())))
        .extracting(SubmissionError::pointer)
        .containsExactly("/data/schema_version");
    assertThat(errorsOf(nsdlEvent(), event -> event.put("ratio", nonFiniteNumber())))
        .extracting(SubmissionError::pointer)
        .containsExactly("/ratio");
  }

  @Test
  void explicitNullIsInvalidRatherThanMissing() {
    assertThat(errorsOf(nsdlEvent(), event -> manifest(event).put("version_id", null)))
        .containsExactly(
            SubmissionError.inBody(
                "/data/manifest/version_id", Code.INVALID_VALUE, "Must not be null."));
  }

  @Test
  void faultsInExtensionAttributesAreReportedInNameOrder() {
    Map<String, Object> event = nsdlEvent();
    event.put("Zeta", "v");
    event.put("Alpha", "v");

    assertThat(errors(event, "run_202"))
        .extracting(SubmissionError::pointer)
        .containsExactly("/Alpha", "/Zeta");
  }

  @Test
  void datasetWithoutAdmissionRulesCannotBeSupported() {
    Set<DatasetUrn> withUnknown = Set.of(BSE, new DatasetUrn("urn:bond-platform:dataset:third"));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SubmissionValidator(withUnknown))
        .withMessageContaining("urn:bond-platform:dataset:third");
  }

  /** A value a JSON parser cannot produce but a caller could pass; held as a Number. */
  private static Number nonFiniteNumber() {
    return new Number() {
      private static final long serialVersionUID = 1L;

      @Override
      public int intValue() {
        return 0;
      }

      @Override
      public long longValue() {
        return 0;
      }

      @Override
      public float floatValue() {
        return 0;
      }

      @Override
      public double doubleValue() {
        return 0;
      }

      @Override
      public String toString() {
        return "NaN";
      }
    };
  }

  private Submission accepted(Map<String, Object> event, String idempotencyKey) {
    SubmissionResult result = validator.validate(event, idempotencyKey);
    assertThat(result).isInstanceOf(SubmissionResult.Valid.class);
    return ((SubmissionResult.Valid) result).submission();
  }

  private List<SubmissionError> errors(Map<String, Object> event, @Nullable String idempotencyKey) {
    SubmissionResult result = validator.validate(event, idempotencyKey);
    assertThat(result).isInstanceOf(SubmissionResult.Invalid.class);
    return ((SubmissionResult.Invalid) result).errors();
  }

  private List<SubmissionError> errorsOf(
      Map<String, Object> event, Consumer<Map<String, Object>> change) {
    return errorsOf(event, "run_202", change);
  }

  private List<SubmissionError> errorsOf(
      Map<String, Object> event, String idempotencyKey, Consumer<Map<String, Object>> change) {
    return errors(with(event, change), idempotencyKey);
  }

  private static Map<String, Object> with(
      Map<String, Object> event, Consumer<Map<String, Object>> change) {
    change.accept(event);
    return event;
  }

  private static Map<String, Object> nsdlEvent() {
    Map<String, Object> inputs = new LinkedHashMap<>();
    inputs.put("isin_code", "INE121A07QY9");
    return event("run_202", NSDL, "isin/INE121A07QY9", "nsdl-bond-data", "evt_nsdl", inputs);
  }

  private static Map<String, Object> bseEvent() {
    Map<String, Object> inputs = new LinkedHashMap<>();
    inputs.put("exchangeName", "BSE");
    inputs.put("tradeDate", "2026-09-21");
    return event(
        "run_101", BSE, "exchange/BSE/trade-date/2026-09-21", "daily-bhavcopy", "evt_bse", inputs);
  }

  private static Map<String, Object> event(
      String runId,
      DatasetUrn dataset,
      String subject,
      String eventType,
      String fetchEventId,
      Map<String, Object> inputs) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("bucket", "data-fetch-service-artifacts");
    manifest.put("key", "runs/" + runId + "/manifest.json");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schema_version", 1);
    data.put("event_type", eventType);
    data.put("event_id", fetchEventId);
    data.put("run_id", runId);
    data.put("inputs", inputs);
    data.put("manifest", manifest);
    data.put("dataset_fingerprint", FINGERPRINT);
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> data(Map<String, Object> event) {
    return (Map<String, Object>) Objects.requireNonNull(event.get("data"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> manifest(Map<String, Object> event) {
    return (Map<String, Object>) Objects.requireNonNull(data(event).get("manifest"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> inputs(Map<String, Object> event) {
    return (Map<String, Object>) Objects.requireNonNull(data(event).get("inputs"));
  }
}

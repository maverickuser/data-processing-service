package com.bondplatform.dataprocessing.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.source.domain.ManifestReader.Reading;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManifestReaderTest {

  @Test
  void readsEveryFieldOfNsdlManifest() {
    Manifest manifest =
        read(Manifests.nsdl("INE121A07QY9_isin-details.json", "INE121A07QY9_ratings.json"));

    assertThat(manifest.id()).isEqualTo("urn:bond-platform:manifest:run_202");
    assertThat(manifest.dataset().value()).isEqualTo("urn:bond-platform:dataset:nsdl-security");
    assertThat(manifest.subject()).isEqualTo("isin/INE121A07QY9");
    assertThat(manifest.eventType()).isEqualTo("nsdl-bond-data");
    assertThat(manifest.fetchEventId()).isEqualTo("evt_1");
    assertThat(manifest.runId()).isEqualTo("run_202");
    assertThat(manifest.inputs()).containsExactly(Map.entry("isin_code", "INE121A07QY9"));
    assertThat(manifest.datasetFingerprint()).startsWith("sha256:b");
    assertThat(manifest.files()).hasSize(2);
    ManifestFile file = manifest.files().get(1);
    assertThat(file.fetchJobId()).isEqualTo("isin-details");
    assertThat(file.bucket()).isEqualTo("data-fetch-service-artifacts");
    assertThat(file.key()).isEqualTo("runs/run_202/raw/isin-details/1/INE121A07QY9_ratings.json");
    assertThat(file.fileName()).isEqualTo("INE121A07QY9_ratings.json");
    assertThat(file.format()).isEqualTo(SourceFormat.JSON);
    assertThat(file.sha256()).isEqualTo(Manifests.HASH);
    assertThat(file.sizeBytes()).isEqualTo(1234);
    assertThat(file.sourceUrl()).isEqualTo("https://example.invalid/isin-details");
  }

  @Test
  void acceptsHashWithPrefixOrInUpperCaseAndKeepsItPlainAndLower() {
    assertThat(sha256Read("sha256:" + "AB".repeat(32))).isEqualTo("ab".repeat(32));
    assertThat(sha256Read("CD".repeat(32))).isEqualTo("cd".repeat(32));
  }

  @Test
  void acceptsFormatInAnyCaseAndWholeSizesOfAnyNumberType() {
    Map<String, Object> manifest = Manifests.bse();
    Manifests.firstFile(manifest).put("format", "CSV");
    Manifests.firstFile(manifest).put("size_bytes", new BigDecimal("99424.0"));

    ManifestFile file = read(manifest).files().get(0);

    assertThat(file.format()).isEqualTo(SourceFormat.CSV);
    assertThat(file.sizeBytes()).isEqualTo(99_424);
  }

  @Test
  void fileWithoutProvenanceIsStillReadable() {
    Map<String, Object> manifest = Manifests.bse();
    Manifests.firstFile(manifest).remove("job_id");
    Manifests.firstFile(manifest).remove("source_url");

    ManifestFile file = read(manifest).files().get(0);

    assertThat(file.fetchJobId()).isNull();
    assertThat(file.sourceUrl()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"specversion", "id", "source", "type", "dataschema", "subject", "data"})
  void everyEnvelopeAttributeIsRequired(String attribute) {
    assertThat(problems(manifest -> manifest.remove(attribute)))
        .containsExactly("/" + attribute + " is required");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"event_type", "event_id", "run_id", "inputs", "dataset_fingerprint", "files"})
  void everyDataPropertyIsRequired(String property) {
    assertThat(problems(manifest -> Manifests.data(manifest).remove(property)))
        .singleElement()
        .asString()
        .startsWith("/data/" + property);
  }

  @Test
  void fixedEnvelopeValuesMustMatch() {
    assertThat(problems(manifest -> manifest.put("type", "com.example.other")))
        .containsExactly("/type must be com.bondplatform.dataset.manifest.v1");
    assertThat(problems(manifest -> manifest.put("source", "urn:other")))
        .containsExactly("/source must be urn:bond-platform:service:data-fetch-service");
    assertThat(problems(manifest -> manifest.put("specversion", "0.3")))
        .containsExactly("/specversion must be 1.0");
    assertThat(problems(manifest -> manifest.put("id", " ")))
        .containsExactly("/id must be non-blank text");
  }

  @Test
  void schemaVersionMustBeOne() {
    assertThat(problems(manifest -> Manifests.data(manifest).put("schema_version", 2)))
        .containsExactly("/data/schema_version must be 1");
    assertThat(problems(manifest -> Manifests.data(manifest).put("schema_version", "1")))
        .containsExactly("/data/schema_version must be 1");
    assertThat(
            read(with(manifest -> Manifests.data(manifest).put("schema_version", BigInteger.ONE))))
        .isNotNull();
  }

  @Test
  void inputsMustBeTextValues() {
    assertThat(
            problems(
                manifest -> Manifests.data(manifest).put("inputs", Map.of("tradeDate", 20260921))))
        .containsExactly("/data/inputs/tradeDate must be text");
    assertThat(problems(manifest -> Manifests.data(manifest).put("inputs", "BSE")))
        .containsExactly("/data/inputs must be an object");
  }

  @Test
  void fileListMustBeNonEmptyArrayOfObjects() {
    assertThat(problems(manifest -> Manifests.data(manifest).put("files", List.of())))
        .containsExactly("/data/files must list at least one file");
    assertThat(problems(manifest -> Manifests.data(manifest).put("files", List.of("a.csv"))))
        .containsExactly("/data/files/0 must be an object");
  }

  @Test
  void eachFileNeedsLocationFormatHashAndSize() {
    assertThat(
            problems(
                manifest -> {
                  Map<String, Object> file = Manifests.firstFile(manifest);
                  file.remove("bucket");
                  file.put("key", "");
                  file.put("format", "xml");
                  file.put("sha256", "file-hash");
                  file.put("size_bytes", -1);
                  file.put("job_id", 7);
                }))
        .containsExactly(
            "/data/files/0/bucket is required",
            "/data/files/0/key must be non-blank text",
            "/data/files/0/format must be csv or json",
            "/data/files/0/sha256 must be 64 hex digits, optionally after sha256:",
            "/data/files/0/size_bytes must be a whole number of bytes",
            "/data/files/0/job_id must be text");
  }

  @Test
  void sizeMustBeWholeAndFitInBytes() {
    assertThat(problems(manifest -> Manifests.firstFile(manifest).put("size_bytes", 1.5)))
        .containsExactly("/data/files/0/size_bytes must be a whole number of bytes");
    assertThat(
            problems(
                manifest ->
                    Manifests.firstFile(manifest)
                        .put("size_bytes", new BigInteger("99999999999999999999"))))
        .containsExactly("/data/files/0/size_bytes must be a whole number of bytes");
    assertThat(problems(manifest -> Manifests.firstFile(manifest).remove("size_bytes")))
        .containsExactly("/data/files/0/size_bytes is required");
  }

  @Test
  void problemsOfManyFieldsAreReportedTogether() {
    assertThat(
            problems(
                manifest -> {
                  manifest.remove("subject");
                  Manifests.data(manifest).remove("run_id");
                }))
        .containsExactly("/subject is required", "/data/run_id is required");
  }

  private static String sha256Read(String hash) {
    return read(with(manifest -> Manifests.firstFile(manifest).put("sha256", hash)))
        .files()
        .get(0)
        .sha256();
  }

  private static Map<String, Object> with(Consumer<Map<String, Object>> change) {
    Map<String, Object> manifest = Manifests.bse();
    change.accept(manifest);
    return manifest;
  }

  private static Manifest read(Map<String, Object> manifest) {
    Reading reading = ManifestReader.read(manifest);
    assertThat(reading).isInstanceOf(Reading.Read.class);
    return ((Reading.Read) reading).manifest();
  }

  private static List<String> problems(Consumer<Map<String, Object>> change) {
    Reading reading = ManifestReader.read(with(change));
    assertThat(reading).isInstanceOf(Reading.Unreadable.class);
    return ((Reading.Unreadable) reading).problems();
  }
}

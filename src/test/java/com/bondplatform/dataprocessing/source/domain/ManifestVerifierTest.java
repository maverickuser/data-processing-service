package com.bondplatform.dataprocessing.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.source.domain.ManifestVerifier.SubmittedRun;
import com.bondplatform.dataprocessing.source.domain.SourceProblem.Code;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Test cases U-JOB-03 and U-SRC-02 (the manifest's part). */
class ManifestVerifierTest {

  private static final SubmittedRun NSDL_RUN =
      new SubmittedRun(
          new DatasetUrn("urn:bond-platform:dataset:nsdl-security"),
          "isin/INE121A07QY9",
          "nsdl-bond-data",
          "evt_1",
          "run_202",
          Map.of("isin_code", "INE121A07QY9"),
          "sha256:" + "b".repeat(64));

  @Test
  void manifestOfTheSubmittedRunWithItsFilesPasses() {
    assertThat(verify(nsdl(), NSDL_RUN, SourceFormat.JSON)).isEmpty();
  }

  // U-JOB-03
  @Test
  void disagreementOnAnySharedFieldIsMismatchNamingEveryField() {
    SubmittedRun other =
        new SubmittedRun(
            new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades"),
            "isin/INE002A08534",
            "daily-bhavcopy",
            "evt_2",
            "run_203",
            Map.of("isin_code", "INE002A08534"),
            "sha256:" + "c".repeat(64));

    Optional<SourceProblem> problem = verify(nsdl(), other, SourceFormat.JSON);

    assertThat(problem).map(SourceProblem::code).contains(Code.INVALID_MANIFEST);
    assertThat(problem.orElseThrow().detail())
        .contains(
            "dataschema",
            "subject",
            "data.event_type",
            "data.event_id",
            "data.run_id",
            "data.inputs",
            "data.dataset_fingerprint");
  }

  // U-JOB-03
  @Test
  void inputsDifferingInOneValueAreMismatch() {
    SubmittedRun otherIsin =
        new SubmittedRun(
            NSDL_RUN.dataset(),
            NSDL_RUN.subject(),
            NSDL_RUN.eventType(),
            NSDL_RUN.fetchEventId(),
            NSDL_RUN.runId(),
            Map.of("isin_code", "ine121a07qy9"),
            NSDL_RUN.datasetFingerprint());

    assertThat(verify(nsdl(), otherIsin, SourceFormat.JSON).orElseThrow().detail())
        .isEqualTo("The manifest disagrees with the submission on data.inputs");
  }

  @Test
  void fileOfAnotherFormatThanTheContractReadsIsInvalid() {
    assertThat(verify(nsdl(), NSDL_RUN, SourceFormat.CSV))
        .contains(new SourceProblem(Code.INVALID_MANIFEST, "Every listed file must be CSV"));
  }

  @Test
  void csvManifestMustListExactlyOneFile() {
    Manifest twoCsv = manifestWith(List.of(file(SourceFormat.CSV, 10), file(SourceFormat.CSV, 10)));

    assertThat(verify(twoCsv, NSDL_RUN, SourceFormat.CSV))
        .contains(
            new SourceProblem(
                Code.INVALID_MANIFEST, "A CSV manifest must list exactly one file, not 2"));
  }

  // U-SRC-02
  @Test
  void csvAndJsonFilesAreLimitedToTenMebibytesTogether() {
    long limit = ManifestVerifier.MAX_SOURCE_BYTES;

    assertThat(
            verify(
                manifestWith(List.of(file(SourceFormat.CSV, limit))), NSDL_RUN, SourceFormat.CSV))
        .isEmpty();
    assertThat(
            verify(
                    manifestWith(List.of(file(SourceFormat.CSV, limit + 1))),
                    NSDL_RUN,
                    SourceFormat.CSV)
                .map(SourceProblem::code))
        .contains(Code.SOURCE_TOO_LARGE);
    List<ManifestFile> jsonFiles = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      jsonFiles.add(file(SourceFormat.JSON, limit / 6 + 1));
    }
    assertThat(verify(manifestWith(jsonFiles), NSDL_RUN, SourceFormat.JSON).orElseThrow())
        .satisfies(
            problem -> {
              assertThat(problem.code()).isEqualTo(Code.SOURCE_TOO_LARGE);
              assertThat(problem.detail()).contains("10485760");
            });
  }

  @Test
  void mismatchIsReportedBeforeFileProblems() {
    // Both are INVALID_MANIFEST; the detail tells them apart.
    SubmittedRun otherRun =
        new SubmittedRun(
            NSDL_RUN.dataset(),
            NSDL_RUN.subject(),
            NSDL_RUN.eventType(),
            NSDL_RUN.fetchEventId(),
            "run_999",
            NSDL_RUN.inputs(),
            NSDL_RUN.datasetFingerprint());

    assertThat(verify(nsdl(), otherRun, SourceFormat.CSV).orElseThrow().detail())
        .isEqualTo("The manifest disagrees with the submission on data.run_id");
  }

  @Test
  void manifestLimitIsOneMebibyte() {
    assertThat(Manifest.MAX_BYTES).isEqualTo(1_048_576);
  }

  private static Optional<SourceProblem> verify(
      Manifest manifest, SubmittedRun run, SourceFormat format) {
    return ManifestVerifier.verify(manifest, run, format);
  }

  private static Manifest nsdl() {
    return ((ManifestReader.Reading.Read)
            ManifestReader.read(Manifests.nsdl("INE121A07QY9_isin-details.json")))
        .manifest();
  }

  private static Manifest manifestWith(List<ManifestFile> files) {
    Manifest manifest = nsdl();
    return new Manifest(
        manifest.id(),
        manifest.dataset(),
        manifest.subject(),
        manifest.eventType(),
        manifest.fetchEventId(),
        manifest.runId(),
        manifest.inputs(),
        manifest.datasetFingerprint(),
        files);
  }

  private static ManifestFile file(SourceFormat format, long size) {
    return new ManifestFile(null, "bucket", "runs/r/raw/j/1/f", format, Manifests.HASH, size, null);
  }
}

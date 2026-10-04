package com.bondplatform.dataprocessing.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.source.domain.SourceProblem.Code;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Test cases U-SRC-04 and U-JSON-05, and the filename half of U-JSON-04. */
class FilenameCheckTest {

  private static final Map<String, String> BSE_INPUTS =
      Map.of("exchangeName", "BSE", "tradeDate", "2026-01-01");
  private static final Map<String, String> NSDL_INPUTS = Map.of("isin_code", " ine831r08076 ");

  @Test
  void csvNameAgreeingWithTheInputsPasses() {
    assertThat(FilenameCheck.csv(file("BSE_fgroup01012026.csv"), BSE_INPUTS)).isEmpty();
  }

  // U-SRC-04
  @Test
  void csvNameWithAnotherDateOrExchangeIsTradeDateMismatch() {
    Optional<SourceProblem> otherDate =
        FilenameCheck.csv(file("BSE_fgroup02012026.csv"), BSE_INPUTS);
    Optional<SourceProblem> otherExchange =
        FilenameCheck.csv(file("NSE_fgroup01012026.csv"), BSE_INPUTS);

    assertThat(otherDate).map(SourceProblem::code).contains(Code.TRADE_DATE_MISMATCH);
    assertThat(otherDate.orElseThrow().detail())
        .isEqualTo(
            "BSE_fgroup02012026.csv names BSE on 2026-01-02, but the manifest gives BSE on"
                + " 2026-01-01");
    assertThat(otherExchange).map(SourceProblem::code).contains(Code.TRADE_DATE_MISMATCH);
  }

  @Test
  void csvNameWithoutExchangeOrRealDateIsInvalidSourceFilename() {
    assertThat(FilenameCheck.csv(file("fgroup01012026.csv"), BSE_INPUTS))
        .map(SourceProblem::code)
        .contains(Code.INVALID_SOURCE_FILENAME);
    assertThat(FilenameCheck.csv(file("BSE_fgroup32132026.csv"), BSE_INPUTS))
        .map(SourceProblem::code)
        .contains(Code.INVALID_SOURCE_FILENAME);
  }

  @Test
  void jsonNamesOfTheRequestedSecurityPassWhateverTheirCase() {
    assertThat(
            FilenameCheck.json(
                files("INE831R08076_ratings.json", "ine831r08076.json"), NSDL_INPUTS))
        .isEmpty();
  }

  // U-JSON-04
  @Test
  void jsonNameIdentifyingNoSecurityIsInvalidSourceFilename() {
    assertThat(
            FilenameCheck.json(files("INE831R08076.json", "_ratings.json"), NSDL_INPUTS)
                .orElseThrow())
        .isEqualTo(
            new SourceProblem(
                Code.INVALID_SOURCE_FILENAME, "_ratings.json does not identify a security"));
  }

  // U-JSON-05
  @Test
  void jsonNamesOfDifferentSecuritiesFailTheWholeRequest() {
    assertThat(
            FilenameCheck.json(
                    files("INE831R08076_ratings.json", "INE002A08534_ratings.json"), NSDL_INPUTS)
                .orElseThrow())
        .satisfies(
            problem -> {
              assertThat(problem.code()).isEqualTo(Code.INVALID_SOURCE_FILENAME);
              assertThat(problem.detail()).contains("more than one security");
            });
  }

  @Test
  void jsonNamesOfAnotherSecurityThanRequestedAreInvalidSourceFilename() {
    assertThat(
            FilenameCheck.json(files("INE002A08534_ratings.json"), NSDL_INPUTS)
                .orElseThrow()
                .detail())
        .contains("requests INE831R08076");
  }

  private static ManifestFile file(String name) {
    return new ManifestFile(
        null, "bucket", "runs/r/raw/j/1/" + name, SourceFormat.CSV, Manifests.HASH, 1, null);
  }

  private static List<ManifestFile> files(String... names) {
    return Arrays.stream(names)
        .map(
            name ->
                new ManifestFile(
                    null,
                    "bucket",
                    "runs/r/raw/j/1/" + name,
                    SourceFormat.JSON,
                    Manifests.HASH,
                    1,
                    null))
        .toList();
  }
}

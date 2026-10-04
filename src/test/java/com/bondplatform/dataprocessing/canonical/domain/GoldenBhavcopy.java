package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The golden bhavcopy's canonical rows and a run to store them under, for adapter tests. */
public final class GoldenBhavcopy {

  /** The run every adapter test stores the golden rows under. */
  public static final CanonicalRun RUN =
      new CanonicalRun(
          new JobId(UUID.fromString("0190f3a0-0000-7000-8000-000000000001")),
          2,
          LocalDate.of(2026, 1, 1),
          "data-fetch-service-artifacts",
          "runs/run_101/raw/debt-bhavcopy/1/BSE_fgroup01012026.csv",
          "bse-debt-bhavcopy-csv-v1",
          "bse-debt-bhavcopy-mapping-v1");

  private GoldenBhavcopy() {}

  /** Returns the eight canonical rows of {@code fixtures/bse/BSE_fgroup01012026.csv}. */
  public static List<CanonicalRow> rows() {
    byte[] bytes = resource("/fixtures/bse/BSE_fgroup01012026.csv");
    List<CanonicalRow> rows = new ArrayList<>();
    new CsvCanonicalizer(BhavcopyContract.CONTRACT, RuleRegistry.standard())
        .canonicalize(() -> new ByteArrayInputStream(bytes), rows::add);
    return List.copyOf(rows);
  }

  static byte[] resource(String path) {
    try (InputStream in = GoldenBhavcopy.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource " + path);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
          "bse-debt-bhavcopy-csv-v2",
          "bse-debt-bhavcopy-mapping-v2");

  private GoldenBhavcopy() {}

  /** Returns the eight canonical rows of {@code fixtures/bse/BSE_fgroup01012026.csv}. */
  public static List<CanonicalRow> rows() {
    byte[] bytes = resource("/fixtures/bse/BSE_fgroup01012026.csv");
    List<CanonicalRow> rows = new ArrayList<>();
    new CsvCanonicalizer(BhavcopyContract.CONTRACT, RuleRegistry.standard())
        .canonicalize(() -> new ByteArrayInputStream(bytes), rows::add);
    return List.copyOf(rows);
  }

  /**
   * Returns golden record 6, which is accepted, with its coupon {@code 9 % p.a.}: invalid, so
   * ignored with an {@code INVALID_DECIMAL} error while the row stays accepted.
   */
  public static CanonicalRow acceptedWithIgnoredCoupon() {
    CanonicalRow accepted =
        rows().stream().filter(row -> row.record().recordNumber() == 6).findFirst().orElseThrow();
    Map<String, CanonicalField> fields = new LinkedHashMap<>(accepted.record().fields());
    CanonicalField coupon = accepted.record().field("coupon_rate");
    fields.put(
        "coupon_rate",
        new CanonicalField(
            coupon.name(),
            coupon.sourceHeader(),
            coupon.columnIndex(),
            "9 % p.a.",
            "9 % p.a.",
            null,
            coupon.dataType(),
            ValidationStatus.IGNORED,
            List.of(new ValidationIssue(ErrorCode.INVALID_DECIMAL, "Expected a decimal."))));
    return new CanonicalRow(
        new CanonicalRecord(accepted.record().recordNumber(), fields, List.of()),
        accepted.disposition(),
        accepted.supersededBy());
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

package com.bondplatform.dataprocessing.source.domain;

import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks that the listed files' names agree with the manifest's inputs (LLD sections 2.1, 13.1).
 *
 * <p>A file name is never the source of the trade date, exchange, or ISIN: the manifest inputs are.
 * The name only has to agree with them.
 */
public final class FilenameCheck {

  private FilenameCheck() {}

  /**
   * Checks a bhavcopy: its name must give an exchange and a real trade date, both equal to the
   * inputs' {@code exchangeName} and {@code tradeDate}.
   */
  public static Optional<SourceProblem> csv(ManifestFile file, Map<String, String> inputs) {
    Optional<CsvFilename> name = CsvFilename.parse(file.fileName());
    if (name.isEmpty()) {
      return problem(
          SourceProblem.Code.INVALID_SOURCE_FILENAME,
          file.fileName() + " does not name an exchange and a DDMMYYYY trade date");
    }
    ExchangeName exchange = ExchangeName.of(inputs.getOrDefault("exchangeName", ""));
    TradeDate tradeDate = TradeDate.parseIso(inputs.getOrDefault("tradeDate", ""));
    if (!name.get().exchange().equals(exchange) || !name.get().tradeDate().equals(tradeDate)) {
      return problem(
          SourceProblem.Code.TRADE_DATE_MISMATCH,
          file.fileName()
              + " names "
              + name.get().exchange()
              + " on "
              + name.get().tradeDate()
              + ", but the manifest gives "
              + exchange
              + " on "
              + tradeDate);
    }
    return Optional.empty();
  }

  /**
   * Checks an NSDL file set: every name must identify a security, all the same one, and it must be
   * the inputs' {@code isin_code} after trimming and uppercasing.
   */
  public static Optional<SourceProblem> json(List<ManifestFile> files, Map<String, String> inputs) {
    Set<Isin> named = new LinkedHashSet<>();
    for (ManifestFile file : files) {
      Optional<Isin> isin = JsonFilename.isinOf(file.fileName());
      if (isin.isEmpty()) {
        return problem(
            SourceProblem.Code.INVALID_SOURCE_FILENAME,
            file.fileName() + " does not identify a security");
      }
      named.add(isin.get());
    }
    if (named.size() > 1) {
      return problem(
          SourceProblem.Code.INVALID_SOURCE_FILENAME,
          "The listed files name more than one security: " + named);
    }
    Isin requested = Isin.of(inputs.getOrDefault("isin_code", ""));
    if (!named.contains(requested)) {
      return problem(
          SourceProblem.Code.INVALID_SOURCE_FILENAME,
          "The listed files name " + named + ", but the manifest requests " + requested);
    }
    return Optional.empty();
  }

  private static Optional<SourceProblem> problem(SourceProblem.Code code, String detail) {
    return Optional.of(new SourceProblem(code, detail));
  }
}

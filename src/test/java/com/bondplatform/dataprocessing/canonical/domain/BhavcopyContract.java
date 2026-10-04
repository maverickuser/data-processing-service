package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.adapter.config.ClasspathContractCatalog;
import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The real bhavcopy source and mapping contracts, loaded once for tests. */
public final class BhavcopyContract {

  /** The real bhavcopy contracts, source and mapping. */
  static final PinnedContracts PINNED =
      new ClasspathContractCatalog(
              new YamlContractLoader(), new ContractValidator(RuleRegistry.standard()))
          .load(
              List.of(new ContractPair("bse-debt-bhavcopy-csv-v1", "bse-debt-bhavcopy-mapping-v1")))
          .contractsFor(new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades"));

  static final SourceContract.Csv CONTRACT = (SourceContract.Csv) PINNED.source();

  /** The real bhavcopy mapping contract. */
  public static final MappingContract MAPPING = PINNED.mapping();

  /** The ten selected headers, in the order a real bhavcopy has them, with one extra column. */
  static final String HEADER =
      "Security_cd,ISIN No.,Open Price,High Price,Low Price,Close Price,Total Traded Volume,"
          + "Number of Trades,Total Turnover,FACE VALUE,Extra";

  /** The selected fields in the column order of {@link BhavcopyContract#HEADER}. */
  static final List<String> COLUMNS =
      List.of(
          "security_code",
          "isin",
          "open_price",
          "high_price",
          "low_price",
          "close_price",
          "traded_volume",
          "number_of_trades",
          "turnover",
          "face_value");

  private BhavcopyContract() {}

  /** A valid row with low 99 and high 101, with the given fields replaced. */
  static CsvRow row(long recordNumber, Map<String, String> overrides) {
    Map<String, String> values =
        new HashMap<>(
            Map.of(
                "security_code", "1001",
                "isin", "INE121A07QY9",
                "open_price", "100",
                "high_price", "101",
                "low_price", "99",
                "close_price", "100.5",
                "traded_volume", "10",
                "number_of_trades", "2",
                "turnover", "1,000.00",
                "face_value", "100"));
    values.putAll(overrides);
    Map<String, CsvCell> cells = new HashMap<>();
    values.forEach((name, raw) -> cells.put(name, new CsvCell(COLUMNS.indexOf(name) + 1, raw)));
    return new CsvRow(recordNumber, cells);
  }
}

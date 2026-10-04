package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.adapter.config.ClasspathContractCatalog;
import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import java.util.List;

/** The real bhavcopy source contract, loaded once for tests. */
final class BhavcopyContract {

  static final SourceContract.Csv CONTRACT =
      (SourceContract.Csv)
          new ClasspathContractCatalog(
                  new YamlContractLoader(), new ContractValidator(RuleRegistry.standard()))
              .load(
                  List.of(
                      new ContractPair("bse-debt-bhavcopy-csv-v1", "bse-debt-bhavcopy-mapping-v1")))
              .contractsFor(new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades"))
              .source();

  /** The ten selected headers, in the order a real bhavcopy has them, with one extra column. */
  static final String HEADER =
      "Security_cd,ISIN No.,Open Price,High Price,Low Price,Close Price,Total Traded Volume,"
          + "Number of Trades,Total Turnover,FACE VALUE,Extra";

  private BhavcopyContract() {}
}

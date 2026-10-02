package com.bondplatform.dataprocessing.contract.adapter.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The contract pairs this deployment loads, from {@code data-processing.contracts}.
 *
 * @param contracts one entry per dataset
 */
@ConfigurationProperties("data-processing")
public record ContractProperties(List<ContractPair> contracts) {

  /**
   * The two contract files for one dataset, named without the {@code .yaml} extension.
   *
   * @param source the stage-1 contract, for example {@code nsdl-security-json-v1}
   * @param mapping the stage-2 contract, for example {@code nsdl-security-mapping-v1}
   */
  public record ContractPair(String source, String mapping) {}
}

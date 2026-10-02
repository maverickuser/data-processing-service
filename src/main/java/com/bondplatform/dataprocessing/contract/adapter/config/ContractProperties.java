package com.bondplatform.dataprocessing.contract.adapter.config;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The contract pairs this deployment loads, from {@code data-processing.contracts}.
 *
 * @param contracts one entry per dataset; at least one
 */
@ConfigurationProperties("data-processing")
public record ContractProperties(List<ContractPair> contracts) {

  /**
   * Rejects a missing or empty list, so the application cannot start with no datasets.
   *
   * @throws IllegalStateException naming the property
   */
  public ContractProperties(@Nullable List<ContractPair> contracts) {
    if (contracts == null || contracts.isEmpty()) {
      throw new IllegalStateException(
          "data-processing.contracts must list at least one source and mapping contract pair");
    }
    this.contracts = List.copyOf(contracts);
  }

  /**
   * The two contract files for one dataset, named without the {@code .yaml} extension.
   *
   * @param source the stage-1 contract, for example {@code nsdl-security-json-v1}
   * @param mapping the stage-2 contract, for example {@code nsdl-security-mapping-v1}
   */
  public record ContractPair(String source, String mapping) {

    private static final Pattern CONTRACT_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * Rejects a missing name or one that is not a plain contract name.
     *
     * @throws IllegalStateException naming the property
     */
    public ContractPair(@Nullable String source, @Nullable String mapping) {
      this.source = requireContractName("source", source);
      this.mapping = requireContractName("mapping", mapping);
    }

    private static String requireContractName(String property, @Nullable String name) {
      if (name == null || !CONTRACT_NAME.matcher(name).matches()) {
        throw new IllegalStateException(
            "data-processing.contracts[]."
                + property
                + " must be a contract name such as nsdl-security-json-v1 but is '"
                + name
                + "'");
      }
      return name;
    }
  }
}

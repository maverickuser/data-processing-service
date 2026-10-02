package com.bondplatform.dataprocessing.contract.adapter.config;

import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the rule set and the contract registry; an invalid contract stops the application. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContractProperties.class)
public class ContractConfiguration {

  /** Returns the fixed rule set that contracts may name. */
  @Bean
  public RuleRegistry ruleRegistry() {
    return RuleRegistry.standard();
  }

  /** Loads and validates every configured contract pair. */
  @Bean
  public ContractRegistry contractRegistry(ContractProperties properties, RuleRegistry rules) {
    return new ClasspathContractCatalog(new YamlContractLoader(), new ContractValidator(rules))
        .load(properties.contracts());
  }
}

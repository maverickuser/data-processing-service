package com.bondplatform.dataprocessing.admission.adapter.config;

import com.bondplatform.dataprocessing.admission.domain.SubmissionValidator;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the submission validator to the datasets that have contracts. */
@Configuration(proxyBeanMethods = false)
public class AdmissionConfiguration {

  /**
   * Returns a validator accepting exactly the datasets with configured contracts; a dataset without
   * admission rules stops the application.
   */
  @Bean
  public SubmissionValidator submissionValidator(ContractRegistry contracts) {
    return new SubmissionValidator(contracts.datasets());
  }
}

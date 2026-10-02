package com.bondplatform.dataprocessing.admission.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.domain.SubmissionResult;
import com.bondplatform.dataprocessing.admission.domain.SubmissionValidator;
import com.bondplatform.dataprocessing.contract.adapter.config.ClasspathContractCatalog;
import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdmissionConfigurationTest {

  @Test
  void validatorAcceptsOnlyDatasetsThatHaveContracts() {
    ContractRegistry nsdlOnly =
        new ClasspathContractCatalog(
                new YamlContractLoader(), new ContractValidator(RuleRegistry.standard()))
            .load(List.of(new ContractPair("nsdl-security-json-v1", "nsdl-security-mapping-v1")));

    SubmissionValidator validator = new AdmissionConfiguration().submissionValidator(nsdlOnly);

    assertThat(validator.validate(SubmissionEvents.nsdl("run_202", "INE121A07QY9"), "run_202"))
        .isInstanceOf(SubmissionResult.Valid.class);
    assertThat(validator.validate(SubmissionEvents.bse("run_101", "2026-09-21"), "run_101"))
        .isInstanceOf(SubmissionResult.Invalid.class);
  }
}

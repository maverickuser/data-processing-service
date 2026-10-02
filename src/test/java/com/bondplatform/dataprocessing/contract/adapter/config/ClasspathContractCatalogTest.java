package com.bondplatform.dataprocessing.contract.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.application.UnknownDatasetException;
import com.bondplatform.dataprocessing.contract.domain.ContractFormatException;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.InvalidContractException;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Test cases U-CON-01 to U-CON-04 through the startup path. */
class ClasspathContractCatalogTest {

  private static final ContractPair BSE =
      new ContractPair("bse-debt-bhavcopy-csv-v1", "bse-debt-bhavcopy-mapping-v1");
  private static final ContractPair NSDL =
      new ContractPair("nsdl-security-json-v1", "nsdl-security-mapping-v1");
  private static final DatasetUrn BSE_DATASET =
      new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades");
  private static final DatasetUrn NSDL_DATASET =
      new DatasetUrn("urn:bond-platform:dataset:nsdl-security");

  private final YamlContractLoader loader = new YamlContractLoader();
  private final ContractValidator validator = new ContractValidator(RuleRegistry.standard());

  @Test
  void theCommittedContractsLoadAndAreValid() {
    ContractRegistry registry =
        new ClasspathContractCatalog(loader, validator).load(List.of(BSE, NSDL));

    assertThat(registry.datasets()).containsExactlyInAnyOrder(BSE_DATASET, NSDL_DATASET);
    PinnedContracts bse = registry.contractsFor(BSE_DATASET);
    assertThat(bse.source().id().name()).isEqualTo("bse-debt-bhavcopy-csv-v1");
    assertThat(bse.mapping().id().name()).isEqualTo("bse-debt-bhavcopy-mapping-v1");
    assertThat(bse.sourceHash()).startsWith("sha256:").hasSize(71);
    assertThat(bse.mappingHash()).startsWith("sha256:").isNotEqualTo(bse.sourceHash());
    assertThat(registry.contractsFor(NSDL_DATASET).mapping().collections()).hasSize(5);
  }

  @Test
  void unknownDatasetIsRejected() {
    ContractRegistry registry = new ClasspathContractCatalog(loader, validator).load(List.of(BSE));

    assertThatThrownBy(() -> registry.contractsFor(NSDL_DATASET))
        .isInstanceOf(UnknownDatasetException.class)
        .hasMessageContaining("urn:bond-platform:dataset:nsdl-security");
  }

  @Test
  void twoContractsForOneDatasetAreRejected() {
    ClasspathContractCatalog catalog = new ClasspathContractCatalog(loader, validator);

    assertThatThrownBy(() -> catalog.load(List.of(BSE, BSE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bse-debt-trades");
  }

  @Test
  void missingContractFileIsReportedByName() {
    ClasspathContractCatalog catalog = new ClasspathContractCatalog(loader, validator);

    assertThatThrownBy(() -> catalog.load(List.of(new ContractPair("absent-v1", "absent-map-v1"))))
        .isInstanceOf(ContractFormatException.class)
        .hasMessageContaining("/contracts/absent-v1.yaml does not exist");
  }

  @Test
  void mappingPairedWithTheWrongSourceContractIsRejected() {
    ClasspathContractCatalog catalog = new ClasspathContractCatalog(loader, validator);
    ContractPair mismatched = new ContractPair(BSE.source(), NSDL.mapping());

    assertThatThrownBy(() -> catalog.load(List.of(mismatched)))
        .isInstanceOf(InvalidContractException.class)
        .hasMessageContaining("nsdl-security-mapping-v1")
        .hasMessageContaining("is paired with 'bse-debt-bhavcopy-csv-v1'");
  }

  @Test
  void fileWhoseContentDeclaresAnotherNameIsRejected() {
    Map<String, String> files = new HashMap<>();
    files.put("/contracts/renamed-v1.yaml", committed(BSE.source()));
    files.put("/contracts/" + BSE.mapping() + ".yaml", committed(BSE.mapping()));
    ClasspathContractCatalog catalog = catalogOver(files);

    assertThatThrownBy(() -> catalog.load(List.of(new ContractPair("renamed-v1", BSE.mapping()))))
        .isInstanceOf(ContractFormatException.class)
        .hasMessageContaining("renamed-v1.yaml declares the contract bse-debt-bhavcopy-csv-v1");
  }

  @Test
  void changedContractContentChangesItsHash() {
    Map<String, String> files = new HashMap<>();
    files.put("/contracts/" + BSE.source() + ".yaml", committed(BSE.source()) + "\n# edited\n");
    files.put("/contracts/" + BSE.mapping() + ".yaml", committed(BSE.mapping()));

    PinnedContracts edited = catalogOver(files).load(List.of(BSE)).contractsFor(BSE_DATASET);
    PinnedContracts original =
        new ClasspathContractCatalog(loader, validator)
            .load(List.of(BSE))
            .contractsFor(BSE_DATASET);

    assertThat(edited.sourceHash()).isNotEqualTo(original.sourceHash());
    assertThat(edited.mappingHash()).isEqualTo(original.mappingHash());
  }

  @Test
  void unreadableContractFileIsReported() {
    ClasspathContractCatalog catalog =
        new ClasspathContractCatalog(loader, validator, resource -> new FailingInputStream());

    assertThatThrownBy(() -> catalog.load(List.of(BSE)))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("bse-debt-bhavcopy-csv-v1.yaml");
  }

  private ClasspathContractCatalog catalogOver(Map<String, String> files) {
    return new ClasspathContractCatalog(
        loader,
        validator,
        resource -> {
          String text = files.get(resource);
          return text == null
              ? null
              : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        });
  }

  private static String committed(String contractName) {
    String resource = "/contracts/" + contractName + ".yaml";
    try (InputStream stream = ClasspathContractCatalogTest.class.getResourceAsStream(resource)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A stream whose every read fails, standing in for an unreadable file. */
  private static final class FailingInputStream extends InputStream {

    @Override
    public int read() throws IOException {
      throw new IOException("disk gone");
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      throw new IOException("disk gone");
    }
  }
}

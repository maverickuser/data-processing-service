package com.bondplatform.dataprocessing.contract.adapter.config;

import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.ContractFormatException;
import com.bondplatform.dataprocessing.contract.domain.ContractHash;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/**
 * Loads, validates, and fingerprints the contract files packaged with the application.
 *
 * <p>Any missing file, wrong shape, unknown rule, or inconsistency fails here, at startup, with a
 * message naming the contract, so a broken contract can never process data.
 */
public final class ClasspathContractCatalog {

  private static final String DIRECTORY = "/contracts/";

  private final YamlContractLoader loader;
  private final ContractValidator validator;
  private final Function<String, InputStream> resources;

  /** Creates a catalog that reads from the application's classpath. */
  public ClasspathContractCatalog(YamlContractLoader loader, ContractValidator validator) {
    this(loader, validator, ClasspathContractCatalog.class::getResourceAsStream);
  }

  /** Creates a catalog that reads resources through the given function; null means not found. */
  ClasspathContractCatalog(
      YamlContractLoader loader,
      ContractValidator validator,
      Function<String, InputStream> resources) {
    this.loader = loader;
    this.validator = validator;
    this.resources = resources;
  }

  /** Builds the registry for the given contract pairs. */
  public ContractRegistry load(List<ContractPair> pairs) {
    return new ContractRegistry(pairs.stream().map(this::pin).toList());
  }

  private PinnedContracts pin(ContractPair pair) {
    byte[] sourceBytes = read(pair.source());
    byte[] mappingBytes = read(pair.mapping());
    SourceContract source = loader.loadSourceContract(text(sourceBytes));
    MappingContract mapping = loader.loadMappingContract(text(mappingBytes));
    requireNameMatchesFile(pair.source(), source.id().name());
    requireNameMatchesFile(pair.mapping(), mapping.id().name());
    validator.requireValid(source, mapping);
    return new PinnedContracts(
        source, ContractHash.of(sourceBytes), mapping, ContractHash.of(mappingBytes));
  }

  private static void requireNameMatchesFile(String fileName, String contractName) {
    if (!fileName.equals(contractName)) {
      throw new ContractFormatException(
          "id", "file " + fileName + ".yaml declares the contract " + contractName);
    }
  }

  private static String text(byte[] contractBytes) {
    return new String(contractBytes, StandardCharsets.UTF_8);
  }

  private byte[] read(String contractName) {
    String resource = DIRECTORY + contractName + ".yaml";
    try (InputStream stream = resources.apply(resource)) {
      if (stream == null) {
        throw new ContractFormatException(
            "(root)", "contract file " + resource + " does not exist");
      }
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + resource, e);
    }
  }
}

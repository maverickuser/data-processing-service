package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.canonical.adapter.json.StrictJsonReader;
import com.bondplatform.dataprocessing.contract.adapter.config.ClasspathContractCatalog;
import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** The real NSDL source and mapping contracts and the sample payloads, loaded for tests. */
public final class NsdlContract {

  /** The real NSDL contracts, source and mapping. */
  public static final PinnedContracts PINNED =
      new ClasspathContractCatalog(
              new YamlContractLoader(), new ContractValidator(RuleRegistry.standard()))
          .load(List.of(new ContractPair("nsdl-security-json-v1", "nsdl-security-mapping-v1")))
          .contractsFor(new DatasetUrn("urn:bond-platform:dataset:nsdl-security"));

  /** The real NSDL source contract. */
  public static final SourceContract.Json CONTRACT = (SourceContract.Json) PINNED.source();

  private NsdlContract() {}

  /** Returns a sample payload from {@code fixtures/nsdl}, parsed. */
  public static JsonValue sample(String fileName) {
    return parse(sampleBytes(fileName));
  }

  /** Returns JSON text parsed strictly. */
  public static JsonValue parse(String json) {
    return parse(json.getBytes(StandardCharsets.UTF_8));
  }

  private static JsonValue parse(byte[] content) {
    return switch (new StrictJsonReader().read(content)) {
      case JsonRead.Parsed parsed -> parsed.root();
      case JsonRead.Malformed malformed -> throw new IllegalArgumentException(malformed.detail());
    };
  }

  private static byte[] sampleBytes(String fileName) {
    try (InputStream in =
        Objects.requireNonNull(
            NsdlContract.class.getResourceAsStream("/fixtures/nsdl/" + fileName), fileName)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

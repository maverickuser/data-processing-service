package com.bondplatform.dataprocessing.contract.application;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The contracts this deployment processes data under, looked up by dataset.
 *
 * <p>It is built once at startup from validated contracts and never changes afterwards.
 */
public final class ContractRegistry {

  private final Map<DatasetUrn, PinnedContracts> contractsByDataset;

  /**
   * Creates the registry.
   *
   * @throws IllegalStateException if two contract pairs claim the same dataset
   */
  public ContractRegistry(List<PinnedContracts> contracts) {
    Map<DatasetUrn, PinnedContracts> byDataset = new LinkedHashMap<>();
    for (PinnedContracts pinned : contracts) {
      DatasetUrn dataset = pinned.source().dataset();
      if (byDataset.putIfAbsent(dataset, pinned) != null) {
        throw new IllegalStateException("More than one contract is registered for " + dataset);
      }
    }
    this.contractsByDataset = Map.copyOf(byDataset);
  }

  /**
   * Returns the contracts for the dataset a submission names.
   *
   * @throws UnknownDatasetException if no contract is registered for it
   */
  public PinnedContracts contractsFor(DatasetUrn dataset) {
    PinnedContracts pinned = contractsByDataset.get(dataset);
    if (pinned == null) {
      throw new UnknownDatasetException(dataset);
    }
    return pinned;
  }

  /** Returns every dataset this deployment accepts. */
  public Set<DatasetUrn> datasets() {
    return contractsByDataset.keySet();
  }
}

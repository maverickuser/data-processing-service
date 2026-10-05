package com.bondplatform.dataprocessing.pipeline.application;

import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.source.domain.Manifest;

/** Checks shared by the dataset handlers before they process a job. */
final class DeployedContracts {

  private DeployedContracts() {}

  /**
   * Fails the attempt if the job's pinned contracts are not the deployed ones. The attempt is
   * retried, so a deployment that restores them can still process the job.
   *
   * @throws IllegalStateException if a contract or its content hash differs
   */
  static void require(PinnedContracts deployed, PinnedContractVersions pinned) {
    requireSame(
        pinned.source(), pinned.sourceHash(), deployed.source().id(), deployed.sourceHash());
    requireSame(
        pinned.mapping(), pinned.mappingHash(), deployed.mapping().id(), deployed.mappingHash());
  }

  /** Returns a manifest input; the manifest was checked to carry the submission's inputs. */
  static String input(Manifest manifest, String name) {
    String value = manifest.inputs().get(name);
    if (value == null) {
      throw new IllegalStateException("The checked manifest has no input " + name);
    }
    return value;
  }

  /**
   * Fails unless the pinned contract is the deployed one. The content hash catches a version that
   * was changed in place after the job was accepted (LLD section 6.2).
   */
  private static void requireSame(
      ContractId pinned, String pinnedHash, ContractId deployed, String deployedHash) {
    if (!pinned.equals(deployed)) {
      throw new IllegalStateException(
          "The job is pinned to " + pinned + ", but this deployment holds " + deployed);
    }
    if (!pinnedHash.equals(deployedHash)) {
      throw new IllegalStateException(
          "The job is pinned to "
              + pinned
              + " with hash "
              + pinnedHash
              + ", but this deployment's copy has hash "
              + deployedHash);
    }
  }
}

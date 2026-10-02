package com.bondplatform.dataprocessing.contract.application;

import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;

/**
 * The two contracts a job is processed under, with the content hash of each.
 *
 * <p>A job records these at admission and uses the same versions for every retry.
 *
 * @param sourceHash fingerprint of the source contract file
 * @param mappingHash fingerprint of the mapping contract file
 */
public record PinnedContracts(
    SourceContract source, String sourceHash, MappingContract mapping, String mappingHash) {}

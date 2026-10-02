package com.bondplatform.dataprocessing.publication.domain;

import org.jspecify.annotations.Nullable;

/**
 * One asset securing a security, as observed in the source. Every value is optional.
 *
 * @param collateralDescription the source's description of the security interest
 */
public record CollateralAsset(
    @Nullable String assetType, @Nullable String collateralDescription, @Nullable String remarks) {}

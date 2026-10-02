package com.bondplatform.dataprocessing.architecture.fixture.crossfeature.alpha.adapter;

import com.bondplatform.dataprocessing.architecture.fixture.crossfeature.beta.application.internal.BetaInternal;

/** Breaks U-ARCH-02: reaches into another feature's internal package. */
public class AlphaAdapterUsingBetaInternal {
  BetaInternal other = new BetaInternal();
}

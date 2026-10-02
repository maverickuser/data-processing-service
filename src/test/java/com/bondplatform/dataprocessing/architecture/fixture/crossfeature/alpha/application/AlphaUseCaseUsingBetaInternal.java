package com.bondplatform.dataprocessing.architecture.fixture.crossfeature.alpha.application;

import com.bondplatform.dataprocessing.architecture.fixture.crossfeature.beta.application.internal.BetaInternal;

/** Breaks U-ARCH-02: reaches into another feature's internal package. */
public class AlphaUseCaseUsingBetaInternal {
  BetaInternal other = new BetaInternal();
}

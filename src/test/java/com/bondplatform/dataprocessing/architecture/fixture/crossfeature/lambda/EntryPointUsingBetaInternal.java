package com.bondplatform.dataprocessing.architecture.fixture.crossfeature.lambda;

import com.bondplatform.dataprocessing.architecture.fixture.crossfeature.beta.application.internal.BetaInternal;

/** Breaks U-ARCH-02: the entry-point exemption covers adapters only. */
public class EntryPointUsingBetaInternal {
  BetaInternal other = new BetaInternal();
}

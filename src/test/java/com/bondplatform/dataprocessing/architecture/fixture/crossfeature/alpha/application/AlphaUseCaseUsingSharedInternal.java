package com.bondplatform.dataprocessing.architecture.fixture.crossfeature.alpha.application;

import com.bondplatform.dataprocessing.architecture.fixture.crossfeature.shared.application.internal.SharedInternal;

/** Breaks U-ARCH-02: the shared exemption covers adapters only. */
public class AlphaUseCaseUsingSharedInternal {
  SharedInternal other = new SharedInternal();
}

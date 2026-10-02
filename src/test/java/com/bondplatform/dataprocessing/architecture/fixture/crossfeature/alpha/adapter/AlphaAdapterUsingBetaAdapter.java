package com.bondplatform.dataprocessing.architecture.fixture.crossfeature.alpha.adapter;

import com.bondplatform.dataprocessing.architecture.fixture.crossfeature.beta.adapter.BetaAdapter;

/** Breaks U-ARCH-02: reaches into another feature's adapter. */
public class AlphaAdapterUsingBetaAdapter {
  BetaAdapter other = new BetaAdapter();
}

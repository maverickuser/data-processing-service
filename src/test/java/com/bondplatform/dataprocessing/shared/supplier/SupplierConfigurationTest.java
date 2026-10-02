package com.bondplatform.dataprocessing.shared.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SupplierConfigurationTest {

  private final SupplierConfiguration configuration = new SupplierConfiguration();

  @Test
  void clockIsUtc() {
    assertThat(configuration.clock().getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void idSupplierReturnsDistinctIdentifiers() {
    IdSupplier idSupplier = configuration.idSupplier();

    assertThat(idSupplier.nextId()).isNotEqualTo(idSupplier.nextId());
  }
}

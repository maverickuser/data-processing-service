package com.bondplatform.dataprocessing.shared.supplier;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the production clock and identifier source. */
@Configuration(proxyBeanMethods = false)
public class SupplierConfiguration {

  /** Returns the UTC system clock. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** Returns a source of random UUIDs. */
  @Bean
  public IdSupplier idSupplier() {
    return UUID::randomUUID;
  }
}

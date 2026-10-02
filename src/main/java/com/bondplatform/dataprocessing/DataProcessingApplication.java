package com.bondplatform.dataprocessing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot configuration root for the data processing service.
 *
 * <p>Every Lambda entry point starts this same application context, so all five functions share one
 * build artifact and one wiring.
 */
@SpringBootApplication
public class DataProcessingApplication {

  /** Starts the application context for local runs; Lambda entry points start it themselves. */
  public static void main(String[] args) {
    SpringApplication.run(DataProcessingApplication.class, args);
  }
}

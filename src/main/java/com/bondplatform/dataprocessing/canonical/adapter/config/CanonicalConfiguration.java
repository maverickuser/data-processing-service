package com.bondplatform.dataprocessing.canonical.adapter.config;

import com.bondplatform.dataprocessing.canonical.adapter.aws.S3CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.adapter.json.StrictJsonReader;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/** Wires canonical-file storage to the shared S3 client, and the JSON source reader. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanonicalProperties.class)
public class CanonicalConfiguration {

  /**
   * Returns the store; temporary files go to the JVM's temporary directory, {@code /tmp} on Lambda.
   */
  @Bean
  public S3CanonicalFileStore s3CanonicalFileStore(S3Client s3, CanonicalProperties canonical) {
    return new S3CanonicalFileStore(
        s3, canonical.bucket(), Path.of(System.getProperty("java.io.tmpdir")));
  }

  /** Returns the reader stage 1 uses for JSON source files. */
  @Bean
  public StrictJsonReader strictJsonReader() {
    return new StrictJsonReader();
  }
}

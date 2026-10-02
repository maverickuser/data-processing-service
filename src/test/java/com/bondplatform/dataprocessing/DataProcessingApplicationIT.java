package com.bondplatform.dataprocessing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Verifies that the application context starts with the committed configuration. */
@SpringBootTest
class DataProcessingApplicationIT {

  @Autowired private ApplicationContext context;

  @Test
  void startsApplicationContext() {
    assertThat(context.getBean(DataProcessingApplication.class)).isNotNull();
  }
}

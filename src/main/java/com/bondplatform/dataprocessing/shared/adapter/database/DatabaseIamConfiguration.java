package com.bondplatform.dataprocessing.shared.adapter.database;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * Switches the application's connection pool to RDS IAM authentication when {@code
 * data-processing.database.iam-authentication} is true, as it is in AWS (LLD section 23.5). Tests
 * and local runs leave it off and log in with the configured password.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("data-processing.database.iam-authentication")
public class DatabaseIamConfiguration {

  /**
   * Gives every Hikari pool a token provider for its configured URL and user. Static, so the
   * post-processor exists before the data source it changes.
   */
  @Bean
  static BeanPostProcessor iamAuthentication(Environment environment) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource pool) {
          RdsUtilities rds =
              RdsUtilities.builder()
                  .region(
                      Region.of(environment.getRequiredProperty("data-processing.database.region")))
                  .build();
          pool.setCredentialsProvider(
              new RdsIamCredentials(rds, pool.getJdbcUrl(), pool.getUsername()));
        }
        return bean;
      }
    };
  }
}

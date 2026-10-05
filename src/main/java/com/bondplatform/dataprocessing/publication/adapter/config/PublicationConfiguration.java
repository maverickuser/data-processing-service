package com.bondplatform.dataprocessing.publication.adapter.config;

import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.publication.application.DailyMarketSummaryRepository;
import com.bondplatform.dataprocessing.publication.application.PublishDailyMarketSummaries;
import com.bondplatform.dataprocessing.publication.application.PublishSecurityDetails;
import com.bondplatform.dataprocessing.publication.application.SecurityCollectionRepository;
import com.bondplatform.dataprocessing.publication.application.SecurityRepository;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the publication use cases with their configured event source. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublicationProperties.class)
public class PublicationConfiguration {

  /** Publishes completely evaluated CSV files. */
  @Bean
  public PublishDailyMarketSummaries publishDailyMarketSummaries(
      SecurityRepository securities,
      DailyMarketSummaryRepository summaries,
      JobCompletion completion,
      OutboxEventStore outbox,
      IdSupplier ids,
      Clock clock,
      PublicationProperties properties) {
    return new PublishDailyMarketSummaries(
        securities, summaries, completion, outbox, ids, clock, properties.source());
  }

  /** Publishes completely evaluated JSON requests. */
  @Bean
  public PublishSecurityDetails publishSecurityDetails(
      SecurityRepository securities,
      SecurityCollectionRepository collections,
      JobCompletion completion,
      Clock clock) {
    return new PublishSecurityDetails(securities, collections, completion, clock);
  }
}

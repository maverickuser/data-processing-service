package com.bondplatform.dataprocessing.outbox.domain;

/** The queues this service sends to. The names are stored in the outbox table. */
public enum OutboxDestination {
  /** The service's own FIFO queue of jobs to process. */
  FILE_PROCESSING,
  /** The externally owned queue that asks the fetch service for a security's details. */
  SECURITY_DETAILS
}

package com.bondplatform.dataprocessing.job.application;

/** What a worker should do with the queue message after running a job's attempt. */
public enum RunResult {
  /** The job finished, successfully or not: acknowledge the message. */
  FINISHED,
  /** The job had already finished, so the message was a redelivery: acknowledge it. */
  ALREADY_FINISHED,
  /** No job has this ID: acknowledge the message, which can never succeed. */
  UNKNOWN_JOB,
  /** The attempt failed temporarily: report failure, so the queue delivers the message again. */
  RETRY_LATER;

  /** Returns whether the queue message is done with. */
  public boolean acknowledgesMessage() {
    return this != RETRY_LATER;
  }
}

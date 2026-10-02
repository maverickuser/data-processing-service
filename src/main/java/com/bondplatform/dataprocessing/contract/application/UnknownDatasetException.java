package com.bondplatform.dataprocessing.contract.application;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;

/** Thrown when a submission names a dataset this service has no contract for. */
public class UnknownDatasetException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception for the given dataset. */
  public UnknownDatasetException(DatasetUrn dataset) {
    super("No contract is registered for dataset " + dataset);
  }
}

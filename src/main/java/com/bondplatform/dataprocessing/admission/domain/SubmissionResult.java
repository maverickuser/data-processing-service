package com.bondplatform.dataprocessing.admission.domain;

import java.util.List;

/** The outcome of validating a submission: the typed submission, or every reason it is rejected. */
public sealed interface SubmissionResult {

  /** The submission satisfies every rule. */
  record Valid(Submission submission) implements SubmissionResult {}

  /** The submission breaks at least one rule. */
  record Invalid(List<SubmissionError> errors) implements SubmissionResult {

    /** Copies the list so the result is immutable. */
    public Invalid {
      errors = List.copyOf(errors);
    }
  }
}

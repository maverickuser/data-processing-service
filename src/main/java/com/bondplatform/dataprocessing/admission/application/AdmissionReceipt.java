package com.bondplatform.dataprocessing.admission.application;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;

/**
 * The immutable record that a submission was accepted. A replay of the same submission returns the
 * same receipt, even after the job has finished.
 *
 * @param eventId the submission's CloudEvent {@code id}
 * @param runId the producer's delivery run
 * @param createdAt when the submission was first accepted
 */
public record AdmissionReceipt(JobId jobId, String eventId, String runId, Instant createdAt) {}

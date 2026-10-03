package com.bondplatform.dataprocessing.admission.adapter.web;

import java.net.URI;
import java.time.Instant;

/**
 * The body of a {@code 202} admission response, as documented in the submission OpenAPI file.
 *
 * @param status always {@code ACCEPTED}: the admission outcome, not the job's current state
 * @param eventId the submission's CloudEvent {@code id}
 * @param statusUrl the absolute URL of the public, read-only processing job
 */
record AdmissionReceiptResponse(
    String jobId, String status, String eventId, String runId, Instant createdAt, URI statusUrl) {}

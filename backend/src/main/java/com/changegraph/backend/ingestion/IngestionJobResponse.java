package com.changegraph.backend.ingestion;

import java.time.Instant;
import java.util.UUID;

public record IngestionJobResponse(
        UUID id,
        String status,
        String requestedCommitSha,
        String progressMessage,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public static IngestionJobResponse from(IngestionJob job) {
        return new IngestionJobResponse(
                job.getId(),
                job.getStatus().name(),
                job.getRequestedCommitSha(),
                job.getProgressMessage(),
                job.getErrorCode(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt());
    }
}

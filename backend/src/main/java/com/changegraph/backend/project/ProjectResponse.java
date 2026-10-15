package com.changegraph.backend.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        boolean repositoryConnected,
        RepositorySummary repository,
        String graphStatus,
        Instant createdAt,
        Instant updatedAt,
        ActivitySummary latestIngestion,
        ActivitySummary latestAnalysis) {

    public record RepositorySummary(
            long githubRepositoryId,
            String ownerName,
            String repositoryName,
            String defaultBranch,
            String repositoryUrl,
            String visibility,
            Instant lastSyncedAt) {
    }

    public record ActivitySummary(UUID id, String status, String detail, Instant createdAt) {
    }
}

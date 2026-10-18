package com.changegraph.backend.analysis;

import java.time.Instant;
import java.util.UUID;

public record AnalysisSummary(
        UUID id,
        String kind,
        String status,
        String commitSha,
        Integer pullRequestNumber,
        String riskLevel,
        Integer riskScore,
        Instant createdAt,
        Instant completedAt) {

    public static AnalysisSummary from(AnalysisRecord record) {
        String kind = record.getKind();
        String status = record.getStatus();
        return new AnalysisSummary(
                record.getId(),
                kind == null || kind.isBlank() ? "GRAPH" : kind,
                status == null || status.isBlank() ? "COMPLETED" : status,
                record.getCommitSha(),
                record.getPullRequestNumber(),
                record.getRiskLevel(),
                record.getRiskScore(),
                record.getCreatedAt(),
                record.getCompletedAt());
    }
}

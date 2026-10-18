package com.changegraph.backend.incident;

import java.time.Instant;
import java.util.List;

public final class IncidentResponses {

    private IncidentResponses() {
    }

    public record EvidenceView(
            String id,
            String evidenceType,
            String relationship,
            String verificationStatus,
            String confidence,
            String summary,
            String sourceType,
            boolean synthetic,
            String functionId) {
    }

    public record IncidentSummary(
            String id,
            String title,
            String summary,
            String sourceType,
            boolean synthetic,
            int evidenceCount,
            Instant updatedAt) {
    }

    public record IncidentDetail(
            String id,
            String title,
            String summary,
            String sourceType,
            boolean synthetic,
            List<EvidenceView> evidence,
            Instant updatedAt) {
    }
}

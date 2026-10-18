package com.changegraph.backend.workflow;

import java.util.List;

public record CodeReview(
        String summary,
        List<ReviewFinding> findings,
        List<String> uncertainties,
        List<String> investigationSteps) {

    public boolean hasActionableIssues() {
        return findings.stream().anyMatch(ReviewFinding::actionable);
    }

    public record ReviewFinding(
            String id,
            String severity,
            String category,
            String title,
            String detail,
            List<String> evidenceIds,
            List<String> affectedComponents,
            String recommendation,
            boolean actionable) {
    }
}

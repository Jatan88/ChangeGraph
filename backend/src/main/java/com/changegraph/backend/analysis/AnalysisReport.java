package com.changegraph.backend.analysis;

import java.util.List;
import java.util.UUID;

public record AnalysisReport(
        UUID id,
        String commitSha,
        Integer pullRequestNumber,
        String riskLevel,
        int riskScore,
        int riskScoreMax,
        String riskScoreInterpretation,
        List<String> riskReasons,
        List<ChangedFile> changedFiles,
        List<ChangedFunction> changedFunctions,
        List<ImpactedFunction> impactedFunctions,
        List<String> impactedServices,
        List<PathExplanation> paths,
        List<RecommendedTest> recommendedTests,
        List<RelatedIncident> relatedIncidents,
        List<String> uncertainties) {

    public record ChangedFile(String path, String changeType) {
    }

    public record ChangedFunction(String id, String name, String signature, String filePath, String changeType) {
    }

    public record ImpactedFunction(String id, String name, String signature, String filePath, String direction, int depth) {
    }

    public record PathExplanation(String fromId, String relationship, String toId, String explanation) {
    }

    public record RecommendedTest(String id, String name, String filePath, boolean direct, String reason) {
    }

    public record EvidenceView(
            String id,
            String evidenceType,
            String relationship,
            String verificationStatus,
            String confidence,
            boolean synthetic,
            String sourceType,
            String summary) {
    }

    public record RelatedIncident(
            String id,
            String title,
            String summary,
            boolean synthetic,
            String sourceType,
            List<EvidenceView> evidence) {
    }
}

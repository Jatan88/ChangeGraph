package com.changegraph.backend.agent;

import java.util.List;

public record AgentReport(
        String commitSha,
        Integer pullRequestNumber,
        String summary,
        String riskLevel,
        int riskScore,
        int riskScoreMax,
        String riskScoreInterpretation,
        List<RiskFactor> riskFactors,
        List<ComponentRef> changedComponents,
        List<ComponentRef> impactedComponents,
        List<ImpactPath> impactPaths,
        List<TestRecommendation> recommendedTests,
        List<IncidentRef> relatedIncidents,
        List<EvidenceRef> evidence,
        List<String> uncertainties,
        List<InvestigationStep> investigationSummary,
        String agentStatus) {

    public record RiskFactor(String statement, String kind, List<String> evidenceIds) {
    }

    public record ComponentRef(String id, String kind, String name, String detail) {
    }

    public record ImpactPath(String fromId, String relationship, String toId, String explanation) {
    }

    public record TestRecommendation(String id, String name, boolean direct, String reason, List<String> evidenceIds) {
    }

    public record IncidentRef(String id, String title, String summary, boolean synthetic, String sourceType) {
    }

    public record EvidenceRef(
            String id,
            String incidentId,
            String evidenceType,
            String relationship,
            String verificationStatus,
            String confidence,
            boolean synthetic,
            String summary) {
    }

    public record InvestigationStep(String tool, String summary, String query) {
    }
}

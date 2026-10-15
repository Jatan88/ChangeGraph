package com.changegraph.backend.analysis;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactAndRiskTest {

    private final RiskScoringService scoring = new RiskScoringService();

    @Test
    void traversalIsBoundedAndCycleSafe() {
        CodeGraph graph = new CodeGraph();
        graph.mergeNode(NodeType.FUNCTION, "a", Map.of("signature", "a()", "className", "SvcA", "isTest", false));
        graph.mergeNode(NodeType.FUNCTION, "b", Map.of("signature", "b()", "className", "SvcB", "isTest", false));
        graph.mergeEdge(EdgeType.CALLS, "a", "b", Map.of());
        graph.mergeEdge(EdgeType.CALLS, "b", "a", Map.of());

        ImpactTraversal.Result result = ImpactTraversal.traverse(graph, List.of("a"), 5, 5, 10);
        long mentionsOfB = result.hits().stream().filter(hit -> hit.functionId().equals("b")).count();
        assertEquals(2, mentionsOfB);
        assertEquals(2, result.hits().size());
        assertFalse(result.truncated());
    }

    @Test
    void recommendationsAndSyntheticEvidenceFollowGraphFacts() {
        CodeGraph graph = new CodeGraph();
        graph.mergeNode(NodeType.COMMIT, "abc1234", Map.of("sha", "abc1234", "limitations", ""));
        graph.mergeNode(NodeType.FUNCTION, "pay", Map.of(
                "name", "retryPayment", "signature", "retryPayment()", "filePath", "Pay.java", "className", "payment.Pay", "isTest", false, "unresolvedCallCount", 1));
        graph.mergeNode(NodeType.FUNCTION, "caller", Map.of(
                "name", "run", "signature", "run()", "filePath", "Run.java", "className", "payment.Run", "isTest", false, "unresolvedCallCount", 0));
        graph.mergeNode(NodeType.SERVICE, "payment.Pay", Map.of("name", "Pay", "basis", "Service"));
        graph.mergeNode(NodeType.SERVICE, "payment.Run", Map.of("name", "Run", "basis", "Service"));
        graph.mergeNode(NodeType.TEST, "direct", Map.of("name", "coversRetry()", "filePath", "PayTest.java", "signature", "coversRetry()"));
        graph.mergeNode(NodeType.TEST, "extra", Map.of("name", "coversCaller()", "filePath", "RunTest.java", "signature", "coversCaller()"));
        graph.mergeEdge(EdgeType.CHANGES_FUNCTION, "abc1234", "pay", Map.of("changeType", "MODIFIED"));
        graph.mergeEdge(EdgeType.CHANGES_FILE, "abc1234", "Pay.java", Map.of("changeType", "MODIFIED"));
        graph.mergeEdge(EdgeType.CALLS, "caller", "pay", Map.of());
        graph.mergeEdge(EdgeType.TESTS, "direct", "pay", Map.of());
        graph.mergeEdge(EdgeType.TESTS, "extra", "caller", Map.of());
        graph.mergeNode(NodeType.INCIDENT, "synthetic-payment-retry", Map.of(
                "title", "Duplicate ledger entries during payment retries",
                "summary", "Synthetic example",
                "sourceType", "SYNTHETIC",
                "isSynthetic", true));
        graph.mergeNode(NodeType.EVIDENCE, "report", Map.of(
                "evidenceType", "INCIDENT_REPORT",
                "verificationStatus", "VERIFIED",
                "confidence", "HIGH",
                "summary", "Synthetic incident report",
                "sourceType", "SYNTHETIC",
                "isSynthetic", true,
                "functionId", "pay"));
        graph.mergeNode(NodeType.EVIDENCE, "cause", Map.of(
                "evidenceType", "HUMAN_REVIEWED_FINDING",
                "verificationStatus", "UNREVIEWED",
                "confidence", "MEDIUM",
                "summary", "Synthetic hypothesis",
                "sourceType", "SYNTHETIC",
                "isSynthetic", true,
                "functionId", "pay"));
        graph.mergeEdge(EdgeType.REPORTS_ON, "report", "synthetic-payment-retry", Map.of());
        graph.mergeEdge(EdgeType.SUPPORTS_CAUSE, "cause", "synthetic-payment-retry", Map.of());

        AnalysisReport report = AnalysisReportBuilder.build(
                UUID.randomUUID(), graph, "abc1234", null, scoring, 3, 2, 20);

        assertEquals(1, report.changedFunctions().size());
        assertTrue(report.impactedFunctions().stream().anyMatch(function -> function.id().equals("caller") && function.direction().equals("CALLER")));
        assertTrue(report.recommendedTests().stream().anyMatch(test -> test.direct() && test.id().equals("direct")));
        assertTrue(report.recommendedTests().stream().anyMatch(test -> !test.direct() && test.id().equals("extra")));
        assertEquals(1, report.relatedIncidents().size());
        assertTrue(report.relatedIncidents().get(0).synthetic());
        assertEquals("SYNTHETIC", report.relatedIncidents().get(0).sourceType());
        assertTrue(report.riskReasons().stream().anyMatch(reason -> reason.contains("directly changed")));
        assertTrue(report.riskReasons().stream().anyMatch(reason -> reason.contains("synthetic")));
        assertTrue(report.uncertainties().stream().anyMatch(item -> item.contains("not prove")));
        assertFalse(report.riskReasons().stream().anyMatch(reason -> reason.toLowerCase().contains("definitely broken")));
        assertTrue(report.riskScore() >= 25);
    }

    @Test
    void scoreReasonsStayEmptyWhenNothingChanged() {
        RiskScoringService.Score score = scoring.score(0, 0, 0, false, 0, 0, false);
        assertEquals("LOW", score.level());
        assertEquals(0, score.score());
        assertTrue(score.reasons().isEmpty());
    }
}

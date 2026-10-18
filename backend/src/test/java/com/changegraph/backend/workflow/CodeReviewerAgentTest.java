package com.changegraph.backend.workflow;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeReviewerAgentTest {

    @Test
    void reviewUsesGraphEvidenceAndDoesNotVerifyIncidents() {
        CodeGraph graph = new CodeGraph();
        String sha = "abc1234";
        graph.mergeNode(NodeType.COMMIT, sha, Map.of("message", "Remove charge"));
        graph.mergeNode(NodeType.FILE, "src/Pay.java", Map.of("path", "src/Pay.java"));
        graph.mergeNode(NodeType.FUNCTION, "src/Pay.java#charge", Map.of(
                "name", "charge",
                "signature", "charge(int)",
                "filePath", "src/Pay.java",
                "className", "Billing"));
        graph.mergeNode(NodeType.FUNCTION, "src/Api.java#checkout", Map.of(
                "name", "checkout",
                "signature", "checkout()",
                "filePath", "src/Api.java"));
        graph.mergeNode(NodeType.SERVICE, "Billing", Map.of("name", "Billing"));
        graph.mergeEdge(EdgeType.CHANGES_FILE, sha, "src/Pay.java", Map.of("changeType", "modified"));
        graph.mergeEdge(EdgeType.CHANGES_FUNCTION, sha, "src/Pay.java#charge", Map.of("changeType", "removed"));
        graph.mergeEdge(EdgeType.CALLS, "src/Api.java#checkout", "src/Pay.java#charge", Map.of());
        graph.mergeNode(NodeType.INCIDENT, "inc-1", Map.of(
                "title", "Double charge",
                "summary", "Charged twice",
                "isSynthetic", false,
                "sourceType", "HISTORICAL"));
        graph.mergeNode(NodeType.EVIDENCE, "ev-1", Map.of(
                "functionId", "src/Pay.java#charge",
                "evidenceType", "REPORT",
                "verificationStatus", "UNVERIFIED",
                "confidence", "LOW",
                "isSynthetic", false,
                "summary", "Ticket mentioned charge"));
        graph.mergeEdge(EdgeType.REPORTS_ON, "ev-1", "inc-1", Map.of());

        CodeReview review = new CodeReviewerAgent().review(UUID.randomUUID(), graph, sha, null, "feature/pay", "release");

        assertTrue(review.findings().stream().anyMatch(finding ->
                "BREAKING".equals(finding.category()) && finding.detail().contains("charge(int)")));
        assertTrue(review.findings().stream().anyMatch(finding ->
                "TESTS".equals(finding.category()) && finding.actionable()));
        assertTrue(review.findings().stream().anyMatch(finding ->
                "HISTORICAL".equals(finding.category())
                        && finding.detail().contains("UNVERIFIED")
                        && finding.detail().contains("did not mark the incident as verified")));
        assertTrue(review.findings().stream().anyMatch(finding ->
                "IMPACT".equals(finding.category())
                        && finding.actionable()
                        && finding.affectedComponents().stream().anyMatch(item -> item.contains("Billing"))));
        assertFalse(review.findings().stream().anyMatch(finding -> finding.detail().contains("marked the incident as verified.")));
        assertEqualsUnverified(graph);
        assertTrue(review.summary().contains("feature/pay"));
        assertTrue(review.summary().contains("release"));
    }

    private static void assertEqualsUnverified(CodeGraph graph) {
        assertTrue(graph.node("ev-1").orElseThrow().property("verificationStatus").equals("UNVERIFIED"));
    }
}

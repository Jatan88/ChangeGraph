package com.changegraph.backend.incident;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentGraphSupportTest {

    @Test
    void listsIncidentsWithLinkedEvidence() {
        CodeGraph graph = new CodeGraph();
        graph.mergeNode(NodeType.INCIDENT, "outage-1", Map.of(
                "title", "Retry storm",
                "summary", "Duplicate charges",
                "sourceType", "MANUAL",
                "isSynthetic", false));
        graph.mergeNode(NodeType.FUNCTION, "pay", Map.of("name", "retryPayment", "signature", "retryPayment()"));
        graph.mergeNode(NodeType.EVIDENCE, "report-1", Map.of(
                "evidenceType", "INCIDENT_REPORT",
                "verificationStatus", "VERIFIED",
                "confidence", "HIGH",
                "summary", "Pager report",
                "sourceType", "MANUAL",
                "isSynthetic", false,
                "functionId", "pay"));
        graph.mergeEdge(EdgeType.REPORTS_ON, "report-1", "outage-1", Map.of());

        assertEquals(1, IncidentGraphSupport.summaries(graph).size());
        var detail = IncidentGraphSupport.detail(graph, "outage-1").orElseThrow();
        assertEquals("Retry storm", detail.title());
        assertEquals(1, detail.evidence().size());
        assertEquals("REPORTS_ON", detail.evidence().get(0).relationship());
        assertTrue(IncidentGraphSupport.evidenceIdsForIncident(graph, "outage-1").contains("report-1"));
    }
}

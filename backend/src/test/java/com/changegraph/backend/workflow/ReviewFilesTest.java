package com.changegraph.backend.workflow;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewFilesTest {

    @Test
    void aChangedSourceFileMayGainATestBesideTheExistingTests() {
        CodeGraph graph = new CodeGraph();
        graph.mergeNode(NodeType.FUNCTION, "pay", Map.of("filePath", "server/payment-service.ts"));
        graph.mergeEdge(EdgeType.CHANGES_FUNCTION, "abc1234", "pay", Map.of("changeType", "MODIFIED"));
        graph.mergeNode(NodeType.TEST, "checkout-test", Map.of("filePath", "server/tests/checkout.test.ts", "name", "checkout"));

        Set<String> allowed = ReviewFiles.allowed(Set.of("server/payment-service.ts", "README.md"), graph, "abc1234");

        assertTrue(allowed.contains("server/payment-service.ts"));
        assertTrue(allowed.contains("README.md"));
        assertTrue(allowed.contains("server/tests/payment-service.test.ts"));
        assertFalse(allowed.contains("server/tests/checkout.test.ts"));
    }
}

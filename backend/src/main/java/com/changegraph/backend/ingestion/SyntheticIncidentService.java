package com.changegraph.backend.ingestion;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.GraphNode;
import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.graph.NodeType;
import com.changegraph.backend.project.GraphLifecycle;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectGraph;
import com.changegraph.backend.user.UserAccount;
import com.changegraph.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SyntheticIncidentService {

    private final ProjectAccess access;
    private final GraphStore graphStore;

    public SyntheticIncidentService(ProjectAccess access, GraphStore graphStore) {
        this.access = access;
        this.graphStore = graphStore;
    }

    public SyntheticSeedResponse seed(UserAccount owner, UUID projectId) {
        var project = access.requireProject(owner, projectId);
        ProjectGraph mapping = access.requireGraph(project.getId());
        if (mapping.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before adding synthetic examples.");
        }
        CodeGraph existing = graphStore.load(project.getId(), mapping.getGraphName(), 500);
        CodeGraph graph = new CodeGraph();
        List<String> linked = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        linkScenario(existing, graph, linked, notes,
                "synthetic-payment-retry",
                "Duplicate ledger entries during payment retries",
                "Synthetic example only. Duplicate ledger entries were described during payment retries.",
                List.of("retryPayment", "retry_payment", "recordLedgerEntry", "record_ledger_entry"),
                List.of(
                        evidence("synthetic-payment-retry-report", "INCIDENT_REPORT", "VERIFIED", "HIGH", EdgeType.REPORTS_ON,
                                "Synthetic incident report: duplicate ledger entries were recorded during payment retries. This is not a production incident."),
                        evidence("synthetic-payment-retry-hypothesis", "HUMAN_REVIEWED_FINDING", "UNREVIEWED", "MEDIUM", EdgeType.SUPPORTS_CAUSE,
                                "Synthetic hypothesis: idempotency protection was missing. This is not a verified root cause."),
                        evidence("synthetic-payment-retry-resolution", "FIX_COMMIT", "UNREVIEWED", "LOW", EdgeType.SUPPORTS_RESOLUTION,
                                "Synthetic note: an idempotency key is a possible resolution. No real commit is claimed.")));
        linkScenario(existing, graph, linked, notes,
                "synthetic-payment-notification",
                "Missing payment notification",
                "Synthetic example only. A payment was described without a notification.",
                List.of("sendNotification", "send_notification", "notifyPayment", "notify_payment"),
                List.of(
                        evidence("synthetic-payment-notification-report", "INCIDENT_REPORT", "VERIFIED", "HIGH", EdgeType.REPORTS_ON,
                                "Synthetic incident report: a payment notification was missing. This is not a production incident."),
                        evidence("synthetic-payment-notification-hypothesis", "HUMAN_REVIEWED_FINDING", "UNREVIEWED", "LOW", EdgeType.SUPPORTS_CAUSE,
                                "Synthetic hypothesis: notification handling failed. This is not a verified root cause."),
                        evidence("synthetic-payment-notification-test", "REGRESSION_TEST", "UNREVIEWED", "MEDIUM", EdgeType.VALIDATES_FIX,
                                "Synthetic note: a regression test would be relevant if the graph already contains one. This record does not invent a test.")));
        graphStore.merge(project.getId(), mapping.getGraphName(), graph);
        notes.add("These incidents are synthetic. Analysis mentions one only when a parsed function in this project matches its declared symbol.");
        return new SyntheticSeedResponse(
                "Synthetic payment examples were saved on this project's graph.",
                List.of("synthetic-payment-retry", "synthetic-payment-notification"),
                linked,
                notes);
    }

    private void linkScenario(
            CodeGraph existing,
            CodeGraph graph,
            List<String> linked,
            List<String> notes,
            String scenarioKey,
            String title,
            String summary,
            List<String> symbols,
            List<EvidenceSeed> evidenceSeeds) {
        graph.mergeNode(NodeType.INCIDENT, scenarioKey, Map.of(
                "title", title,
                "summary", summary,
                "sourceType", "SYNTHETIC",
                "isSynthetic", true,
                "scenarioKey", scenarioKey));
        List<GraphNode> matches = new ArrayList<>();
        for (String symbol : symbols) {
            matches.addAll(matchingFunctions(existing, symbol));
        }
        String functionId = "";
        if (matches.size() == 1) {
            functionId = matches.get(0).getId();
            linked.add(functionId);
        } else if (matches.size() > 1) {
            notes.add(scenarioKey + " matched multiple functions, so it was not linked to one of them.");
        } else {
            notes.add(scenarioKey + " did not match a parsed payment function, so analysis will not treat it as related.");
        }
        for (EvidenceSeed seed : evidenceSeeds) {
            graph.mergeNode(NodeType.EVIDENCE, seed.id(), Map.of(
                    "evidenceType", seed.type(),
                    "verificationStatus", seed.status(),
                    "confidence", seed.confidence(),
                    "summary", seed.summary(),
                    "sourceType", "SYNTHETIC",
                    "isSynthetic", true,
                    "functionId", functionId));
            graph.mergeEdge(seed.relationship(), seed.id(), scenarioKey, Map.of());
        }
    }

    private static List<GraphNode> matchingFunctions(CodeGraph graph, String methodName) {
        return graph.nodesOf(NodeType.FUNCTION).stream()
                .filter(node -> methodName.equals(node.property("name")))
                .filter(node -> {
                    String haystack = (node.property("className") + " " + node.property("filePath")).toLowerCase();
                    return haystack.contains("payment");
                })
                .toList();
    }

    private static EvidenceSeed evidence(String id, String type, String status, String confidence, EdgeType relationship, String summary) {
        return new EvidenceSeed(id, type, status, confidence, relationship, summary);
    }

    private record EvidenceSeed(String id, String type, String status, String confidence, EdgeType relationship, String summary) {
    }

    public record SyntheticSeedResponse(String message, List<String> scenarioKeys, List<String> linkedFunctionIds, List<String> notes) {
    }
}

package com.changegraph.backend.incident;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.GraphEdge;
import com.changegraph.backend.graph.GraphNode;
import com.changegraph.backend.graph.NodeType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class IncidentGraphSupport {

    private IncidentGraphSupport() {
    }

    static List<IncidentResponses.IncidentSummary> summaries(CodeGraph graph) {
        Map<String, List<IncidentResponses.EvidenceView>> evidence = evidenceByIncident(graph);
        List<IncidentResponses.IncidentSummary> items = new ArrayList<>();
        for (GraphNode incident : graph.nodesOf(NodeType.INCIDENT)) {
            List<IncidentResponses.EvidenceView> linked = evidence.getOrDefault(incident.getId(), List.of());
            items.add(new IncidentResponses.IncidentSummary(
                    incident.getId(),
                    incident.property("title"),
                    incident.property("summary"),
                    incident.property("sourceType"),
                    incident.flag("isSynthetic"),
                    linked.size(),
                    updatedAt(incident)));
        }
        items.sort(Comparator.comparing(IncidentResponses.IncidentSummary::id));
        return items;
    }

    static Optional<IncidentResponses.IncidentDetail> detail(CodeGraph graph, String incidentId) {
        GraphNode incident = graph.node(incidentId).orElse(null);
        if (incident == null || incident.getType() != NodeType.INCIDENT) {
            return Optional.empty();
        }
        List<IncidentResponses.EvidenceView> linked = evidenceByIncident(graph).getOrDefault(incidentId, List.of());
        return Optional.of(new IncidentResponses.IncidentDetail(
                incident.getId(),
                incident.property("title"),
                incident.property("summary"),
                incident.property("sourceType"),
                incident.flag("isSynthetic"),
                linked,
                updatedAt(incident)));
    }

    static List<String> evidenceIdsForIncident(CodeGraph graph, String incidentId) {
        List<String> ids = new ArrayList<>();
        for (EdgeType type : IncidentCatalog.EVIDENCE_RELATIONSHIPS) {
            for (GraphEdge edge : graph.edgesOf(type)) {
                if (incidentId.equals(edge.getTargetId())) {
                    ids.add(edge.getSourceId());
                }
            }
        }
        return ids;
    }

    private static Map<String, List<IncidentResponses.EvidenceView>> evidenceByIncident(CodeGraph graph) {
        Map<String, List<IncidentResponses.EvidenceView>> grouped = new LinkedHashMap<>();
        Map<String, Map<String, IncidentResponses.EvidenceView>> deduped = new LinkedHashMap<>();
        for (EdgeType type : IncidentCatalog.EVIDENCE_RELATIONSHIPS) {
            for (GraphEdge edge : graph.edgesOf(type)) {
                GraphNode evidence = graph.node(edge.getSourceId()).orElse(null);
                if (evidence == null || evidence.getType() != NodeType.EVIDENCE) {
                    continue;
                }
                deduped
                        .computeIfAbsent(edge.getTargetId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(evidence.getId(), toEvidence(evidence, type.name()));
            }
        }
        for (Map.Entry<String, Map<String, IncidentResponses.EvidenceView>> entry : deduped.entrySet()) {
            List<IncidentResponses.EvidenceView> list = new ArrayList<>(entry.getValue().values());
            list.sort(Comparator.comparing(IncidentResponses.EvidenceView::id));
            grouped.put(entry.getKey(), list);
        }
        return grouped;
    }

    private static IncidentResponses.EvidenceView toEvidence(GraphNode evidence, String relationship) {
        return new IncidentResponses.EvidenceView(
                evidence.getId(),
                evidence.property("evidenceType"),
                relationship,
                evidence.property("verificationStatus"),
                evidence.property("confidence"),
                evidence.property("summary"),
                evidence.property("sourceType"),
                evidence.flag("isSynthetic"),
                evidence.property("functionId"));
    }

    private static Instant updatedAt(GraphNode node) {
        String raw = node.property("updatedAt");
        if (raw.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
        }
    }
}

package com.changegraph.backend.analysis;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.GraphEdge;
import com.changegraph.backend.graph.GraphNode;
import com.changegraph.backend.graph.NodeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AnalysisReportBuilder {

    private AnalysisReportBuilder() {
    }

    public static AnalysisReport build(
            UUID id,
            CodeGraph graph,
            String commitSha,
            Integer pullRequestNumber,
            RiskScoringService scoring,
            int upstreamDepth,
            int downstreamDepth,
            int maxNodes) {
        List<AnalysisReport.ChangedFile> changedFiles = new ArrayList<>();
        for (GraphEdge edge : graph.edgesOf(EdgeType.CHANGES_FILE)) {
            if (commitSha.equals(edge.getSourceId())) {
                changedFiles.add(new AnalysisReport.ChangedFile(edge.getTargetId(), edge.property("changeType")));
            }
        }
        List<AnalysisReport.ChangedFunction> changedFunctions = new ArrayList<>();
        List<String> changedIds = new ArrayList<>();
        for (GraphEdge edge : graph.edgesOf(EdgeType.CHANGES_FUNCTION)) {
            if (!commitSha.equals(edge.getSourceId())) {
                continue;
            }
            GraphNode function = graph.node(edge.getTargetId()).orElse(null);
            if (function == null) {
                continue;
            }
            changedIds.add(function.getId());
            changedFunctions.add(new AnalysisReport.ChangedFunction(
                    function.getId(),
                    function.property("name"),
                    function.property("signature"),
                    function.property("filePath"),
                    edge.property("changeType")));
        }

        ImpactTraversal.Result impact = ImpactTraversal.traverse(graph, changedIds, upstreamDepth, downstreamDepth, maxNodes);
        List<AnalysisReport.ImpactedFunction> impacted = new ArrayList<>();
        for (ImpactTraversal.Hit hit : impact.hits()) {
            GraphNode function = graph.node(hit.functionId()).orElse(null);
            if (function == null) {
                continue;
            }
            impacted.add(new AnalysisReport.ImpactedFunction(
                    function.getId(),
                    function.property("name"),
                    function.property("signature"),
                    function.property("filePath"),
                    hit.direction(),
                    hit.depth()));
        }

        Set<String> relevantIds = new LinkedHashSet<>(changedIds);
        impact.hits().forEach(hit -> relevantIds.add(hit.functionId()));
        Map<String, String> directionById = new LinkedHashMap<>();
        changedIds.forEach(functionId -> directionById.put(functionId, "DIRECT"));
        impact.hits().forEach(hit -> directionById.putIfAbsent(hit.functionId(), hit.direction()));

        List<AnalysisReport.RecommendedTest> tests = new ArrayList<>();
        Set<String> directTestIds = new LinkedHashSet<>();
        Set<String> coveredChanged = new LinkedHashSet<>();
        for (GraphEdge edge : graph.edgesOf(EdgeType.TESTS)) {
            if (!relevantIds.contains(edge.getTargetId())) {
                continue;
            }
            GraphNode test = graph.node(edge.getSourceId()).orElse(null);
            if (test == null) {
                continue;
            }
            boolean direct = changedIds.contains(edge.getTargetId());
            if (direct) {
                directTestIds.add(test.getId());
                coveredChanged.add(edge.getTargetId());
            }
            String reason = direct
                    ? "This test has a TESTS relationship to a directly changed function."
                    : "This test has a TESTS relationship to a potentially impacted function (" + directionById.getOrDefault(edge.getTargetId(), "related") + ").";
            tests.add(new AnalysisReport.RecommendedTest(
                    test.getId(),
                    test.property("name"),
                    test.property("filePath"),
                    direct,
                    reason));
        }
        List<AnalysisReport.RecommendedTest> additional = tests.stream()
                .filter(test -> test.direct() || !directTestIds.contains(test.id()))
                .toList();

        int missingTests = (int) changedIds.stream().filter(functionId -> !coveredChanged.contains(functionId)).count();
        List<AnalysisReport.RelatedIncident> incidents = relatedIncidents(graph, relevantIds);
        int verifiedReports = (int) incidents.stream()
                .flatMap(incident -> incident.evidence().stream())
                .filter(evidence -> evidence.synthetic()
                        && "INCIDENT_REPORT".equals(evidence.evidenceType())
                        && "VERIFIED".equals(evidence.verificationStatus())
                        && "REPORTS_ON".equals(evidence.relationship()))
                .count();

        boolean unresolved = graph.nodesOf(NodeType.FUNCTION).stream()
                .anyMatch(node -> relevantIds.contains(node.getId()) && node.number("unresolvedCallCount") > 0);
        String limitationText = graph.node(commitSha).map(node -> node.property("limitations")).orElse("");
        boolean uncertainty = unresolved || !limitationText.isBlank() || impact.truncated();

        RiskScoringService.Score score = scoring.score(
                changedFunctions.size(),
                (int) impact.hits().stream().filter(hit -> "CALLER".equals(hit.direction())).count(),
                (int) impact.hits().stream().filter(hit -> "DOWNSTREAM".equals(hit.direction())).count(),
                !impact.services().isEmpty() && impact.services().size() > 1,
                missingTests,
                verifiedReports > 0 ? 1 : 0,
                uncertainty);

        List<String> uncertainties = new ArrayList<>();
        if (changedFunctions.isEmpty()) {
            uncertainties.add("No function-level changes were identified for this commit. File changes may be in an unsupported language, unparsed, or outside the diff hunks.");
        }
        if (unresolved) {
            uncertainties.add("Some method calls in the relevant functions could not be resolved to a single target and were not turned into CALLS relationships.");
        }
        if (impact.truncated()) {
            uncertainties.add("Impact traversal stopped at the configured node limit, so more distant callers or callees may exist.");
        }
        if (!limitationText.isBlank()) {
            uncertainties.add(limitationText);
        }
        uncertainties.add("Impact statements are potential effects inferred from graph paths, not proof that behavior is broken.");
        for (AnalysisReport.RelatedIncident incident : incidents) {
            boolean unreviewedCause = incident.evidence().stream()
                    .anyMatch(evidence -> "SUPPORTS_CAUSE".equals(evidence.relationship()) && !"VERIFIED".equals(evidence.verificationStatus()));
            if (unreviewedCause) {
                uncertainties.add("Incident \"" + incident.title() + "\" has an unverified cause hypothesis. A report that the incident occurred does not prove that cause.");
            }
        }

        List<AnalysisReport.PathExplanation> paths = impact.paths().stream()
                .map(step -> new AnalysisReport.PathExplanation(
                        step.fromId(),
                        step.relationship(),
                        step.toId(),
                        ImpactTraversal.displayName(graph, step.fromId()) + " CALLS " + ImpactTraversal.displayName(graph, step.toId())
                                + ". This is a parsed relationship, so the callee is potentially impacted."))
                .toList();

        return new AnalysisReport(
                id,
                commitSha,
                pullRequestNumber,
                score.level(),
                score.score(),
                RiskScoringService.SCORE_MAX,
                RiskScoringService.INTERPRETATION,
                score.reasons(),
                changedFiles,
                changedFunctions,
                impacted,
                impact.services(),
                paths,
                additional,
                incidents,
                uncertainties);
    }

    private static List<AnalysisReport.RelatedIncident> relatedIncidents(CodeGraph graph, Set<String> relevantIds) {
        Map<String, List<AnalysisReport.EvidenceView>> evidenceByIncident = new LinkedHashMap<>();
        Map<String, Boolean> linked = new LinkedHashMap<>();
        for (EdgeType type : List.of(EdgeType.REPORTS_ON, EdgeType.SUPPORTS_CAUSE, EdgeType.SUPPORTS_RESOLUTION, EdgeType.VALIDATES_FIX)) {
            for (GraphEdge edge : graph.edgesOf(type)) {
                GraphNode evidence = graph.node(edge.getSourceId()).orElse(null);
                if (evidence == null || evidence.getType() != NodeType.EVIDENCE) {
                    continue;
                }
                String functionId = evidence.property("functionId");
                boolean matches = !functionId.isBlank() && relevantIds.contains(functionId);
                linked.merge(edge.getTargetId(), matches, Boolean::logicalOr);
                evidenceByIncident.computeIfAbsent(edge.getTargetId(), ignored -> new ArrayList<>()).add(new AnalysisReport.EvidenceView(
                        evidence.getId(),
                        evidence.property("evidenceType"),
                        type.name(),
                        evidence.property("verificationStatus"),
                        evidence.property("confidence"),
                        evidence.flag("isSynthetic"),
                        evidence.property("sourceType"),
                        evidence.property("summary")));
            }
        }
        List<AnalysisReport.RelatedIncident> incidents = new ArrayList<>();
        for (GraphNode incident : graph.nodesOf(NodeType.INCIDENT)) {
            if (!linked.getOrDefault(incident.getId(), false)) {
                continue;
            }
            incidents.add(new AnalysisReport.RelatedIncident(
                    incident.getId(),
                    incident.property("title"),
                    incident.property("summary"),
                    incident.flag("isSynthetic"),
                    incident.property("sourceType"),
                    evidenceByIncident.getOrDefault(incident.getId(), List.of())));
        }
        return incidents;
    }
}

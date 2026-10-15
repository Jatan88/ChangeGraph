package com.changegraph.backend.agent;

import com.changegraph.backend.analysis.RiskScoringService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AgentReportGenerator {

    private final RiskScoringService scoring;

    public AgentReportGenerator(RiskScoringService scoring) {
        this.scoring = scoring;
    }

    public AgentReport generate(AgentState state, String agentStatus) {
        List<Map<String, String>> changedFunctions = new ArrayList<>();
        List<Map<String, String>> changedFiles = new ArrayList<>();
        List<Map<String, Object>> hits = new ArrayList<>();
        List<Map<String, String>> paths = new ArrayList<>();
        List<Map<String, Object>> tests = new ArrayList<>();
        List<Map<String, Object>> incidents = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, String>> services = new ArrayList<>();
        List<Map<String, String>> dependencies = new ArrayList<>();
        boolean truncated = false;
        int unresolved = 0;
        Set<String> known = new LinkedHashSet<>();
        known.add(state.commitSha());

        for (ToolObservation observation : state.observations()) {
            known.addAll(observation.evidenceIds());
            changedFiles.addAll(stringMaps(observation.facts().get("files")));
            changedFunctions.addAll(stringMaps(observation.facts().get("functions")));
            hits.addAll(objectMaps(observation.facts().get("hits")));
            paths.addAll(stringMaps(observation.facts().get("paths")));
            tests.addAll(objectMaps(observation.facts().get("tests")));
            incidents.addAll(objectMaps(observation.facts().get("incidents")));
            evidence.addAll(objectMaps(observation.facts().get("evidence")));
            services.addAll(stringMaps(observation.facts().get("services")));
            dependencies.addAll(stringMaps(observation.facts().get("dependencies")));
            if (Boolean.TRUE.equals(observation.facts().get("truncated"))) {
                truncated = true;
            }
            if (observation.facts().get("unresolvedCallCount") instanceof Number number) {
                unresolved += number.intValue();
            }
            for (Map<String, Object> incident : objectMaps(observation.facts().get("incidents"))) {
                evidence.addAll(objectMaps(incident.get("evidence")));
            }
        }

        Set<String> changedIds = new LinkedHashSet<>();
        List<AgentReport.ComponentRef> changedComponents = new ArrayList<>();
        for (Map<String, String> file : changedFiles) {
            if (!known.contains(file.get("path"))) {
                continue;
            }
            changedComponents.add(new AgentReport.ComponentRef(file.get("path"), "FILE", file.get("path"), file.getOrDefault("changeType", "")));
        }
        for (Map<String, String> function : changedFunctions) {
            if (!known.contains(function.get("id"))) {
                continue;
            }
            changedIds.add(function.get("id"));
            changedComponents.add(new AgentReport.ComponentRef(
                    function.get("id"), "FUNCTION", function.getOrDefault("signature", function.get("id")), function.getOrDefault("changeType", "")));
        }

        List<AgentReport.ComponentRef> impacted = new ArrayList<>();
        int callers = 0;
        int downstream = 0;
        for (Map<String, Object> hit : hits) {
            String id = text(hit.get("id"));
            if (!known.contains(id) || changedIds.contains(id)) {
                continue;
            }
            String direction = text(hit.get("direction"));
            if ("CALLER".equals(direction)) {
                callers++;
            } else if ("DOWNSTREAM".equals(direction)) {
                downstream++;
            }
            impacted.add(new AgentReport.ComponentRef(id, direction, text(hit.get("name")), "depth " + text(hit.get("depth"))));
        }

        List<AgentReport.ImpactPath> impactPaths = new ArrayList<>();
        for (Map<String, String> path : paths) {
            if (!known.contains(path.get("fromId")) || !known.contains(path.get("toId"))) {
                continue;
            }
            impactPaths.add(new AgentReport.ImpactPath(
                    path.get("fromId"),
                    path.getOrDefault("relationship", "CALLS"),
                    path.get("toId"),
                    "Observed CALLS edge in the project graph. The callee is potentially impacted."));
        }

        Set<String> coveredChanged = new LinkedHashSet<>();
        List<AgentReport.TestRecommendation> recommendations = new ArrayList<>();
        for (Map<String, Object> test : tests) {
            String id = text(test.get("id"));
            String target = text(test.get("targetId"));
            if (!known.contains(id)) {
                continue;
            }
            boolean direct = Boolean.TRUE.equals(test.get("direct"));
            if (direct) {
                coveredChanged.add(target);
            }
            recommendations.add(new AgentReport.TestRecommendation(
                    id, text(test.get("name")), direct, text(test.get("reason")), List.of(id, target).stream().filter(known::contains).toList()));
        }

        List<AgentReport.IncidentRef> related = new ArrayList<>();
        List<AgentReport.EvidenceRef> evidenceRefs = new ArrayList<>();
        Set<String> seenIncidents = new LinkedHashSet<>();
        Set<String> seenEvidence = new LinkedHashSet<>();
        int verifiedReports = 0;
        for (Map<String, Object> incident : incidents) {
            String id = text(incident.get("id"));
            if (!known.contains(id) || !seenIncidents.add(id)) {
                continue;
            }
            related.add(new AgentReport.IncidentRef(id, text(incident.get("title")), text(incident.get("summary")),
                    Boolean.TRUE.equals(incident.get("synthetic")), text(incident.get("sourceType"))));
        }
        for (Map<String, Object> item : evidence) {
            String id = text(item.get("id"));
            if (!known.contains(id) || !seenEvidence.add(id)) {
                continue;
            }
            evidenceRefs.add(new AgentReport.EvidenceRef(
                    id,
                    text(item.get("incidentId")),
                    text(item.get("evidenceType")),
                    text(item.get("relationship")),
                    text(item.get("verificationStatus")),
                    text(item.get("confidence")),
                    Boolean.TRUE.equals(item.get("synthetic")),
                    text(item.get("summary"))));
            if (Boolean.TRUE.equals(item.get("synthetic"))
                    && "INCIDENT_REPORT".equals(item.get("evidenceType"))
                    && "VERIFIED".equals(item.get("verificationStatus"))
                    && "REPORTS_ON".equals(item.get("relationship"))) {
                verifiedReports++;
            }
        }

        int missingTests = (int) changedIds.stream().filter(id -> !coveredChanged.contains(id)).count();
        boolean serviceBoundary = dependencies.size() > 0 || services.size() > 1;
        RiskScoringService.Score score = scoring.score(
                changedIds.size(), callers, downstream, serviceBoundary, missingTests, verifiedReports > 0 ? 1 : 0, truncated || unresolved > 0);

        List<AgentReport.RiskFactor> factors = new ArrayList<>();
        if (!changedIds.isEmpty()) {
            factors.add(factor(changedIds.size() + " function" + (changedIds.size() == 1 ? " was" : "s were") + " directly changed.",
                    "FACT", changedIds, known));
        }
        List<String> callerIds = impacted.stream().filter(item -> "CALLER".equals(item.kind())).map(AgentReport.ComponentRef::id).toList();
        if (!callerIds.isEmpty()) {
            factors.add(factor(callerIds.size() + " caller" + (callerIds.size() == 1 ? " is" : "s are") + " potentially impacted through CALLS edges.",
                    "FACT", callerIds, known));
        }
        List<String> downstreamIds = impacted.stream().filter(item -> "DOWNSTREAM".equals(item.kind())).map(AgentReport.ComponentRef::id).toList();
        if (!downstreamIds.isEmpty()) {
            factors.add(factor(downstreamIds.size() + " downstream function" + (downstreamIds.size() == 1 ? " is" : "s are") + " potentially impacted through CALLS edges.",
                    "FACT", downstreamIds, known));
        }
        if (!dependencies.isEmpty()) {
            List<String> serviceIds = new ArrayList<>();
            dependencies.forEach(edge -> {
                serviceIds.add(edge.get("fromId"));
                serviceIds.add(edge.get("toId"));
            });
            factors.add(factor("A recorded DEPENDS_ON edge connects services touched by this change.", "FACT", serviceIds, known));
        }
        if (missingTests > 0) {
            factors.add(factor(missingTests + " changed function" + (missingTests == 1 ? " has" : "s have") + " no direct TESTS edge in the graph.",
                    "UNKNOWN", changedIds.stream().filter(id -> !coveredChanged.contains(id)).toList(), known));
        }
        if (verifiedReports > 0) {
            List<String> reportIds = evidenceRefs.stream()
                    .filter(item -> "INCIDENT_REPORT".equals(item.evidenceType()) && "VERIFIED".equals(item.verificationStatus()))
                    .map(AgentReport.EvidenceRef::id)
                    .toList();
            factors.add(factor("A verified incident report is linked to an investigated function. A synthetic label means it is not a production incident.",
                    "HISTORICAL", reportIds, known));
        }
        for (AgentReport.EvidenceRef item : evidenceRefs) {
            if ("SUPPORTS_CAUSE".equals(item.relationship()) && !"VERIFIED".equals(item.verificationStatus())) {
                factors.add(factor("Evidence " + item.id() + " records an unverified cause. This is a hypothesis, not a confirmed root cause.",
                        "HYPOTHESIS", List.of(item.id()), known));
            }
        }
        if (!recommendations.isEmpty()) {
            factors.add(factor("Run the tests that the graph connects to the changed or impacted functions.",
                    "RECOMMENDATION", recommendations.stream().map(AgentReport.TestRecommendation::id).toList(), known));
        }

        List<String> uncertainties = new ArrayList<>();
        if (changedIds.isEmpty()) {
            uncertainties.add("No function-level changes were identified for this commit.");
        }
        if (truncated) {
            uncertainties.add("Dependency tracing stopped at its node limit.");
        }
        if (unresolved > 0) {
            uncertainties.add("At least one inspected function has unresolved method calls, so some dependencies may be missing.");
        }
        if (state.observations().stream().noneMatch(item -> "trace_function_dependencies".equals(item.tool()))) {
            uncertainties.add("Callers and callees were not traced, so impact paths are incomplete.");
        }
        uncertainties.add("Impact statements are potential effects inferred from graph paths, not proof that behavior is broken.");
        if ("DETERMINISTIC_FALLBACK".equals(agentStatus)) {
            uncertainties.add("AI-assisted reasoning was unavailable. This report uses deterministic graph analysis.");
        }

        String summary = ("DETERMINISTIC_FALLBACK".equals(agentStatus)
                ? "AI-assisted reasoning was unavailable, so this report uses deterministic graph analysis. "
                : "Investigation used the project graph. ")
                + changedIds.size() + " changed functions, " + impacted.size() + " potentially impacted functions, "
                + recommendations.size() + " related tests, and " + related.size() + " related incidents. Risk "
                + score.level() + " (" + score.score() + "/100).";

        List<AgentReport.InvestigationStep> steps = state.observations().stream()
                .map(item -> new AgentReport.InvestigationStep(item.tool(), item.summary()))
                .toList();

        return new AgentReport(
                state.commitSha(),
                state.pullRequestNumber(),
                summary,
                score.level(),
                score.score(),
                RiskScoringService.SCORE_MAX,
                RiskScoringService.INTERPRETATION,
                factors.stream().filter(factor -> !factor.evidenceIds().isEmpty() || "UNKNOWN".equals(factor.kind())).toList(),
                changedComponents,
                impacted,
                impactPaths,
                recommendations,
                related,
                evidenceRefs,
                uncertainties,
                steps,
                agentStatus);
    }

    private static AgentReport.RiskFactor factor(String statement, String kind, Iterable<String> ids, Set<String> known) {
        List<String> refs = new ArrayList<>();
        for (String id : ids) {
            if (id != null && known.contains(id) && !refs.contains(id)) {
                refs.add(id);
            }
        }
        return new AgentReport.RiskFactor(statement, kind, List.copyOf(refs));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> stringMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, String>> maps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> copy.put(String.valueOf(key), entry == null ? "" : String.valueOf(entry)));
                maps.add(copy);
            }
        }
        return maps;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
                maps.add(copy);
            }
        }
        return maps;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

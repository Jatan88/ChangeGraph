package com.changegraph.backend.workflow;

import com.changegraph.backend.agent.AgentState;
import com.changegraph.backend.agent.AgentToolRegistry;
import com.changegraph.backend.agent.GraphAgentTools;
import com.changegraph.backend.agent.ToolObservation;
import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.GraphEdge;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CodeReviewerAgent {

    private final AgentToolRegistry registry = new AgentToolRegistry(GraphAgentTools.all());

    public CodeReview review(
            UUID projectId,
            CodeGraph graph,
            String commitSha,
            Integer pullRequestNumber,
            String sourceBranch,
            String targetBranch) {
        AgentState state = new AgentState(projectId, commitSha, pullRequestNumber, graph, 8, 1);
        ToolObservation changes = run(state, "get_commit_changes", Map.of());
        run(state, "trace_function_dependencies", Map.of("direction", "both", "depth", "2"));
        ToolObservation tests = run(state, "find_related_tests", Map.of());
        ToolObservation incidents = run(state, "search_historical_incidents", Map.of());
        ToolObservation services = run(state, "inspect_service_dependencies", Map.of());

        List<Map<String, String>> files = stringMaps(changes.facts().get("files"));
        List<Map<String, String>> functions = stringMaps(changes.facts().get("functions"));
        List<Map<String, Object>> hits = objectMaps(observation(state, "trace_function_dependencies").facts().get("hits"));
        List<Map<String, Object>> relatedTests = objectMaps(tests.facts().get("tests"));
        List<Map<String, Object>> linkedIncidents = objectMaps(incidents.facts().get("incidents"));
        List<Map<String, String>> relatedServices = stringMaps(services.facts().get("services"));

        List<CodeReview.ReviewFinding> findings = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();
        uncertainties.add("Findings are inferences from the project graph. They are not proof that a function is broken.");
        uncertainties.add("Repository scripts were not executed.");
        if (Boolean.TRUE.equals(observation(state, "trace_function_dependencies").facts().get("truncated"))) {
            uncertainties.add("Dependency traversal stopped at its node limit.");
        }

        int index = 1;
        for (Map<String, String> function : functions) {
            if (!"removed".equalsIgnoreCase(function.get("changeType"))) {
                continue;
            }
            List<String> callers = callersOf(graph, function.get("id"));
            if (callers.isEmpty()) {
                continue;
            }
            findings.add(finding(
                    index++,
                    "HIGH",
                    "BREAKING",
                    "Removed function still has callers",
                    function.get("signature") + " was removed and still has " + callers.size() + " recorded caller"
                            + (callers.size() == 1 ? "" : "s") + ".",
                    callers,
                    List.of(function.get("id")),
                    "Update or remove the callers before merging. This was not rewritten automatically.",
                    true));
        }

        if (!functions.isEmpty() && hits.isEmpty() && findings.stream().noneMatch(item -> "IMPACT".equals(item.category()))) {
            findings.add(finding(
                    index++,
                    "LOW",
                    "IMPACT",
                    "No dependent functions were found",
                    "The bounded dependency trace did not find callers or callees beyond the changed functions.",
                    List.of(),
                    functions.stream().map(item -> item.get("id")).toList(),
                    "Confirm that calls outside the ingested file set are not affected.",
                    false));
        }
        if (!hits.isEmpty()) {
            List<String> affected = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                affected.add(String.valueOf(hit.get("name")));
            }
            for (Map<String, String> service : relatedServices) {
                affected.add(service.get("name"));
            }
            boolean reachesServices = !relatedServices.isEmpty();
            findings.add(finding(
                    index++,
                    reachesServices ? "HIGH" : "MEDIUM",
                    "IMPACT",
                    "Changes reach dependent components",
                    "The graph trace found " + hits.size() + " potentially impacted function"
                            + (hits.size() == 1 ? "" : "s")
                            + (relatedServices.isEmpty() ? "." : " and " + relatedServices.size() + " related service"
                            + (relatedServices.size() == 1 ? "." : "s.")),
                    affected,
                    affected,
                    reachesServices
                            ? "Preserve the behavior callers and downstream functions depend on. If this diff removes a guard, put that guard back, and add or update a test for the changed functions."
                            : "Review the dependent functions before merging.",
                    reachesServices));
        }

        boolean directTest = relatedTests.stream().anyMatch(test -> Boolean.TRUE.equals(test.get("direct")));
        if (!functions.isEmpty() && !directTest) {
            List<String> names = functions.stream().map(item -> item.get("signature")).toList();
            findings.add(finding(
                    index++,
                    "MEDIUM",
                    "TESTS",
                    "No direct test is linked to the changed functions",
                    "The project graph has no TESTS edge from a test to the changed functions.",
                    names,
                    names,
                    "Add a test that calls each changed function and place it with the existing tests.",
                    true));
        } else if (directTest) {
            List<String> names = relatedTests.stream()
                    .filter(test -> Boolean.TRUE.equals(test.get("direct")))
                    .map(test -> String.valueOf(test.get("name")))
                    .toList();
            findings.add(finding(
                    index++,
                    "INFO",
                    "TESTS",
                    "Existing tests cover changed functions",
                    "These tests are linked to changed functions and may need to be run or updated: " + String.join(", ", names) + ".",
                    names,
                    names,
                    "Run the linked tests. ChangeGraph did not execute them.",
                    false));
        }

        for (Map<String, Object> incident : linkedIncidents) {
            List<Map<String, Object>> evidence = objectMaps(incident.get("evidence"));
            List<String> evidenceIds = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            for (Map<String, Object> item : evidence) {
                evidenceIds.add(String.valueOf(item.get("id")));
                String status = String.valueOf(item.getOrDefault("verificationStatus", "UNKNOWN"));
                statuses.add(status);
            }
            boolean synthetic = Boolean.TRUE.equals(incident.get("synthetic"));
            findings.add(finding(
                    index++,
                    synthetic ? "LOW" : "HIGH",
                    "HISTORICAL",
                    "Historical evidence is linked to this change",
                    (synthetic ? "Synthetic incident. " : "")
                            + UntrustedText.data(String.valueOf(incident.get("title")), 160)
                            + ". Stored verification status: " + (statuses.isEmpty() ? "none" : String.join(", ", statuses))
                            + ". This review did not mark the incident as verified.",
                    evidenceIds,
                    List.of(String.valueOf(incident.get("id"))),
                    "Read the stored evidence and its verification status. Do not treat this finding as a newly verified incident.",
                    false));
        }

        if (functions.isEmpty() && !files.isEmpty()) {
            uncertainties.add("Changed files did not resolve to parsed functions, so call impact may be incomplete.");
        }

        String summary = "Reviewed " + functions.size() + " changed function" + (functions.size() == 1 ? "" : "s")
                + " from " + blank(sourceBranch, "the source branch")
                + " toward " + blank(targetBranch, "the target branch")
                + ". " + findings.stream().filter(CodeReview.ReviewFinding::actionable).count()
                + " actionable finding" + (findings.stream().filter(CodeReview.ReviewFinding::actionable).count() == 1 ? "" : "s")
                + ". Historical evidence was not marked verified.";
        List<String> steps = state.observations().stream().map(item -> item.tool() + ": " + item.summary()).toList();
        return new CodeReview(summary, List.copyOf(findings), List.copyOf(uncertainties), steps);
    }

    private ToolObservation run(AgentState state, String tool, Map<String, String> arguments) {
        ToolObservation observation = registry.require(tool).execute(state, arguments);
        state.add(observation, tool);
        return observation;
    }

    private static ToolObservation observation(AgentState state, String tool) {
        return state.observations().stream()
                .filter(item -> tool.equals(item.tool()))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private static List<String> callersOf(CodeGraph graph, String functionId) {
        List<String> callers = new ArrayList<>();
        for (GraphEdge edge : graph.edgesOf(EdgeType.CALLS)) {
            if (functionId.equals(edge.getTargetId())) {
                callers.add(edge.getSourceId());
            }
        }
        return callers;
    }

    private static CodeReview.ReviewFinding finding(
            int index,
            String severity,
            String category,
            String title,
            String detail,
            List<String> evidenceIds,
            List<String> affected,
            String recommendation,
            boolean actionable) {
        return new CodeReview.ReviewFinding(
                "f" + index,
                severity,
                category,
                title,
                detail,
                evidenceIds,
                affected,
                recommendation,
                actionable);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<Map<String, String>> stringMaps(Object value) {
        List<Map<String, String>> maps = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, String> copy = new LinkedHashMap<>();
                    map.forEach((key, entry) -> copy.put(String.valueOf(key), entry == null ? "" : String.valueOf(entry)));
                    maps.add(copy);
                }
            }
        }
        return maps;
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
                    maps.add(copy);
                }
            }
        }
        return maps;
    }
}

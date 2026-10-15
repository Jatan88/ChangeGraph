package com.changegraph.backend.agent;

import com.changegraph.backend.analysis.ImpactTraversal;
import com.changegraph.backend.common.Text;
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

public final class GraphAgentTools {

    private GraphAgentTools() {
    }

    public static List<AgentTool> all() {
        return List.of(
                commitChanges(),
                traceDependencies(),
                relatedTests(),
                historicalIncidents(),
                serviceDependencies(),
                inspectEvidence(),
                functionDetails());
    }

    private static AgentTool commitChanges() {
        return tool("get_commit_changes", "Changed files and functions recorded for this commit.", Set.of(), (state, args) -> {
            List<Map<String, String>> files = new ArrayList<>();
            List<Map<String, String>> functions = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (GraphEdge edge : state.graph().edgesOf(EdgeType.CHANGES_FILE)) {
                if (!state.commitSha().equals(edge.getSourceId())) {
                    continue;
                }
                files.add(Map.of("path", edge.getTargetId(), "changeType", edge.property("changeType")));
                ids.add(edge.getTargetId());
            }
            for (GraphEdge edge : state.graph().edgesOf(EdgeType.CHANGES_FUNCTION)) {
                if (!state.commitSha().equals(edge.getSourceId())) {
                    continue;
                }
                GraphNode function = state.graph().node(edge.getTargetId()).orElse(null);
                if (function == null || function.getType() != NodeType.FUNCTION) {
                    continue;
                }
                functions.add(functionView(function, edge.property("changeType")));
                ids.add(function.getId());
            }
            String message = state.graph().node(state.commitSha()).map(node -> Text.clamp(node.property("message"), 180)).orElse("");
            return observation("get_commit_changes",
                    "Recorded " + files.size() + " changed files and " + functions.size() + " changed functions from the commit graph.",
                    args, ids, Map.of("files", files, "functions", functions, "commitMessage", message, "ids", ids));
        });
    }

    private static AgentTool traceDependencies() {
        return tool("trace_function_dependencies",
                "Callers and callees of a changed function, using bounded CALLS edges already in the project graph.",
                Set.of("functionId", "direction", "depth"), (state, args) -> {
                    List<String> seeds = seeds(state, args.get("functionId"));
                    if (seeds.isEmpty()) {
                        return observation("trace_function_dependencies",
                                "No changed function was available to trace.", args, List.of(), Map.of("hits", List.of(), "paths", List.of(), "ids", List.of()));
                    }
                    String direction = args.getOrDefault("direction", "both");
                    int depth = depth(args.get("depth"));
                    int upstream = "downstream".equals(direction) ? 0 : depth;
                    int downstream = "callers".equals(direction) ? 0 : depth;
                    ImpactTraversal.Result result = ImpactTraversal.traverse(state.graph(), seeds, upstream, downstream, 20);
                    List<Map<String, Object>> hits = new ArrayList<>();
                    List<String> ids = new ArrayList<>(seeds);
                    for (ImpactTraversal.Hit hit : result.hits()) {
                        GraphNode node = state.graph().node(hit.functionId()).orElse(null);
                        if (node == null) {
                            continue;
                        }
                        Map<String, Object> view = new LinkedHashMap<>();
                        view.put("id", node.getId());
                        view.put("name", node.property("signature").isBlank() ? node.getId() : node.property("signature"));
                        view.put("direction", hit.direction());
                        view.put("depth", hit.depth());
                        hits.add(view);
                        ids.add(node.getId());
                    }
                    List<Map<String, String>> paths = new ArrayList<>();
                    for (ImpactTraversal.PathStep step : result.paths()) {
                        if (state.graph().node(step.fromId()).isEmpty() || state.graph().node(step.toId()).isEmpty()) {
                            continue;
                        }
                        paths.add(Map.of("fromId", step.fromId(), "relationship", step.relationship(), "toId", step.toId()));
                        ids.add(step.fromId());
                        ids.add(step.toId());
                    }
                    return observation("trace_function_dependencies",
                            "Traced " + hits.size() + " potentially impacted functions. "
                                    + (result.truncated() ? "The traversal stopped at its node limit." : "The traversal stayed inside the configured depth."),
                            args, ids, Map.of("hits", hits, "paths", paths, "truncated", result.truncated(), "ids", ids));
                });
    }

    private static AgentTool relatedTests() {
        return tool("find_related_tests",
                "Tests with a TESTS edge to a changed or already traced function. Direct edges are separate from inferred recommendations.",
                Set.of(), (state, args) -> {
                    Set<String> changed = changedFunctionIds(state);
                    Set<String> relevant = new LinkedHashSet<>(changed);
                    relevant.addAll(tracedIds(state));
                    List<Map<String, Object>> tests = new ArrayList<>();
                    List<String> ids = new ArrayList<>();
                    for (GraphEdge edge : state.graph().edgesOf(EdgeType.TESTS)) {
                        if (!relevant.contains(edge.getTargetId())) {
                            continue;
                        }
                        GraphNode test = state.graph().node(edge.getSourceId()).orElse(null);
                        if (test == null) {
                            continue;
                        }
                        boolean direct = changed.contains(edge.getTargetId());
                        Map<String, Object> view = new LinkedHashMap<>();
                        view.put("id", test.getId());
                        view.put("name", test.property("name").isBlank() ? test.getId() : test.property("name"));
                        view.put("direct", direct);
                        view.put("targetId", edge.getTargetId());
                        view.put("reason", direct
                                ? "A TESTS edge connects this test to a changed function."
                                : "A TESTS edge connects this test to a potentially impacted function, so running it is an inference from that dependency.");
                        tests.add(view);
                        ids.add(test.getId());
                        ids.add(edge.getTargetId());
                    }
                    return observation("find_related_tests",
                            "Found " + tests.size() + " tests connected to the investigated functions.",
                            args, ids, Map.of("tests", tests, "ids", ids));
                });
    }

    private static AgentTool historicalIncidents() {
        return tool("search_historical_incidents",
                "Incidents linked through evidence to changed or traced functions. Includes verification status and synthetic labels.",
                Set.of(), (state, args) -> {
                    Set<String> relevant = new LinkedHashSet<>(changedFunctionIds(state));
                    relevant.addAll(tracedIds(state));
                    List<Map<String, Object>> incidents = incidentsFor(state.graph(), relevant);
                    List<String> ids = new ArrayList<>();
                    for (Map<String, Object> incident : incidents) {
                        ids.add(String.valueOf(incident.get("id")));
                        for (Map<String, Object> item : objectMaps(incident.get("evidence"))) {
                            ids.add(String.valueOf(item.get("id")));
                        }
                    }
                    return observation("search_historical_incidents",
                            incidents.isEmpty()
                                    ? "No stored incident is linked to the investigated functions."
                                    : "Found " + incidents.size() + " linked incidents. Synthetic incidents stay labeled synthetic.",
                            args, ids, Map.of("incidents", incidents, "ids", ids));
                });
    }

    private static AgentTool serviceDependencies() {
        return tool("inspect_service_dependencies",
                "Service nodes and DEPENDS_ON edges already recorded for the investigated functions.",
                Set.of(), (state, args) -> {
                    Set<String> functionIds = new LinkedHashSet<>(changedFunctionIds(state));
                    functionIds.addAll(tracedIds(state));
                    Set<String> serviceIds = new LinkedHashSet<>();
                    for (String functionId : functionIds) {
                        state.graph().node(functionId).ifPresent(function -> {
                            String className = function.property("className");
                            state.graph().node(className).filter(node -> node.getType() == NodeType.SERVICE)
                                    .ifPresent(service -> serviceIds.add(service.getId()));
                        });
                    }
                    List<Map<String, String>> services = new ArrayList<>();
                    for (String serviceId : serviceIds) {
                        GraphNode service = state.graph().node(serviceId).orElse(null);
                        if (service == null) {
                            continue;
                        }
                        services.add(Map.of("id", service.getId(), "name", service.property("name").isBlank() ? service.getId() : service.property("name")));
                    }
                    List<Map<String, String>> dependencies = new ArrayList<>();
                    List<String> ids = new ArrayList<>(serviceIds);
                    for (GraphEdge edge : state.graph().edgesOf(EdgeType.DEPENDS_ON)) {
                        if (!serviceIds.contains(edge.getSourceId()) && !serviceIds.contains(edge.getTargetId())) {
                            continue;
                        }
                        if (state.graph().node(edge.getSourceId()).isEmpty() || state.graph().node(edge.getTargetId()).isEmpty()) {
                            continue;
                        }
                        dependencies.add(Map.of("fromId", edge.getSourceId(), "toId", edge.getTargetId()));
                        ids.add(edge.getSourceId());
                        ids.add(edge.getTargetId());
                    }
                    return observation("inspect_service_dependencies",
                            "Found " + services.size() + " related services and " + dependencies.size() + " recorded service dependencies.",
                            args, ids, Map.of("services", services, "dependencies", dependencies, "ids", ids));
                });
    }

    private static AgentTool inspectEvidence() {
        return tool("inspect_evidence",
                "Evidence already stored for one incident: reports, cause findings, fixes, and regression-test evidence.",
                Set.of("incidentId"), (state, args) -> {
                    String incidentId = args.getOrDefault("incidentId", "");
                    GraphNode incident = state.graph().node(incidentId).filter(node -> node.getType() == NodeType.INCIDENT).orElse(null);
                    if (incident == null) {
                        return observation("inspect_evidence",
                                "No incident with that id exists in this project graph.",
                                args, List.of(), Map.of("found", false, "evidence", List.of(), "ids", List.of()));
                    }
                    List<Map<String, Object>> evidence = evidenceFor(state.graph(), incidentId);
                    List<String> ids = new ArrayList<>();
                    ids.add(incidentId);
                    for (Map<String, Object> item : evidence) {
                        ids.add(String.valueOf(item.get("id")));
                    }
                    return observation("inspect_evidence",
                            "Retrieved " + evidence.size() + " evidence records for incident " + incidentId + ".",
                            args, ids, Map.of("found", true, "incidentId", incidentId, "evidence", evidence, "ids", ids));
                });
    }

    private static AgentTool functionDetails() {
        return tool("get_function_details",
                "Parsed metadata plus direct callers and callees for one function in this project graph.",
                Set.of("functionId"), (state, args) -> {
                    String functionId = args.getOrDefault("functionId", "");
                    GraphNode function = state.graph().node(functionId).filter(node -> node.getType() == NodeType.FUNCTION).orElse(null);
                    if (function == null) {
                        return observation("get_function_details",
                                "No function with that id exists in this project graph.",
                                args, List.of(), Map.of("found", false, "ids", List.of()));
                    }
                    List<String> callers = neighbors(state.graph(), functionId, false);
                    List<String> callees = neighbors(state.graph(), functionId, true);
                    List<String> ids = new ArrayList<>();
                    ids.add(functionId);
                    ids.addAll(callers);
                    ids.addAll(callees);
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("found", true);
                    facts.put("id", function.getId());
                    facts.put("name", function.property("signature").isBlank() ? function.getId() : function.property("signature"));
                    facts.put("filePath", function.property("filePath"));
                    facts.put("unresolvedCallCount", function.number("unresolvedCallCount"));
                    facts.put("callers", callers);
                    facts.put("callees", callees);
                    facts.put("ids", ids);
                    return observation("get_function_details",
                            "Loaded " + facts.get("name") + " with " + callers.size() + " callers and " + callees.size() + " callees.",
                            args, ids, facts);
                });
    }

    private static List<String> seeds(AgentState state, String functionId) {
        if (functionId == null || functionId.isBlank()) {
            return List.copyOf(changedFunctionIds(state));
        }
        if (state.graph().node(functionId).filter(node -> node.getType() == NodeType.FUNCTION).isEmpty()) {
            return List.of();
        }
        return List.of(functionId);
    }

    private static int depth(String raw) {
        if (raw == null || !raw.matches("[1-3]")) {
            return 2;
        }
        return Integer.parseInt(raw);
    }

    static Set<String> changedFunctionIds(AgentState state) {
        Set<String> ids = new LinkedHashSet<>();
        for (ToolObservation observation : state.observations()) {
            if (!"get_commit_changes".equals(observation.tool())) {
                continue;
            }
            for (Map<String, String> function : maps(observation.facts().get("functions"))) {
                ids.add(function.get("id"));
            }
        }
        return ids;
    }

    private static Set<String> tracedIds(AgentState state) {
        Set<String> ids = new LinkedHashSet<>();
        for (ToolObservation observation : state.observations()) {
            if (!"trace_function_dependencies".equals(observation.tool())) {
                continue;
            }
            for (Map<String, Object> hit : objectMaps(observation.facts().get("hits"))) {
                ids.add(String.valueOf(hit.get("id")));
            }
        }
        return ids;
    }

    private static List<String> neighbors(CodeGraph graph, String functionId, boolean outgoing) {
        List<String> ids = new ArrayList<>();
        for (GraphEdge edge : graph.edgesOf(EdgeType.CALLS)) {
            String neighbor = outgoing ? edge.getTargetId() : edge.getSourceId();
            String self = outgoing ? edge.getSourceId() : edge.getTargetId();
            if (functionId.equals(self) && graph.node(neighbor).isPresent()) {
                ids.add(neighbor);
            }
        }
        return ids;
    }

    private static List<Map<String, Object>> incidentsFor(CodeGraph graph, Set<String> relevant) {
        Map<String, Boolean> linked = new LinkedHashMap<>();
        for (EdgeType type : evidenceTypes()) {
            for (GraphEdge edge : graph.edgesOf(type)) {
                GraphNode evidence = graph.node(edge.getSourceId()).orElse(null);
                if (evidence == null || evidence.getType() != NodeType.EVIDENCE) {
                    continue;
                }
                String functionId = evidence.property("functionId");
                boolean matches = !functionId.isBlank() && relevant.contains(functionId);
                linked.merge(edge.getTargetId(), matches, Boolean::logicalOr);
            }
        }
        List<Map<String, Object>> incidents = new ArrayList<>();
        for (GraphNode incident : graph.nodesOf(NodeType.INCIDENT)) {
            if (!linked.getOrDefault(incident.getId(), false)) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", incident.getId());
            view.put("title", Text.clamp(incident.property("title"), 160));
            view.put("summary", Text.clamp(incident.property("summary"), 240));
            view.put("synthetic", incident.flag("isSynthetic"));
            view.put("sourceType", incident.property("sourceType"));
            view.put("evidence", evidenceFor(graph, incident.getId()));
            incidents.add(view);
        }
        return incidents;
    }

    private static List<Map<String, Object>> evidenceFor(CodeGraph graph, String incidentId) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (EdgeType type : evidenceTypes()) {
            for (GraphEdge edge : graph.edgesOf(type)) {
                if (!incidentId.equals(edge.getTargetId())) {
                    continue;
                }
                GraphNode node = graph.node(edge.getSourceId()).orElse(null);
                if (node == null || node.getType() != NodeType.EVIDENCE) {
                    continue;
                }
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", node.getId());
                view.put("incidentId", incidentId);
                view.put("evidenceType", node.property("evidenceType"));
                view.put("relationship", type.name());
                view.put("verificationStatus", node.property("verificationStatus"));
                view.put("confidence", node.property("confidence"));
                view.put("synthetic", node.flag("isSynthetic"));
                view.put("summary", Text.clamp(node.property("summary"), 240));
                evidence.add(view);
            }
        }
        return evidence;
    }

    private static List<EdgeType> evidenceTypes() {
        return List.of(EdgeType.REPORTS_ON, EdgeType.SUPPORTS_CAUSE, EdgeType.SUPPORTS_RESOLUTION, EdgeType.VALIDATES_FIX);
    }

    private static Map<String, String> functionView(GraphNode function, String changeType) {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("id", function.getId());
        view.put("name", function.property("name"));
        view.put("signature", function.property("signature").isBlank() ? function.getId() : function.property("signature"));
        view.put("filePath", function.property("filePath"));
        view.put("changeType", changeType);
        return view;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> maps(Object value) {
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

    private static ToolObservation observation(
            String tool,
            String summary,
            Map<String, String> arguments,
            List<String> evidenceIds,
            Map<String, Object> facts) {
        return new ToolObservation(tool, summary, arguments, List.copyOf(new LinkedHashSet<>(evidenceIds)), facts);
    }

    private static AgentTool tool(String name, String description, Set<String> argumentNames, ToolBody body) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Set<String> argumentNames() {
                return argumentNames;
            }

            @Override
            public ToolObservation execute(AgentState state, Map<String, String> arguments) {
                return body.execute(state, arguments);
            }
        };
    }

    @FunctionalInterface
    private interface ToolBody {
        ToolObservation execute(AgentState state, Map<String, String> arguments);
    }
}

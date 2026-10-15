package com.changegraph.backend.analysis;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.GraphEdge;
import com.changegraph.backend.graph.GraphNode;
import com.changegraph.backend.graph.NodeType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ImpactTraversal {

    public record Hit(String functionId, String direction, int depth) {
    }

    public record PathStep(String fromId, String relationship, String toId) {
    }

    public record Result(List<Hit> hits, List<PathStep> paths, List<String> services, boolean truncated) {
    }

    private ImpactTraversal() {
    }

    public static Result traverse(CodeGraph graph, List<String> changedFunctionIds, int upstreamDepth, int downstreamDepth, int maxNodes) {
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();
        for (GraphEdge edge : graph.edgesOf(EdgeType.CALLS)) {
            outgoing.computeIfAbsent(edge.getSourceId(), ignored -> new ArrayList<>()).add(edge.getTargetId());
            incoming.computeIfAbsent(edge.getTargetId(), ignored -> new ArrayList<>()).add(edge.getSourceId());
        }

        List<Hit> hits = new ArrayList<>();
        List<PathStep> paths = new ArrayList<>();
        boolean truncated = false;
        int budget = Math.max(1, maxNodes);

        truncated |= walk(changedFunctionIds, downstreamDepth, budget, "DOWNSTREAM", outgoing, true, hits, paths);
        truncated |= walk(changedFunctionIds, upstreamDepth, budget, "CALLER", incoming, false, hits, paths);

        Set<String> serviceNames = new LinkedHashSet<>();
        List<String> relevant = new ArrayList<>(changedFunctionIds);
        hits.forEach(hit -> relevant.add(hit.functionId()));
        for (String functionId : relevant) {
            graph.node(functionId).ifPresent(function -> {
                String className = function.property("className");
                graph.node(className).filter(node -> node.getType() == NodeType.SERVICE)
                        .ifPresent(service -> serviceNames.add(service.property("name").isBlank() ? className : service.property("name")));
            });
        }
        return new Result(hits, paths, List.copyOf(serviceNames), truncated);
    }

    private static boolean walk(
            List<String> seeds,
            int maxDepth,
            int budget,
            String direction,
            Map<String, List<String>> adjacency,
            boolean forward,
            List<Hit> hits,
            List<PathStep> paths) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (String seed : seeds) {
            if (seen.add(seed)) {
                queue.add(seed);
                depths.add(0);
            }
        }
        boolean truncated = false;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int depth = depths.removeFirst();
            if (depth >= maxDepth) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (!seen.add(next)) {
                    continue;
                }
                if (hits.size() >= budget) {
                    return true;
                }
                hits.add(new Hit(next, direction, depth + 1));
                if (forward) {
                    paths.add(new PathStep(current, "CALLS", next));
                } else {
                    paths.add(new PathStep(next, "CALLS", current));
                }
                queue.add(next);
                depths.add(depth + 1);
            }
        }
        return truncated;
    }

    public static String displayName(CodeGraph graph, String functionId) {
        return graph.node(functionId).map(node -> node.property("signature").isBlank() ? functionId : node.property("signature")).orElse(functionId);
    }

    public static boolean isTestFunction(GraphNode node) {
        return node.flag("isTest");
    }
}

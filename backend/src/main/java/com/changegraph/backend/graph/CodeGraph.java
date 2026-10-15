package com.changegraph.backend.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CodeGraph {

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final Map<String, GraphEdge> edges = new LinkedHashMap<>();

    public GraphNode mergeNode(NodeType type, String id, Map<String, Object> properties) {
        GraphNode node = nodes.computeIfAbsent(id, ignored -> new GraphNode(type, id));
        if (properties != null) {
            properties.forEach(node::put);
        }
        return node;
    }

    public GraphEdge mergeEdge(EdgeType type, String sourceId, String targetId, Map<String, Object> properties) {
        GraphEdge created = new GraphEdge(type, sourceId, targetId);
        GraphEdge edge = edges.computeIfAbsent(created.key(), ignored -> created);
        if (properties != null) {
            properties.forEach(edge::put);
        }
        return edge;
    }

    public Optional<GraphNode> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Collection<GraphNode> nodes() {
        return nodes.values();
    }

    public List<GraphNode> nodesOf(NodeType type) {
        return nodes.values().stream().filter(node -> node.getType() == type).toList();
    }

    public Collection<GraphEdge> edges() {
        return edges.values();
    }

    public List<GraphEdge> edgesOf(EdgeType type) {
        return edges.values().stream().filter(edge -> edge.getType() == type).toList();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public void mergeAll(CodeGraph other) {
        for (GraphNode node : other.nodes()) {
            mergeNode(node.getType(), node.getId(), node.getProperties());
        }
        for (GraphEdge edge : other.edges()) {
            mergeEdge(edge.getType(), edge.getSourceId(), edge.getTargetId(), edge.getProperties());
        }
    }

    public List<String> limitations() {
        return new ArrayList<>();
    }
}

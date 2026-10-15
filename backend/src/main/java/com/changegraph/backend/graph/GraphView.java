package com.changegraph.backend.graph;

import java.util.List;
import java.util.Map;

public record GraphView(List<NodeView> nodes, List<EdgeView> edges, boolean truncated) {

    public record NodeView(String id, String type, String label, Map<String, Object> properties) {
    }

    public record EdgeView(String type, String source, String target, Map<String, Object> properties) {
    }
}

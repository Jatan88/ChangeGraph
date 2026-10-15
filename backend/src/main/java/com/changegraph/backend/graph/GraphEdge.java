package com.changegraph.backend.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GraphEdge {

    private final EdgeType type;
    private final String sourceId;
    private final String targetId;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public GraphEdge(EdgeType type, String sourceId, String targetId) {
        this.type = type;
        this.sourceId = sourceId;
        this.targetId = targetId;
    }

    public EdgeType getType() {
        return type;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public String property(String key) {
        Object value = properties.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    void put(String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    public String key() {
        return type.name() + "|" + sourceId + "|" + targetId;
    }
}

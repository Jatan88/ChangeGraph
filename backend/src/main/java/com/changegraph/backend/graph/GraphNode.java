package com.changegraph.backend.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GraphNode {

    private final NodeType type;
    private final String id;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public GraphNode(NodeType type, String id) {
        this.type = type;
        this.id = id;
    }

    public NodeType getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public String property(String key) {
        Object value = properties.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public boolean flag(String key) {
        Object value = properties.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    public int number(String key) {
        Object value = properties.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    void put(String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }
}

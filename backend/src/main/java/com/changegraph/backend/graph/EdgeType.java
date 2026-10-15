package com.changegraph.backend.graph;

public enum EdgeType {
    CONTAINS,
    BELONGS_TO,
    CALLS,
    TESTS,
    DEPENDS_ON,
    CHANGES_FILE,
    CHANGES_FUNCTION,
    REPORTS_ON,
    SUPPORTS_CAUSE,
    SUPPORTS_RESOLUTION,
    VALIDATES_FIX;

    public static EdgeType fromName(String name) {
        for (EdgeType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}

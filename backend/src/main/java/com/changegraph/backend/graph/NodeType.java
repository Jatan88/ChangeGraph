package com.changegraph.backend.graph;

public enum NodeType {
    REPOSITORY("Repository"),
    FILE("File"),
    FUNCTION("Function"),
    SERVICE("Service"),
    TEST("Test"),
    COMMIT("Commit"),
    INCIDENT("Incident"),
    EVIDENCE("Evidence");

    private final String label;

    NodeType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static NodeType fromLabel(String label) {
        for (NodeType type : values()) {
            if (type.label.equals(label)) {
                return type;
            }
        }
        return null;
    }
}

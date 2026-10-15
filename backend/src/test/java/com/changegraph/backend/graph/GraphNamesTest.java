package com.changegraph.backend.graph;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphNamesTest {

    @Test
    void graphNameIsServerDerivedFromProjectId() {
        UUID projectId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        String name = GraphNames.forProject(projectId);
        assertEquals("cg_123e4567e89b12d3a456426614174000", name);
        assertTrue(GraphNames.matchesProject(projectId, name));
        assertFalse(GraphNames.matchesProject(projectId, "changegraph"));
        assertFalse(GraphNames.isWellFormed("cg_" + projectId));
    }
}

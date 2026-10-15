package com.changegraph.backend.graph;

import java.util.UUID;
import java.util.regex.Pattern;

public final class GraphNames {

    public static final Pattern WELL_FORMED = Pattern.compile("cg_[0-9a-f]{32}");

    private GraphNames() {
    }

    public static String forProject(UUID projectId) {
        return "cg_" + projectId.toString().replace("-", "");
    }

    public static boolean isWellFormed(String graphName) {
        return graphName != null && WELL_FORMED.matcher(graphName).matches();
    }

    public static boolean matchesProject(UUID projectId, String graphName) {
        return projectId != null && forProject(projectId).equals(graphName) && isWellFormed(graphName);
    }
}

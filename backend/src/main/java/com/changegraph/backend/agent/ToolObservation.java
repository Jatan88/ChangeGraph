package com.changegraph.backend.agent;

import java.util.List;
import java.util.Map;

public record ToolObservation(
        String tool,
        String summary,
        Map<String, String> arguments,
        List<String> evidenceIds,
        Map<String, Object> facts) {
}

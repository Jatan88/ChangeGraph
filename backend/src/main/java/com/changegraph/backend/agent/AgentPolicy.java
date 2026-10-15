package com.changegraph.backend.agent;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class AgentPolicy {

    public void check(AgentState state, AgentTool tool, Map<String, String> arguments) {
        if (state.toolCalls() >= state.maxToolCalls()) {
            throw new AgentRejectedException("BUDGET", "The investigation tool budget is exhausted.");
        }
        Map<String, String> safe = sanitize(tool, arguments);
        String key = callKey(tool.name(), safe);
        if (state.alreadyCalled(key)) {
            throw new AgentRejectedException("DUPLICATE", "Skipped a repeated " + tool.name() + " request.");
        }
    }

    public Map<String, String> sanitize(AgentTool tool, Map<String, String> arguments) {
        Map<String, String> safe = new TreeMap<>();
        Map<String, String> source = arguments == null ? Map.of() : arguments;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (!tool.argumentNames().contains(entry.getKey())) {
                throw new AgentRejectedException("INVALID_ARGUMENT", "Unexpected argument for " + tool.name() + ".");
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.isEmpty()) {
                continue;
            }
            safe.put(entry.getKey(), validate(entry.getKey(), value));
        }
        return safe;
    }

    public static String callKey(String tool, Map<String, String> arguments) {
        return tool + " " + arguments;
    }

    private static String validate(String name, String value) {
        return switch (name) {
            case "direction" -> {
                if (!Set.of("both", "callers", "downstream").contains(value)) {
                    throw new AgentRejectedException("INVALID_ARGUMENT", "Direction must be both, callers, or downstream.");
                }
                yield value;
            }
            case "depth" -> {
                if (!value.matches("[1-3]")) {
                    throw new AgentRejectedException("INVALID_ARGUMENT", "Depth must be from 1 to 3.");
                }
                yield value;
            }
            case "functionId", "incidentId" -> {
                if (!value.matches("[A-Za-z0-9_./#\\-]{1,200}")) {
                    throw new AgentRejectedException("INVALID_ARGUMENT", "Identifier is not allowed.");
                }
                yield value;
            }
            default -> throw new AgentRejectedException("INVALID_ARGUMENT", "Unexpected argument for the tool.");
        };
    }
}

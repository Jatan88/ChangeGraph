package com.changegraph.backend.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(Collection<AgentTool> available) {
        for (AgentTool tool : available) {
            if (tool == null || tool.name() == null || !tool.name().matches("[a-z0-9_]{1,64}")) {
                throw new IllegalArgumentException("Tool name is not allowed");
            }
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name");
            }
        }
    }

    public List<AgentTool> tools() {
        return List.copyOf(tools.values());
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new AgentRejectedException("UNKNOWN_TOOL", "That investigation tool is not available.");
        }
        return tool;
    }
}

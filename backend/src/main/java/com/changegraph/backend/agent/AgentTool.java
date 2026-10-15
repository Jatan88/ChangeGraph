package com.changegraph.backend.agent;

import java.util.Map;
import java.util.Set;

public interface AgentTool {

    String name();

    String description();

    Set<String> argumentNames();

    ToolObservation execute(AgentState state, Map<String, String> arguments);
}

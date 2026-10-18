package com.changegraph.backend.agent;

import com.changegraph.backend.common.Text;
import com.changegraph.backend.graph.CodeGraph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AgentState {

    private final UUID projectId;
    private final String commitSha;
    private final Integer pullRequestNumber;
    private final CodeGraph graph;
    private final int maxToolCalls;
    private final int maxIterations;
    private final List<ToolObservation> observations = new ArrayList<>();
    private final Set<String> callKeys = new LinkedHashSet<>();
    private String loadQuery;

    public AgentState(
            UUID projectId,
            String commitSha,
            Integer pullRequestNumber,
            CodeGraph graph,
            int maxToolCalls,
            int maxIterations) {
        this.projectId = projectId;
        this.commitSha = commitSha;
        this.pullRequestNumber = pullRequestNumber;
        this.graph = graph;
        this.maxToolCalls = maxToolCalls;
        this.maxIterations = maxIterations;
    }

    public UUID projectId() {
        return projectId;
    }

    public String commitSha() {
        return commitSha;
    }

    public Integer pullRequestNumber() {
        return pullRequestNumber;
    }

    public CodeGraph graph() {
        return graph;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public List<ToolObservation> observations() {
        return List.copyOf(observations);
    }

    public void add(ToolObservation observation, String callKey) {
        observations.add(observation);
        if (callKey != null) {
            callKeys.add(callKey);
        }
    }

    public void recordLoad(String query) {
        this.loadQuery = query;
    }

    public String loadQuery() {
        return loadQuery;
    }

    public void note(String tool, String summary) {
        observations.add(new ToolObservation(tool, summary, Map.of(), List.of(), Map.of(), null));
    }

    public boolean alreadyCalled(String callKey) {
        return callKeys.contains(callKey);
    }

    public int toolCalls() {
        return callKeys.size();
    }

    public String plannerView(List<AgentTool> tools) {
        StringBuilder view = new StringBuilder();
        view.append("AVAILABLE TOOLS\n");
        for (AgentTool tool : tools) {
            view.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        view.append("COMMIT ").append(commitSha).append('\n');
        view.append("COMPLETED TOOLS\n");
        for (ToolObservation observation : observations) {
            view.append("- ").append(observation.tool()).append(": ").append(Text.clamp(observation.summary(), 240));
            view.append(" evidence=").append(observation.evidenceIds()).append('\n');
        }
        view.append("FINDINGS\n");
        for (ToolObservation observation : observations) {
            view.append(observation.tool()).append(' ').append(idsOnly(observation)).append('\n');
        }
        if (view.length() > 6000) {
            return view.substring(0, 6000);
        }
        return view.toString();
    }

    private static String idsOnly(ToolObservation observation) {
        StringBuilder ids = new StringBuilder();
        for (String id : observation.evidenceIds()) {
            if (!ids.isEmpty()) {
                ids.append(',');
            }
            ids.append(id);
        }
        Object nested = observation.facts().get("ids");
        if (nested instanceof List<?> list) {
            for (Object value : list) {
                if (!ids.isEmpty()) {
                    ids.append(',');
                }
                ids.append(value);
            }
        }
        return ids.toString();
    }
}

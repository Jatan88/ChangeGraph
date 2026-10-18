package com.changegraph.backend.agent;

import com.changegraph.backend.analysis.AnalysisRequest;
import com.changegraph.backend.analysis.ChangeAnalysisService;
import com.changegraph.backend.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/agent/investigations")
public class AgentController {

    private final ChangeImpactAgent agent;
    private final ChangeAnalysisService analyses;
    private final CurrentUser currentUser;

    public AgentController(ChangeImpactAgent agent, ChangeAnalysisService analyses, CurrentUser currentUser) {
        this.agent = agent;
        this.analyses = analyses;
        this.currentUser = currentUser;
    }

    @PostMapping
    public AgentReport investigate(@PathVariable UUID projectId, @RequestBody(required = false) AnalysisRequest request) {
        return agent.investigate(currentUser.require(), projectId, request);
    }

    @GetMapping("/{analysisId}")
    public AgentReport get(@PathVariable UUID projectId, @PathVariable UUID analysisId) {
        return analyses.getAgent(currentUser.require(), projectId, analysisId);
    }
}

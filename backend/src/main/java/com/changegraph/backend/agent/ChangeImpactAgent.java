package com.changegraph.backend.agent;

import com.changegraph.backend.analysis.AnalysisPersistence;
import com.changegraph.backend.analysis.AnalysisRequest;
import com.changegraph.backend.analysis.RiskScoringService;
import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.github.GitHubClient;
import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.ingestion.IngestionService;
import com.changegraph.backend.project.GraphLifecycle;
import com.changegraph.backend.project.Project;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectGraph;
import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.user.UserAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

@Service
public class ChangeImpactAgent {

    private final ProjectAccess access;
    private final GitHubClient github;
    private final GraphStore graphStore;
    private final IngestionService ingestionService;
    private final LlmClient llm;
    private final AgentToolRegistry registry;
    private final AgentPlanner planner;
    private final AgentPolicy policy;
    private final AgentReportGenerator generator;
    private final AnalysisPersistence persistence;

    @Autowired
    public ChangeImpactAgent(
            ProjectAccess access,
            GitHubClient github,
            GraphStore graphStore,
            IngestionService ingestionService,
            LlmClient llm,
            AnalysisPersistence persistence,
            RiskScoringService scoring,
            JsonMapper jsonMapper) {
        this.access = access;
        this.github = github;
        this.graphStore = graphStore;
        this.ingestionService = ingestionService;
        this.llm = llm;
        this.registry = new AgentToolRegistry(GraphAgentTools.all());
        this.planner = new AgentPlanner(llm, jsonMapper);
        this.policy = new AgentPolicy();
        this.generator = new AgentReportGenerator(scoring);
        this.persistence = persistence;
    }

    ChangeImpactAgent(LlmClient llm, AgentReportGenerator generator, JsonMapper jsonMapper) {
        this.access = null;
        this.github = null;
        this.graphStore = null;
        this.ingestionService = null;
        this.llm = llm;
        this.registry = new AgentToolRegistry(GraphAgentTools.all());
        this.planner = new AgentPlanner(llm, jsonMapper);
        this.policy = new AgentPolicy();
        this.generator = generator;
        this.persistence = null;
    }

    public AgentReport investigate(UserAccount owner, UUID projectId, AnalysisRequest request) {
        Project project = access.requireProject(owner, projectId);
        ProjectGraph mapping = access.requireGraph(project.getId());
        if (mapping.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before analysis.");
        }
        ProjectRepositoryLink link = access.requireRepository(project.getId());
        boolean hasSha = request != null && request.commitSha() != null && !request.commitSha().isBlank();
        boolean hasPull = request != null && request.pullRequestNumber() != null;
        if (hasSha == hasPull) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ANALYSIS_TARGET_REQUIRED", "Provide either a commit SHA or a pull request number.");
        }
        GitHubModels.Repository repository = github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
        if (repository.id() != link.getGithubRepositoryId()) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_IDENTITY_CHANGED", "The linked repository id no longer matches GitHub.");
        }
        String sha = hasSha ? request.commitSha().trim() : null;
        Integer pullNumber = hasPull ? request.pullRequestNumber() : null;
        if (hasPull) {
            sha = github.getPullRequest(link.getOwnerName(), link.getRepositoryName(), pullNumber).headSha();
        }
        if (sha == null || !sha.matches("[0-9a-fA-F]{7,40}")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_COMMIT", "A public commit SHA could not be resolved.");
        }
        CodeGraph graph = graphStore.load(project.getId(), mapping.getGraphName(), 500);
        if (graph.node(sha).isEmpty()) {
            var job = ingestionService.queue(owner, projectId, sha);
            ingestionService.execute(job.id());
            graph = graphStore.load(project.getId(), mapping.getGraphName(), 500);
        }
        if (graph.node(sha).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "COMMIT_NOT_INGESTED", "The commit could not be ingested, so it was not investigated.");
        }
        AgentState state = new AgentState(project.getId(), sha, pullNumber, graph, Integer.MAX_VALUE, Integer.MAX_VALUE);
        state.recordLoad(graphStore.loadScript(mapping.getGraphName(), 500));
        AgentReport report = investigate(state);
        persistence.saveAgent(project.getId(), UUID.randomUUID(), report);
        return report;
    }

    public void record(UUID projectId, String commitSha, Integer pullRequestNumber, CodeGraph graph, String loadScript) {
        if (persistence == null || graph == null || commitSha == null || commitSha.isBlank()) {
            return;
        }
        AgentState state = new AgentState(projectId, commitSha, pullRequestNumber, graph, Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (loadScript != null && !loadScript.isBlank()) {
            state.recordLoad(loadScript);
        }
        AgentReport report = investigate(state);
        persistence.saveAgent(projectId, UUID.randomUUID(), report);
    }

    public AgentReport investigate(AgentState state) {
        execute(state, "get_commit_changes", Map.of());
        if (!llm.available()) {
            return fallback(state, "The language model is not configured.");
        }
        int repeated = 0;
        while (true) {
            AgentPlanner.Plan plan;
            try {
                plan = planner.next(state, registry);
            } catch (LlmUnavailableException ex) {
                return fallback(state, "The language model was unavailable.");
            } catch (AgentRejectedException ex) {
                return fallback(state, "The model response could not be used.");
            }
            if (plan.finish()) {
                state.note("planner", "The investigation stopped after the available graph evidence was reviewed.");
                break;
            }
            try {
                AgentTool tool = registry.require(plan.tool());
                Map<String, String> arguments = policy.sanitize(tool, plan.arguments());
                policy.check(state, tool, arguments);
                execute(state, tool.name(), arguments);
                repeated = 0;
            } catch (AgentRejectedException ex) {
                if ("DUPLICATE".equals(ex.code())) {
                    state.note("policy", ex.getMessage());
                    repeated++;
                    if (repeated >= registry.tools().size()) {
                        state.note("planner", "The investigation stopped after the available graph evidence was reviewed.");
                        break;
                    }
                    continue;
                }
                return fallback(state, "The model requested an investigation step that is not allowed.");
            }
        }
        return generator.generate(state, "COMPLETED");
    }

    private AgentReport fallback(AgentState state, String reason) {
        state.note("policy", "AI-assisted reasoning was unavailable. " + reason);
        runIfMissing(state, "trace_function_dependencies", Map.of("direction", "both", "depth", "2"));
        runIfMissing(state, "find_related_tests", Map.of());
        runIfMissing(state, "search_historical_incidents", Map.of());
        runIfMissing(state, "inspect_service_dependencies", Map.of());
        return generator.generate(state, "DETERMINISTIC_FALLBACK");
    }

    private void runIfMissing(AgentState state, String toolName, Map<String, String> arguments) {
        try {
            AgentTool tool = registry.require(toolName);
            Map<String, String> safe = policy.sanitize(tool, arguments);
            if (state.alreadyCalled(AgentPolicy.callKey(toolName, safe))) {
                return;
            }
            execute(state, toolName, safe);
        } catch (AgentRejectedException ex) {
            state.note("policy", ex.getMessage());
        }
    }

    private void execute(AgentState state, String toolName, Map<String, String> arguments) {
        AgentTool tool = registry.require(toolName);
        Map<String, String> safe = policy.sanitize(tool, arguments);
        policy.check(state, tool, safe);
        ToolObservation observation = tool.execute(state, safe);
        state.add(observation, AgentPolicy.callKey(toolName, safe));
    }
}

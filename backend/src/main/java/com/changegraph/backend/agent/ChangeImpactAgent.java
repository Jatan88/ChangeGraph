package com.changegraph.backend.agent;

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
import org.springframework.beans.factory.annotation.Value;
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
    private final int maxToolCalls;
    private final int maxIterations;

    @Autowired
    public ChangeImpactAgent(
            ProjectAccess access,
            GitHubClient github,
            GraphStore graphStore,
            IngestionService ingestionService,
            LlmClient llm,
            RiskScoringService scoring,
            JsonMapper jsonMapper,
            @Value("${changegraph.agent.max-tool-calls:5}") int maxToolCalls,
            @Value("${changegraph.agent.max-iterations:4}") int maxIterations) {
        this.access = access;
        this.github = github;
        this.graphStore = graphStore;
        this.ingestionService = ingestionService;
        this.llm = llm;
        this.registry = new AgentToolRegistry(GraphAgentTools.all());
        this.planner = new AgentPlanner(llm, jsonMapper);
        this.policy = new AgentPolicy();
        this.generator = new AgentReportGenerator(scoring);
        this.maxToolCalls = Math.max(2, Math.min(maxToolCalls, 8));
        this.maxIterations = Math.max(1, Math.min(maxIterations, 6));
    }

    ChangeImpactAgent(LlmClient llm, AgentReportGenerator generator, int maxToolCalls, int maxIterations, JsonMapper jsonMapper) {
        this.access = null;
        this.github = null;
        this.graphStore = null;
        this.ingestionService = null;
        this.llm = llm;
        this.registry = new AgentToolRegistry(GraphAgentTools.all());
        this.planner = new AgentPlanner(llm, jsonMapper);
        this.policy = new AgentPolicy();
        this.generator = generator;
        this.maxToolCalls = maxToolCalls;
        this.maxIterations = maxIterations;
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
        return investigate(new AgentState(project.getId(), sha, pullNumber, graph, maxToolCalls, maxIterations));
    }

    public AgentReport investigate(AgentState state) {
        execute(state, "get_commit_changes", Map.of());
        if (!llm.available()) {
            return fallback(state, "The language model is not configured.");
        }
        boolean finished = false;
        for (int iteration = 0; iteration < state.maxIterations(); iteration++) {
            AgentPlanner.Plan plan;
            try {
                plan = planner.next(state, registry);
            } catch (LlmUnavailableException ex) {
                return fallback(state, "The language model was unavailable.");
            } catch (AgentRejectedException ex) {
                return fallback(state, "The model response could not be used.");
            }
            if (plan.finish()) {
                finished = true;
                state.note("planner", "The investigation stopped after the available graph evidence was reviewed.");
                break;
            }
            try {
                AgentTool tool = registry.require(plan.tool());
                Map<String, String> arguments = policy.sanitize(tool, plan.arguments());
                policy.check(state, tool, arguments);
                execute(state, tool.name(), arguments);
            } catch (AgentRejectedException ex) {
                if ("BUDGET".equals(ex.code())) {
                    state.note("policy", "Stopped because the investigation budget was reached.");
                    return generator.generate(state, "BUDGET_EXHAUSTED");
                }
                if ("DUPLICATE".equals(ex.code())) {
                    state.note("policy", ex.getMessage());
                    continue;
                }
                return fallback(state, "The model requested an investigation step that is not allowed.");
            }
        }
        if (!finished) {
            state.note("policy", "Stopped because the investigation iteration budget was reached.");
            return generator.generate(state, "BUDGET_EXHAUSTED");
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
            if (state.alreadyCalled(AgentPolicy.callKey(toolName, safe)) || state.toolCalls() >= state.maxToolCalls()) {
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

package com.changegraph.backend.analysis;

import com.changegraph.backend.agent.AgentReport;
import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.PageResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Service
public class ChangeAnalysisService {

    private final ProjectAccess access;
    private final GitHubClient github;
    private final GraphStore graphStore;
    private final IngestionService ingestionService;
    private final AnalysisRepository analyses;
    private final AnalysisPersistence persistence;
    private final RiskScoringService scoring;
    private final JsonMapper jsonMapper;
    private final int upstreamDepth;
    private final int downstreamDepth;
    private final int maxNodes;

    public ChangeAnalysisService(
            ProjectAccess access,
            GitHubClient github,
            GraphStore graphStore,
            IngestionService ingestionService,
            AnalysisRepository analyses,
            AnalysisPersistence persistence,
            RiskScoringService scoring,
            JsonMapper jsonMapper,
            @Value("${changegraph.analysis.upstream-depth:3}") int upstreamDepth,
            @Value("${changegraph.analysis.downstream-depth:2}") int downstreamDepth,
            @Value("${changegraph.analysis.max-nodes:100}") int maxNodes) {
        this.access = access;
        this.github = github;
        this.graphStore = graphStore;
        this.ingestionService = ingestionService;
        this.analyses = analyses;
        this.persistence = persistence;
        this.scoring = scoring;
        this.jsonMapper = jsonMapper;
        this.upstreamDepth = upstreamDepth;
        this.downstreamDepth = downstreamDepth;
        this.maxNodes = maxNodes;
    }

    public AnalysisReport analyze(UserAccount owner, UUID projectId, AnalysisRequest request) {
        Project project = access.requireProject(owner, projectId);
        ProjectGraph mapping = access.requireGraph(project.getId());
        if (mapping.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before analysis.");
        }
        ProjectRepositoryLink link = access.requireRepository(project.getId());
        boolean hasSha = request != null && request.commitSha() != null && !request.commitSha().isBlank();
        boolean hasPull = request != null && request.pullRequestNumber() != null;
        if (hasSha == hasPull) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ANALYSIS_TARGET_REQUIRED",
                    "Provide either a commit SHA or a pull request number.");
        }
        GitHubModels.Repository repository = github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
        if (repository.id() != link.getGithubRepositoryId()) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_IDENTITY_CHANGED", "The linked repository id no longer matches GitHub.");
        }
        String sha = hasSha ? request.commitSha().trim() : null;
        Integer pullNumber = hasPull ? request.pullRequestNumber() : null;
        if (hasPull) {
            GitHubModels.PullRequest pull = github.getPullRequest(link.getOwnerName(), link.getRepositoryName(), pullNumber);
            sha = pull.headSha();
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
            throw new ApiException(HttpStatus.CONFLICT, "COMMIT_NOT_INGESTED", "The commit could not be ingested, so it was not analyzed.");
        }
        AnalysisReport report = AnalysisReportBuilder.build(
                UUID.randomUUID(), graph, sha, pullNumber, scoring, upstreamDepth, downstreamDepth, maxNodes);
        persistence.save(project.getId(), report);
        return report;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnalysisSummary> list(UserAccount owner, UUID projectId, int page, int size) {
        access.requireProject(owner, projectId);
        int bounded = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        var result = analyses.findByProjectId(projectId, PageRequest.of(safePage, bounded, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(
                result.getContent().stream().map(AnalysisSummary::from).toList(),
                safePage,
                bounded,
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AnalysisReport get(UserAccount owner, UUID projectId, UUID analysisId) {
        AnalysisRecord record = require(owner, projectId, analysisId);
        if ("AGENT".equals(record.getKind())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Analysis not found.");
        }
        return jsonMapper.readValue(record.getResultJson(), AnalysisReport.class);
    }

    @Transactional(readOnly = true)
    public AgentReport getAgent(UserAccount owner, UUID projectId, UUID analysisId) {
        AnalysisRecord record = require(owner, projectId, analysisId);
        if (!"AGENT".equals(record.getKind())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Analysis not found.");
        }
        return jsonMapper.readValue(record.getResultJson(), AgentReport.class);
    }

    private AnalysisRecord require(UserAccount owner, UUID projectId, UUID analysisId) {
        access.requireProject(owner, projectId);
        AnalysisRecord record = analyses.findByIdAndProjectId(analysisId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Analysis not found."));
        if (record.getResultJson() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_UNAVAILABLE", "This analysis does not have a stored report.");
        }
        return record;
    }
}

package com.changegraph.backend.workflow;

import com.changegraph.backend.agent.ChangeImpactAgent;
import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.github.GitHubClient;
import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.ingestion.IngestionJobResponse;
import com.changegraph.backend.ingestion.IngestionJobService;
import com.changegraph.backend.ingestion.IngestionService;
import com.changegraph.backend.parser.JavaSourceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowStore store;
    private final IngestionService ingestion;
    private final IngestionJobService jobs;
    private final GraphStore graphs;
    private final GitHubClient github;
    private final CodeReviewerAgent reviewer;
    private final DeveloperAgent developer;
    private final ChangeImpactAgent impactAgent;
    private final ConcurrentHashMap<UUID, Object> projectLocks = new ConcurrentHashMap<>();

    public WorkflowEngine(
            WorkflowStore store,
            IngestionService ingestion,
            IngestionJobService jobs,
            GraphStore graphs,
            GitHubClient github,
            CodeReviewerAgent reviewer,
            DeveloperAgent developer,
            ChangeImpactAgent impactAgent) {
        this.store = store;
        this.ingestion = ingestion;
        this.jobs = jobs;
        this.graphs = graphs;
        this.github = github;
        this.reviewer = reviewer;
        this.developer = developer;
        this.impactAgent = impactAgent;
    }

    public void process(UUID runId) {
        WorkflowStore.RunSnapshot run = store.claim(runId);
        if (run == null) {
            log.info("Workflow {} was not started because it is no longer queued", runId);
            return;
        }
        log.info("Workflow {} started for project {} {} commit {}",
                run.id(), run.projectId(), run.kind(), shortSha(run.commitSha()));
        synchronized (projectLocks.computeIfAbsent(run.projectId(), ignored -> new Object())) {
            try {
                investigate(run);
                log.info("Workflow {} finished for project {}", run.id(), run.projectId());
            } catch (ApiException ex) {
                log.warn("Workflow {} failed with {}: {}", runId, ex.getCode(), ex.getMessage());
                store.event(runId, WorkflowAgent.SYSTEM, WorkflowPhase.FETCH, WorkflowEventStatus.FAILED, ex.getMessage(), ex.getCode());
                store.fail(runId, ex.getMessage(), ex.getCode());
            } catch (RuntimeException ex) {
                log.warn("Workflow {} failed", runId, ex);
                store.event(runId, WorkflowAgent.SYSTEM, WorkflowPhase.FETCH, WorkflowEventStatus.FAILED, "The workflow failed before it could finish.", "WORKFLOW_FAILED");
                store.fail(runId, "The workflow failed before it could finish.", "WORKFLOW_FAILED");
            }
        }
    }

    private void investigate(WorkflowStore.RunSnapshot run) {
        UUID runId = run.id();
        store.event(
                runId,
                WorkflowAgent.SYSTEM,
                WorkflowPhase.DETECTED,
                WorkflowEventStatus.COMPLETED,
                detectedSummary(run),
                "Selected branch " + blank(run.selectedBranch())
                        + ". Repository default branch " + blank(run.defaultBranch())
                        + ". Source " + blank(run.sourceBranch())
                        + ". Target " + blank(run.targetBranch()) + ".");
        if (!run.graphReady() || run.graphName() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before processing this change.");
        }
        if (run.owner() == null || run.repository() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_NOT_LINKED", "Connect a public GitHub repository before continuing.");
        }
        GitHubModels.Repository repository = github.getPublicRepository(run.owner(), run.repository());
        if (repository.id() != run.githubRepositoryId()) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_IDENTITY_CHANGED", "The linked repository id no longer matches GitHub.");
        }
        store.event(runId, WorkflowAgent.INGESTION, WorkflowPhase.FETCH, WorkflowEventStatus.RUNNING, "Fetching source changes", null);
        IngestionJobResponse queued = ingestion.queueForProject(run.projectId(), run.commitSha());
        ingestion.execute(queued.id());
        IngestionJobResponse finished = jobs.view(queued.id());
        if (finished == null || !"SUCCEEDED".equals(finished.status())) {
            String code = finished == null || finished.errorCode() == null ? "INGESTION_FAILED" : finished.errorCode();
            String message = finished == null || finished.progressMessage() == null
                    ? "Source changes could not be ingested."
                    : finished.progressMessage();
            throw new ApiException(HttpStatus.CONFLICT, code, message);
        }
        store.event(runId, WorkflowAgent.INGESTION, WorkflowPhase.FETCH, WorkflowEventStatus.COMPLETED, "Source changes fetched", finished.progressMessage());
        store.event(runId, WorkflowAgent.GRAPH, WorkflowPhase.GRAPH, WorkflowEventStatus.RUNNING, "Analyzing the project graph", null);
        CodeGraph graph = graphs.load(run.projectId(), run.graphName(), 500);
        store.event(
                runId,
                WorkflowAgent.GRAPH,
                WorkflowPhase.GRAPH,
                WorkflowEventStatus.COMPLETED,
                "Graph analysis completed",
                "Loaded " + graph.nodeCount() + " nodes and " + graph.edgeCount() + " edges for this review.");
        recordInvestigation(run, graph);
        store.event(runId, WorkflowAgent.CODE_REVIEWER, WorkflowPhase.REVIEW, WorkflowEventStatus.RUNNING, "Code Reviewer is investigating dependencies", null);
        CodeReview review = reviewer.review(run.projectId(), graph, run.commitSha(), run.pullRequestNumber(), run.sourceBranch(), run.targetBranch());
        for (CodeReview.ReviewFinding finding : review.findings()) {
            store.event(
                    runId,
                    WorkflowAgent.CODE_REVIEWER,
                    WorkflowPhase.REVIEW,
                    WorkflowEventStatus.COMPLETED,
                    finding.severity() + ": " + finding.title(),
                    finding.recommendation());
        }
        store.saveReview(runId, review);
        store.event(runId, WorkflowAgent.CODE_REVIEWER, WorkflowPhase.REVIEW, WorkflowEventStatus.COMPLETED, review.summary(), null);
        if (!run.proposeFixes() || !review.hasActionableIssues()) {
            String reason = run.proposeFixes()
                    ? "No actionable fix was required."
                    : "Branch synchronization does not open a pull request.";
            store.event(runId, WorkflowAgent.DEVELOPER, WorkflowPhase.FIX, WorkflowEventStatus.COMPLETED, reason, null);
            store.event(runId, WorkflowAgent.DEVELOPER, WorkflowPhase.PULL_REQUEST, WorkflowEventStatus.COMPLETED, "No pull request was opened", reason);
            store.finish(runId, WorkflowStatus.COMPLETED, reason, "Repository scripts were not executed.", null, null, null);
            return;
        }
        store.event(runId, WorkflowAgent.DEVELOPER, WorkflowPhase.FIX, WorkflowEventStatus.RUNNING, "Developer Agent is preparing a fix", null);
        Set<String> allowed = ReviewFiles.allowed(changedPaths(run), graph, run.commitSha());
        FixResult fix = developer.propose(new DeveloperAgent.FixRequest(
                runId,
                run.attempt(),
                run.owner(),
                run.repository(),
                run.commitSha(),
                run.defaultBranch(),
                run.selectedBranch(),
                run.sourceBranch(),
                run.targetBranch(),
                true,
                allowed,
                fileContents(run, allowed),
                patches(run),
                review));
        store.event(runId, WorkflowAgent.DEVELOPER, WorkflowPhase.FIX, WorkflowEventStatus.COMPLETED, fix.explanation(), fix.validation());
        if (fix.pullRequestOpened()) {
            store.event(
                    runId,
                    WorkflowAgent.DEVELOPER,
                    WorkflowPhase.PULL_REQUEST,
                    WorkflowEventStatus.COMPLETED,
                    "Opened pull request #" + fix.pullRequestNumber(),
                    fix.pullRequestUrl());
            store.finish(
                    runId,
                    WorkflowStatus.AWAITING_HUMAN_REVIEW,
                    fix.explanation(),
                    fix.validation(),
                    fix.branch(),
                    fix.pullRequestUrl(),
                    fix.pullRequestNumber());
            return;
        }
        store.event(runId, WorkflowAgent.DEVELOPER, WorkflowPhase.PULL_REQUEST, WorkflowEventStatus.COMPLETED, "No pull request was opened", fix.explanation());
        store.finish(runId, WorkflowStatus.COMPLETED, fix.explanation(), fix.validation(), null, null, null);
    }

    private Set<String> changedPaths(WorkflowStore.RunSnapshot run) {
        Set<String> paths = new LinkedHashSet<>();
        GitHubModels.CommitDetail commit = github.getCommit(run.owner(), run.repository(), run.commitSha());
        for (GitHubModels.CommitFile file : commit.files()) {
            String path = JavaSourceParser.normalizePath(file.filename());
            if (JavaSourceParser.isSafePath(path) && !"removed".equalsIgnoreCase(file.status())) {
                paths.add(path);
            }
            if (paths.size() == 8) {
                break;
            }
        }
        return paths;
    }

    private void recordInvestigation(WorkflowStore.RunSnapshot run, CodeGraph graph) {
        if (run.kind() == WorkflowKind.BRANCH_SYNC || impactAgent == null) {
            return;
        }
        try {
            impactAgent.record(
                    run.projectId(),
                    run.commitSha(),
                    run.pullRequestNumber(),
                    graph,
                    graphs.loadScript(run.graphName(), 500));
            store.event(
                    run.id(),
                    WorkflowAgent.CODE_REVIEWER,
                    WorkflowPhase.REVIEW,
                    WorkflowEventStatus.COMPLETED,
                    "Agent investigation stored",
                    "The impact report was saved without using the Investigate button.");
        } catch (RuntimeException ex) {
            log.warn("Agent investigation was not stored for workflow {}", run.id(), ex);
            store.event(
                    run.id(),
                    WorkflowAgent.CODE_REVIEWER,
                    WorkflowPhase.REVIEW,
                    WorkflowEventStatus.FAILED,
                    "Agent investigation was not stored",
                    "The code review continued.");
        }
    }

    private Map<String, String> patches(WorkflowStore.RunSnapshot run) {
        Map<String, String> patches = new LinkedHashMap<>();
        GitHubModels.CommitDetail commit = github.getCommit(run.owner(), run.repository(), run.commitSha());
        for (GitHubModels.CommitFile file : commit.files()) {
            String path = JavaSourceParser.normalizePath(file.filename());
            if (!JavaSourceParser.isSafePath(path) || "removed".equalsIgnoreCase(file.status())) {
                continue;
            }
            if (file.patch() != null && !file.patch().isBlank()) {
                patches.put(path, file.patch());
            }
            if (patches.size() == 8) {
                break;
            }
        }
        return patches;
    }

    private Map<String, String> fileContents(WorkflowStore.RunSnapshot run, Set<String> paths) {
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : paths) {
            String body = github.getFile(run.owner(), run.repository(), path, run.commitSha());
            if (body != null && body.length() <= 100_000) {
                files.put(path, body);
            }
        }
        return files;
    }

    private static String detectedSummary(WorkflowStore.RunSnapshot run) {
        if (run.kind() == WorkflowKind.PULL_REQUEST) {
            return "Pull request #" + run.pullRequestNumber() + " detected";
        }
        if (run.kind() == WorkflowKind.BRANCH_SYNC) {
            return "Selected branch " + blank(run.selectedBranch()) + " queued for re-index";
        }
        String sha = run.commitSha() == null ? "" : run.commitSha().substring(0, Math.min(7, run.commitSha().length()));
        return "Commit " + sha + " detected on " + blank(run.targetBranch());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "none";
        }
        return sha.substring(0, Math.min(7, sha.length()));
    }
}

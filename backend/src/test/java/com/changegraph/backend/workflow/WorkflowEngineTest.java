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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEngineTest {

    private final WorkflowStore store = mock(WorkflowStore.class);
    private final IngestionService ingestion = mock(IngestionService.class);
    private final IngestionJobService jobs = mock(IngestionJobService.class);
    private final GraphStore graphs = mock(GraphStore.class);
    private final GitHubClient github = mock(GitHubClient.class);
    private final CodeReviewerAgent reviewer = mock(CodeReviewerAgent.class);
    private final DeveloperAgent developer = mock(DeveloperAgent.class);
    private final ChangeImpactAgent impactAgent = mock(ChangeImpactAgent.class);
    private final WorkflowEngine engine = new WorkflowEngine(store, ingestion, jobs, graphs, github, reviewer, developer, impactAgent);
    private final UUID runId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @Test
    void ingestionFailureIsRecordedAndDoesNotOpenAPullRequest() {
        when(store.claim(runId)).thenReturn(snapshot());
        when(github.getPublicRepository("acme", "payments")).thenReturn(repository());
        when(ingestion.queueForProject(projectId, "abc1234")).thenThrow(new ApiException(
                HttpStatus.CONFLICT,
                "INGESTION_IN_PROGRESS",
                "An ingestion is already running for this project."));

        engine.process(runId);

        verify(store).fail(eq(runId), eq("An ingestion is already running for this project."), eq("INGESTION_IN_PROGRESS"));
        verify(developer, never()).propose(any());
        verify(github, never()).createBranch(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reviewWithoutActionableIssuesCompletesWithoutAFixPullRequest() {
        when(store.claim(runId)).thenReturn(snapshot());
        when(github.getPublicRepository("acme", "payments")).thenReturn(repository());
        UUID jobId = UUID.randomUUID();
        when(ingestion.queueForProject(projectId, "abc1234")).thenReturn(job(jobId, "QUEUED"));
        when(jobs.view(jobId)).thenReturn(job(jobId, "SUCCEEDED"));
        when(graphs.load(eq(projectId), eq("cg_graph"), anyInt())).thenReturn(new CodeGraph());
        when(reviewer.review(any(), any(), any(), any(), any(), any())).thenReturn(new CodeReview(
                "No actionable findings.",
                List.of(),
                List.of(),
                List.of("get_commit_changes: none")));

        engine.process(runId);

        verify(store).finish(eq(runId), eq(WorkflowStatus.COMPLETED), anyString(), anyString(), eq(null), eq(null), eq(null));
        verify(developer, never()).propose(any());
    }

    @Test
    void actionableReviewAsksTheDeveloperAgentAndRecordsThePullRequest() {
        when(store.claim(runId)).thenReturn(snapshot());
        when(github.getPublicRepository("acme", "payments")).thenReturn(repository());
        UUID jobId = UUID.randomUUID();
        when(ingestion.queueForProject(projectId, "abc1234")).thenReturn(job(jobId, "QUEUED"));
        when(jobs.view(jobId)).thenReturn(job(jobId, "SUCCEEDED"));
        when(graphs.load(eq(projectId), eq("cg_graph"), anyInt())).thenReturn(new CodeGraph());
        CodeReview review = new CodeReview("Action needed.", List.of(new CodeReview.ReviewFinding(
                "f1", "HIGH", "BREAKING", "Removed function still has callers", "detail",
                List.of(), List.of(), "Update callers.", true)), List.of(), List.of());
        when(reviewer.review(any(), any(), any(), any(), any(), any())).thenReturn(review);
        when(github.getCommit("acme", "payments", "abc1234")).thenReturn(new GitHubModels.CommitDetail(
                "abc1234", "msg", "2026-01-01T00:00:00Z", List.of(), List.of()));
        when(developer.propose(any())).thenReturn(new FixResult(
                true,
                true,
                "Opened a pull request for human review.",
                "Static checks only.",
                "changegraph/fix-abc1234-aaaaaaaa-a1",
                "https://github.com/acme/payments/pull/8",
                8,
                List.of("Removed function still has callers")));

        engine.process(runId);

        verify(developer).propose(any());
        verify(store).finish(
                eq(runId),
                eq(WorkflowStatus.AWAITING_HUMAN_REVIEW),
                anyString(),
                anyString(),
                eq("changegraph/fix-abc1234-aaaaaaaa-a1"),
                eq("https://github.com/acme/payments/pull/8"),
                eq(8));
    }

    private WorkflowStore.RunSnapshot snapshot() {
        return new WorkflowStore.RunSnapshot(
                runId,
                projectId,
                WorkflowKind.PULL_REQUEST,
                "abc1234",
                4,
                "feature/pay",
                "release",
                "Ship pay",
                true,
                1,
                "cg_graph",
                true,
                "acme",
                "payments",
                42,
                "main",
                "develop");
    }

    private static GitHubModels.Repository repository() {
        return new GitHubModels.Repository(42, "acme", "payments", "main", "https://github.com/acme/payments", true);
    }

    private static IngestionJobResponse job(UUID id, String status) {
        return new IngestionJobResponse(id, status, "abc1234", "Ingestion finished", null, Instant.now(), Instant.now(), Instant.now());
    }
}

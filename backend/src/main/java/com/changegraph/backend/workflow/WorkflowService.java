package com.changegraph.backend.workflow;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.PageResponse;
import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.github.NewCommitListener;
import com.changegraph.backend.github.NewPullRequestListener;
import com.changegraph.backend.github.RepositoryWorkflows;
import com.changegraph.backend.project.Project;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectRepository;
import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import com.changegraph.backend.user.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WorkflowService implements NewCommitListener, NewPullRequestListener, RepositoryWorkflows {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowStore store;
    private final WorkflowRunner runner;
    private final ProjectAccess access;
    private final ProjectRepository projects;
    private final ProjectRepositoryLinkRepository links;

    public WorkflowService(
            WorkflowStore store,
            WorkflowRunner runner,
            ProjectAccess access,
            ProjectRepository projects,
            ProjectRepositoryLinkRepository links) {
        this.store = store;
        this.runner = runner;
        this.access = access;
        this.projects = projects;
        this.links = links;
    }

    @Override
    public void onNewCommits(UUID projectId, String branch, List<GitHubModels.CommitSummary> commits) {
        if (links.findByProjectId(projectId).isEmpty()) {
            log.warn("Ignored {} new commit(s) for project {} because the repository is no longer linked", commits.size(), projectId);
            return;
        }
        log.info("Reviewing {} new commit(s) on {} for project {}", commits.size(), branch, projectId);
        for (GitHubModels.CommitSummary commit : commits) {
            enqueue(
                    projectId,
                    null,
                    "push:" + commit.sha(),
                    WorkflowKind.PUSH,
                    commit.sha(),
                    null,
                    branch,
                    branch,
                    commit.message() == null || commit.message().isBlank() ? "Commit " + commit.sha() : commit.message(),
                    true);
        }
    }

    @Override
    public void onOpenPullRequests(UUID projectId, List<GitHubModels.PullRequest> pullRequests) {
        if (links.findByProjectId(projectId).isEmpty()) {
            log.warn("Ignored {} open pull request(s) for project {} because the repository is no longer linked", pullRequests.size(), projectId);
            return;
        }
        log.info("Reviewing {} open pull request(s) for project {}", pullRequests.size(), projectId);
        for (GitHubModels.PullRequest pull : pullRequests) {
            if (pull.number() <= 0 || pull.headSha() == null || !pull.headSha().matches("[0-9a-fA-F]{7,40}")) {
                continue;
            }
            String head = pull.headRef() == null ? "" : pull.headRef();
            String base = pull.baseRef() == null ? "" : pull.baseRef();
            if (head.isBlank() || base.isBlank()) {
                continue;
            }
            String title = pull.title() == null || pull.title().isBlank() ? "Pull request " + pull.number() : pull.title();
            enqueue(
                    projectId,
                    null,
                    "pr:" + pull.number() + ":" + pull.headSha(),
                    WorkflowKind.PULL_REQUEST,
                    pull.headSha(),
                    pull.number(),
                    head,
                    base,
                    title,
                    true);
        }
    }

    @Override
    @Transactional
    public UUID syncBranch(UUID projectId, String branch, String commitSha) {
        if (links.findByProjectId(projectId).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_NOT_LINKED", "Connect a public GitHub repository before continuing.");
        }
        return enqueue(
                projectId,
                null,
                "branch:" + branch + ":" + commitSha,
                WorkflowKind.BRANCH_SYNC,
                commitSha,
                null,
                branch,
                branch,
                "Re-index " + branch,
                false).run().getId();
    }

    @Transactional
    public List<UUID> acceptWebhook(String deliveryId, ProjectRepositoryLink link, WebhookPayloads.Accepted accepted) {
        String key = accepted.kind() == WorkflowKind.PULL_REQUEST
                ? "pr:" + accepted.pullRequestNumber() + ":" + accepted.commitSha()
                : "push:" + accepted.commitSha();
        return List.of(enqueue(
                link.getProject().getId(),
                deliveryId,
                key,
                accepted.kind(),
                accepted.commitSha(),
                accepted.pullRequestNumber(),
                accepted.sourceBranch(),
                accepted.targetBranch(),
                accepted.title(),
                true).run().getId());
    }

    public PageResponse<WorkflowResponses.WorkflowSummary> list(UserAccount owner, UUID projectId, int page, int size) {
        access.requireProject(owner, projectId);
        return store.list(projectId, page, size);
    }

    public WorkflowResponses.WorkflowDetail detail(UserAccount owner, UUID projectId, UUID runId) {
        access.requireProject(owner, projectId);
        return store.detail(projectId, runId);
    }

    public SseEmitter stream(UserAccount owner, UUID projectId, UUID runId, int after) {
        access.requireProject(owner, projectId);
        return store.stream(projectId, runId, after);
    }

    public SseEmitter projectStream(UserAccount owner, UUID projectId) {
        access.requireProject(owner, projectId);
        return store.projectStream(projectId);
    }

    public UUID retry(UserAccount owner, UUID projectId, UUID runId) {
        access.requireProject(owner, projectId);
        UUID id = store.retry(projectId, runId);
        runner.run(id);
        return id;
    }

    private WorkflowStore.AcceptResult enqueue(
            UUID projectId,
            String deliveryId,
            String idempotencyKey,
            WorkflowKind kind,
            String commitSha,
            Integer pullRequestNumber,
            String sourceBranch,
            String targetBranch,
            String title,
            boolean proposeFixes) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Project not found."));
        WorkflowRun run = new WorkflowRun(
                UUID.randomUUID(),
                project,
                deliveryId,
                idempotencyKey,
                kind,
                commitSha,
                pullRequestNumber,
                sourceBranch,
                targetBranch,
                title,
                proposeFixes,
                Instant.now());
        WorkflowStore.AcceptResult result = store.accept(run);
        if (result.created()) {
            log.info("Queued workflow {} for project {} {} commit {}",
                    result.run().getId(), projectId, kind, shortSha(commitSha));
            runner.run(result.run().getId());
        } else {
            log.info("Skipped duplicate workflow {} for project {} key {}",
                    result.run().getId(), projectId, idempotencyKey);
        }
        return result;
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "none";
        }
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }
}

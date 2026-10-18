package com.changegraph.backend.github;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import com.changegraph.backend.project.ProjectResponse;
import com.changegraph.backend.user.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GitHubRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryService.class);

    private final ProjectAccess access;
    private final ProjectRepositoryLinkRepository links;
    private final GitHubClient github;
    private final GitHubCommitPoller commitPoller;
    private final ObjectProvider<RepositoryWorkflows> workflows;

    public GitHubRepositoryService(
            ProjectAccess access,
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            GitHubCommitPoller commitPoller,
            ObjectProvider<RepositoryWorkflows> workflows) {
        this.access = access;
        this.links = links;
        this.github = github;
        this.commitPoller = commitPoller;
        this.workflows = workflows;
    }

    @Transactional
    public ProjectResponse.RepositorySummary link(UserAccount owner, UUID projectId, String repositoryUrl, String branch) {
        var project = access.requireProject(owner, projectId);
        if (links.findByProjectId(project.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_ALREADY_LINKED", "This project already has a repository. Remove it before linking another.");
        }
        RepositoryCoordinates coordinates = GitHubUrlParser.parse(repositoryUrl);
        GitHubModels.Repository repository = github.getPublicRepository(coordinates.owner(), coordinates.name());
        ProjectRepositoryLink link = new ProjectRepositoryLink(
                UUID.randomUUID(),
                project,
                repository.id(),
                repository.owner(),
                repository.name(),
                repository.defaultBranch(),
                repository.url(),
                Instant.now());
        link.setSelectedBranch(resolveBranch(repository, branch));
        link.setLastSyncedAt(Instant.now());
        String baseline = currentTip(link);
        if (baseline != null) {
            link.setLastSeenCommitSha(baseline);
        }
        links.save(link);
        log.info("Linked {}/{} branch {} for project {} at commit {}. Later commits on this branch start a review. History already on the branch does not.",
                link.getOwnerName(),
                link.getRepositoryName(),
                link.branchForSync(),
                project.getId(),
                baseline == null ? "none" : baseline.substring(0, Math.min(7, baseline.length())));
        return summary(link);
    }

    @Transactional(readOnly = true)
    public ProjectResponse.RepositorySummary get(UserAccount owner, UUID projectId) {
        var project = access.requireProject(owner, projectId);
        return summary(access.requireRepository(project.getId()));
    }

    @Transactional
    public void unlink(UserAccount owner, UUID projectId) {
        var project = access.requireProject(owner, projectId);
        if (links.findByProjectId(project.getId()).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "This project does not have a linked repository.");
        }
        links.deleteByProjectId(project.getId());
        commitPoller.evict(project.getId());
    }

    @Transactional(readOnly = true)
    public List<GitHubModels.CommitSummary> commits(UserAccount owner, UUID projectId) {
        return commitPoller.cachedCommits(linked(owner, projectId));
    }

    @Transactional(readOnly = true)
    public CommitPollResponse commitPoll(UserAccount owner, UUID projectId) {
        return commitPoller.status(linked(owner, projectId));
    }

    @Transactional(readOnly = true)
    public CommitPollResponse pollCommits(UserAccount owner, UUID projectId) {
        return commitPoller.pollNow(linked(owner, projectId));
    }

    @Transactional(readOnly = true)
    public GitHubModels.CommitDetail commit(UserAccount owner, UUID projectId, String sha) {
        var link = linked(owner, projectId);
        github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
        GitHubModels.CommitDetail detail = github.getCommit(link.getOwnerName(), link.getRepositoryName(), sha);
        List<GitHubModels.CommitFile> files = detail.files().stream()
                .map(file -> new GitHubModels.CommitFile(file.filename(), file.previousFilename(), file.status(), null, file.additions(), file.deletions()))
                .toList();
        return new GitHubModels.CommitDetail(detail.sha(), detail.message(), detail.committedAt(), detail.parentShas(), files);
    }

    @Transactional(readOnly = true)
    public BranchListResponse branches(UserAccount owner, UUID projectId) {
        ProjectRepositoryLink link = linked(owner, projectId);
        GitHubModels.Repository repository = github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
        GitHubModels.BranchPage page = github.listBranches(link.getOwnerName(), link.getRepositoryName());
        List<BranchListResponse.BranchItem> items = new ArrayList<>();
        for (String name : page.names()) {
            items.add(new BranchListResponse.BranchItem(
                    name,
                    name.equals(repository.defaultBranch()),
                    name.equals(link.branchForSync())));
        }
        return new BranchListResponse(repository.defaultBranch(), link.branchForSync(), page.truncated(), items);
    }

    @Transactional
    public BranchSelectionResponse selectBranch(UserAccount owner, UUID projectId, String branch) {
        var project = access.requireProject(owner, projectId);
        ProjectRepositoryLink link = access.requireRepository(project.getId());
        GitHubModels.Repository repository = github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
        if (repository.id() != link.getGithubRepositoryId()) {
            throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_IDENTITY_CHANGED", "The linked repository id no longer matches GitHub.");
        }
        String selected = resolveBranch(repository, branch);
        link.setDefaultBranch(repository.defaultBranch());
        link.setSelectedBranch(selected);
        link.setLastSyncedAt(Instant.now());
        commitPoller.evict(project.getId());
        UUID workflowId = null;
        List<GitHubModels.CommitSummary> commits = github.listCommits(link.getOwnerName(), link.getRepositoryName(), selected, 1);
        if (!commits.isEmpty() && commits.getFirst().sha() != null && !commits.getFirst().sha().isBlank()) {
            link.setLastSeenCommitSha(commits.getFirst().sha());
            RepositoryWorkflows starter = workflows.getIfAvailable();
            if (starter != null) {
                workflowId = starter.syncBranch(project.getId(), selected, commits.getFirst().sha());
            }
        }
        log.info("Selected branch {} for project {} at commit {}",
                selected,
                project.getId(),
                commits.isEmpty() ? "none" : commits.getFirst().sha());
        return new BranchSelectionResponse(summary(link), workflowId);
    }

    @Transactional(readOnly = true)
    public List<GitHubModels.PullRequest> pulls(UserAccount owner, UUID projectId) {
        var link = linked(owner, projectId);
        github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
        return github.listPullRequests(link.getOwnerName(), link.getRepositoryName(), 20);
    }

    private ProjectRepositoryLink linked(UserAccount owner, UUID projectId) {
        var project = access.requireProject(owner, projectId);
        return access.requireRepository(project.getId());
    }

    private static ProjectResponse.RepositorySummary summary(ProjectRepositoryLink link) {
        return new ProjectResponse.RepositorySummary(
                link.getGithubRepositoryId(),
                link.getOwnerName(),
                link.getRepositoryName(),
                link.getDefaultBranch(),
                link.branchForSync(),
                link.getRepositoryUrl(),
                "public",
                link.getLastSyncedAt());
    }

    private String currentTip(ProjectRepositoryLink link) {
        try {
            List<GitHubModels.CommitSummary> commits = github.listCommits(
                    link.getOwnerName(), link.getRepositoryName(), link.branchForSync(), 1);
            if (commits.isEmpty() || commits.getFirst().sha() == null || commits.getFirst().sha().isBlank()) {
                return null;
            }
            return commits.getFirst().sha();
        } catch (RuntimeException ex) {
            log.warn("Could not record the current commit while linking {}/{} for project {}",
                    link.getOwnerName(), link.getRepositoryName(), link.getProject().getId(), ex);
            return null;
        }
    }

    private String resolveBranch(GitHubModels.Repository repository, String requested) {
        if (requested == null || requested.isBlank()) {
            return repository.defaultBranch();
        }
        String branch = requested.trim();
        if (!BranchNames.isSafe(branch)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_BRANCH", "Branch name is not allowed.");
        }
        GitHubModels.BranchPage page = github.listBranches(repository.owner(), repository.name());
        if (!page.names().contains(branch)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BRANCH_NOT_FOUND", "That branch was not found on the public repository.");
        }
        return branch;
    }
}

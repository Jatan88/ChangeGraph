package com.changegraph.backend.github;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import com.changegraph.backend.project.ProjectResponse;
import com.changegraph.backend.user.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GitHubRepositoryService {

    private final ProjectAccess access;
    private final ProjectRepositoryLinkRepository links;
    private final GitHubClient github;
    private final GitHubCommitPoller commitPoller;

    public GitHubRepositoryService(
            ProjectAccess access,
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            GitHubCommitPoller commitPoller) {
        this.access = access;
        this.links = links;
        this.github = github;
        this.commitPoller = commitPoller;
    }

    @Transactional
    public ProjectResponse.RepositorySummary link(UserAccount owner, UUID projectId, String repositoryUrl) {
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
        link.setLastSyncedAt(Instant.now());
        links.save(link);
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
                link.getRepositoryUrl(),
                "public",
                link.getLastSyncedAt());
    }
}

package com.changegraph.backend.github;

import com.changegraph.backend.auth.CurrentUser;
import com.changegraph.backend.project.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class GitHubRepositoryController {

    private final GitHubRepositoryService repositories;
    private final CurrentUser currentUser;

    public GitHubRepositoryController(GitHubRepositoryService repositories, CurrentUser currentUser) {
        this.repositories = repositories;
        this.currentUser = currentUser;
    }

    @PostMapping("/repository")
    public ResponseEntity<ProjectResponse.RepositorySummary> link(
            @PathVariable UUID projectId,
            @Valid @RequestBody LinkRepositoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(repositories.link(currentUser.require(), projectId, request.repositoryUrl()));
    }

    @GetMapping("/repository")
    public ProjectResponse.RepositorySummary get(@PathVariable UUID projectId) {
        return repositories.get(currentUser.require(), projectId);
    }

    @DeleteMapping("/repository")
    public ResponseEntity<Void> unlink(@PathVariable UUID projectId) {
        repositories.unlink(currentUser.require(), projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/commits")
    public List<GitHubModels.CommitSummary> commits(@PathVariable UUID projectId) {
        return repositories.commits(currentUser.require(), projectId);
    }

    @GetMapping("/github-poll")
    public CommitPollResponse commitPoll(@PathVariable UUID projectId) {
        return repositories.commitPoll(currentUser.require(), projectId);
    }

    @PostMapping("/github-poll")
    public CommitPollResponse pollCommits(@PathVariable UUID projectId) {
        return repositories.pollCommits(currentUser.require(), projectId);
    }

    @GetMapping("/commits/{sha}")
    public GitHubModels.CommitDetail commit(@PathVariable UUID projectId, @PathVariable String sha) {
        return repositories.commit(currentUser.require(), projectId, sha);
    }

    @GetMapping("/pulls")
    public List<GitHubModels.PullRequest> pulls(@PathVariable UUID projectId) {
        return repositories.pulls(currentUser.require(), projectId);
    }
}

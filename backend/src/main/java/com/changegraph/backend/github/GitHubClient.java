package com.changegraph.backend.github;

import java.util.List;

public interface GitHubClient {

    GitHubModels.Repository getPublicRepository(String owner, String name);

    List<GitHubModels.CommitSummary> listCommits(String owner, String name, String branch, int limit);

    GitHubModels.CommitDetail getCommit(String owner, String name, String sha);

    GitHubModels.PullRequest getPullRequest(String owner, String name, int number);

    List<GitHubModels.PullRequest> listPullRequests(String owner, String name, int limit);

    String getFile(String owner, String name, String path, String ref);

    List<String> listSourceFiles(String owner, String name, String directory, String ref);

    List<String> listRepositorySourceFiles(String owner, String name, String ref);

    GitHubModels.BranchPage listBranches(String owner, String name);

    void createBranch(String owner, String name, String branch, String fromSha);

    void updateFile(String owner, String name, String branch, String path, String message, String content);

    GitHubModels.CreatedPullRequest openPullRequest(
            String owner,
            String name,
            String title,
            String head,
            String base,
            String body);
}

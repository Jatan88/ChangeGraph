package com.changegraph.backend.github;

import java.util.List;
import java.util.UUID;

public interface NewPullRequestListener {

    void onOpenPullRequests(UUID projectId, List<GitHubModels.PullRequest> pullRequests);
}

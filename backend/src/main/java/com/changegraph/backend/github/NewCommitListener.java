package com.changegraph.backend.github;

import java.util.List;
import java.util.UUID;

public interface NewCommitListener {

    void onNewCommits(UUID projectId, String branch, List<GitHubModels.CommitSummary> commits);
}

package com.changegraph.backend.github;

import java.time.Instant;
import java.util.List;

public record CommitPollResponse(
        List<GitHubModels.CommitSummary> commits,
        Instant lastPolledAt,
        long pollIntervalSeconds) {
}

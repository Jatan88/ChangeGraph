package com.changegraph.backend.github;

import java.util.List;

public final class GitHubModels {

    private GitHubModels() {
    }

    public record Repository(
            long id,
            String owner,
            String name,
            String defaultBranch,
            String url,
            boolean publicRepository) {
    }

    public record CommitSummary(String sha, String message, String committedAt) {
    }

    public record CommitFile(
            String filename,
            String previousFilename,
            String status,
            String patch,
            int additions,
            int deletions) {
    }

    public record CommitDetail(
            String sha,
            String message,
            String committedAt,
            List<String> parentShas,
            List<CommitFile> files) {
    }

    public record PullRequest(
            int number,
            String title,
            String headSha,
            String baseSha,
            String htmlUrl,
            String headRef,
            String baseRef) {
    }

    public record BranchPage(java.util.List<String> names, boolean truncated) {
    }

    public record CreatedPullRequest(int number, String htmlUrl, String headRef, String baseRef) {
    }
}

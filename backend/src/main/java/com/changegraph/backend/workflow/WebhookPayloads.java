package com.changegraph.backend.workflow;

import tools.jackson.databind.JsonNode;

import java.util.Set;

public final class WebhookPayloads {

    private static final Set<String> PULL_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private WebhookPayloads() {
    }

    public record Decision(String disposition, Accepted accepted, String reason) {
        public static Decision ignore(String reason) {
            return new Decision("IGNORE", null, reason);
        }

        public static Decision reject(String reason) {
            return new Decision("REJECT", null, reason);
        }

        public static Decision accept(Accepted accepted) {
            return new Decision("ACCEPT", accepted, "accepted");
        }
    }

    public record Accepted(
            long repositoryId,
            WorkflowKind kind,
            String commitSha,
            Integer pullRequestNumber,
            String sourceBranch,
            String targetBranch,
            String title) {
    }

    public static Decision interpret(String githubEvent, JsonNode payload, String selectedBranch) {
        if (payload == null || payload.isMissingNode()) {
            return Decision.reject("payload is missing");
        }
        JsonNode repository = payload.path("repository");
        long repositoryId = repository.path("id").asLong(0);
        if (repositoryId <= 0) {
            return Decision.reject("repository id is missing");
        }
        if (repository.path("private").asBoolean(true) || "private".equalsIgnoreCase(repository.path("visibility").asString(""))) {
            return Decision.ignore("repository is private");
        }
        if ("push".equals(githubEvent)) {
            return push(payload, repositoryId, selectedBranch);
        }
        if ("pull_request".equals(githubEvent)) {
            return pullRequest(payload, repositoryId);
        }
        return Decision.ignore("event is not push or pull_request");
    }

    private static Decision push(JsonNode payload, long repositoryId, String selectedBranch) {
        String ref = payload.path("ref").asString("");
        String after = payload.path("after").asString("");
        if (!ref.startsWith("refs/heads/") || after.isBlank() || after.chars().allMatch(ch -> ch == '0')) {
            return Decision.ignore("push is not a branch update");
        }
        String branch = ref.substring("refs/heads/".length());
        if (selectedBranch == null || selectedBranch.isBlank() || !selectedBranch.equals(branch)) {
            String selected = selectedBranch == null || selectedBranch.isBlank() ? "unset" : selectedBranch;
            return Decision.ignore("push is on " + branch + ", selected branch is " + selected);
        }
        if (!after.matches("[0-9a-fA-F]{7,40}")) {
            return Decision.reject("commit sha is not a git sha");
        }
        String message = "";
        JsonNode commits = payload.path("commits");
        if (commits.isArray() && !commits.isEmpty()) {
            message = commits.get(commits.size() - 1).path("message").asString("");
        }
        String title = UntrustedText.data(message, 240);
        if (title.isBlank()) {
            title = "Commit " + after.substring(0, Math.min(7, after.length()));
        }
        return Decision.accept(new Accepted(repositoryId, WorkflowKind.PUSH, after, null, branch, branch, title));
    }

    private static Decision pullRequest(JsonNode payload, long repositoryId) {
        String action = payload.path("action").asString("");
        if (!PULL_ACTIONS.contains(action)) {
            return Decision.ignore("pull request action " + (action.isBlank() ? "missing" : action) + " is not reviewed");
        }
        JsonNode pull = payload.path("pull_request");
        int number = pull.path("number").asInt(0);
        String headSha = pull.path("head").path("sha").asString("");
        String headRef = pull.path("head").path("ref").asString("");
        String baseRef = pull.path("base").path("ref").asString("");
        if (number <= 0 || !headSha.matches("[0-9a-fA-F]{7,40}") || headRef.isBlank() || baseRef.isBlank()) {
            return Decision.reject("pull request payload is incomplete");
        }
        String title = UntrustedText.data(pull.path("title").asString(""), 240);
        if (title.isBlank()) {
            title = "Pull request " + number;
        }
        return Decision.accept(new Accepted(
                repositoryId,
                WorkflowKind.PULL_REQUEST,
                headSha,
                number,
                headRef,
                baseRef,
                title));
    }
}

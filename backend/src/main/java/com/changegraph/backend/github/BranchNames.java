package com.changegraph.backend.github;

public final class BranchNames {

    private BranchNames() {
    }

    public static boolean isSafe(String branch) {
        if (branch == null) {
            return false;
        }
        String name = branch.trim();
        if (name.length() < 1 || name.length() > 255) {
            return false;
        }
        if (name.startsWith("/") || name.endsWith("/") || name.endsWith(".lock")) {
            return false;
        }
        if (name.contains("..") || name.contains("//") || name.contains("@{") || name.contains("\\")) {
            return false;
        }
        return name.matches("[A-Za-z0-9._/\\-]+");
    }

    public static String fixBranch(String commitSha, java.util.UUID runId, int attempt) {
        String sha = commitSha == null ? "change" : commitSha.toLowerCase(java.util.Locale.ROOT);
        String shortSha = sha.substring(0, Math.min(7, sha.length()));
        String run = runId.toString().replace("-", "");
        String suffix = run.substring(0, Math.min(8, run.length()));
        int safeAttempt = Math.max(1, attempt);
        return "changegraph/fix-" + shortSha + "-" + suffix + "-a" + safeAttempt;
    }

    public static boolean isFixBranch(String branch) {
        return branch != null && branch.startsWith("changegraph/fix-") && isSafe(branch);
    }
}

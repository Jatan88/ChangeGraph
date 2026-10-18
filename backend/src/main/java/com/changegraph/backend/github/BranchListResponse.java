package com.changegraph.backend.github;

import java.util.List;

public record BranchListResponse(
        String defaultBranch,
        String selectedBranch,
        boolean truncated,
        List<BranchItem> branches) {

    public record BranchItem(String name, boolean repositoryDefault, boolean selected) {
    }
}

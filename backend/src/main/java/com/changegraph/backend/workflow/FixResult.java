package com.changegraph.backend.workflow;

import java.util.List;

public record FixResult(
        boolean attempted,
        boolean pullRequestOpened,
        String explanation,
        String validation,
        String branch,
        String pullRequestUrl,
        Integer pullRequestNumber,
        List<String> unresolved) {

    public static FixResult notRequired(String explanation) {
        return new FixResult(false, false, explanation, "No repository scripts were executed.", null, null, null, List.of());
    }

    public static FixResult explained(String explanation, List<String> unresolved) {
        return new FixResult(
                true,
                false,
                explanation,
                "Static checks did not produce a pull request. Repository scripts were not executed.",
                null,
                null,
                null,
                unresolved);
    }
}

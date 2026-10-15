package com.changegraph.backend.github;

import com.changegraph.backend.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

public final class GitHubResponses {

    private GitHubResponses() {
    }

    public static void requirePublic(boolean privateRepository, String visibility) {
        if (privateRepository || visibility == null || !"public".equals(visibility)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "REPOSITORY_NOT_PUBLIC",
                    "The repository is missing, private, or not accessible. This version only supports public GitHub repositories.");
        }
    }

    public static boolean rateLimited(int status, String remaining, String body) {
        if (status != 403 && status != 429) {
            return false;
        }
        if ("0".equals(remaining)) {
            return true;
        }
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("rate limit") || lower.contains("secondary rate");
    }
}

package com.changegraph.backend.github;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LinkRepositoryRequest(
        @NotBlank @Size(max = 300) String repositoryUrl,
        @Size(max = 255) String branch) {
}

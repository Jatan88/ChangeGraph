package com.changegraph.backend.github;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SelectBranchRequest(@NotBlank @Size(max = 255) String branch) {
}

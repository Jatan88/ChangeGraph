package com.changegraph.backend.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description) {
}

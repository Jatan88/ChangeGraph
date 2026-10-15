package com.changegraph.backend.project;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @Size(min = 1, max = 80) String name,
        @Size(max = 500) String description) {
}

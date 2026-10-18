package com.changegraph.backend.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class IncidentRequests {

    private IncidentRequests() {
    }

    public record CreateIncident(
            @Pattern(regexp = IncidentIds.PATTERN, message = "Use 1–120 letters, digits, dots, dashes, or underscores.")
            @Size(max = 120)
            String id,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String summary,
            @Size(max = 40) String sourceType,
            Boolean synthetic) {
    }

    public record UpdateIncident(
            @Size(max = 200) String title,
            @Size(max = 2000) String summary,
            @Size(max = 40) String sourceType,
            Boolean synthetic) {
    }

    public record CreateEvidence(
            @Pattern(regexp = IncidentIds.PATTERN, message = "Use 1–120 letters, digits, dots, dashes, or underscores.")
            @Size(max = 120)
            String id,
            @NotBlank @Size(max = 40) String evidenceType,
            @NotBlank @Size(max = 40) String relationship,
            @NotBlank @Size(max = 40) String verificationStatus,
            @NotBlank @Size(max = 16) String confidence,
            @NotBlank @Size(max = 2000) String summary,
            @Size(max = 120) String functionId,
            @Size(max = 40) String sourceType,
            Boolean synthetic) {
    }

    public record UpdateEvidence(
            @Size(max = 40) String evidenceType,
            @Size(max = 40) String relationship,
            @Size(max = 40) String verificationStatus,
            @Size(max = 16) String confidence,
            @Size(max = 2000) String summary,
            @Size(max = 120) String functionId,
            @Size(max = 40) String sourceType,
            Boolean synthetic) {
    }
}

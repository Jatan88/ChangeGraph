package com.changegraph.backend.incident;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.graph.EdgeType;
import org.springframework.http.HttpStatus;

import java.util.Set;

final class IncidentCatalog {

    static final Set<String> EVIDENCE_TYPES = Set.of(
            "INCIDENT_REPORT",
            "HUMAN_REVIEWED_FINDING",
            "FIX_COMMIT",
            "REGRESSION_TEST");
    static final Set<String> VERIFICATION_STATUSES = Set.of("VERIFIED", "UNREVIEWED", "DISPUTED");
    static final Set<String> CONFIDENCE_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    static final Set<EdgeType> EVIDENCE_RELATIONSHIPS = Set.of(
            EdgeType.REPORTS_ON,
            EdgeType.SUPPORTS_CAUSE,
            EdgeType.SUPPORTS_RESOLUTION,
            EdgeType.VALIDATES_FIX);

    private IncidentCatalog() {
    }

    static String evidenceType(String value) {
        String normalized = require(value, "evidenceType");
        if (!EVIDENCE_TYPES.contains(normalized)) {
            throw invalid("evidenceType", normalized);
        }
        return normalized;
    }

    static EdgeType relationship(String value) {
        String normalized = require(value, "relationship");
        EdgeType type = EdgeType.fromName(normalized);
        if (type == null || !EVIDENCE_RELATIONSHIPS.contains(type)) {
            throw invalid("relationship", normalized);
        }
        return type;
    }

    static String verificationStatus(String value) {
        String normalized = require(value, "verificationStatus");
        if (!VERIFICATION_STATUSES.contains(normalized)) {
            throw invalid("verificationStatus", normalized);
        }
        return normalized;
    }

    static String confidence(String value) {
        String normalized = require(value, "confidence");
        if (!CONFIDENCE_LEVELS.contains(normalized)) {
            throw invalid("confidence", normalized);
        }
        return normalized;
    }

    static String sourceType(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", field + " is required.");
        }
        return value.trim().toUpperCase();
    }

    private static ApiException invalid(String field, String value) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "VALIDATION_ERROR",
                field + " \"" + value + "\" is not supported.");
    }
}

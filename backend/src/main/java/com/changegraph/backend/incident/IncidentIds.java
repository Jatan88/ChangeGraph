package com.changegraph.backend.incident;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.Text;
import org.springframework.http.HttpStatus;

import java.util.UUID;

final class IncidentIds {

    static final String PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,119}";

    private IncidentIds() {
    }

    static String incidentId(String requested) {
        return nodeId(requested, "incident");
    }

    static String evidenceId(String requested) {
        return nodeId(requested, "evidence");
    }

    private static String nodeId(String requested, String prefix) {
        String cleaned = Text.blankToNull(requested);
        if (cleaned == null) {
            return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
        }
        if (!cleaned.matches(PATTERN)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_ID", "The id uses characters that are not allowed.");
        }
        return cleaned;
    }
}

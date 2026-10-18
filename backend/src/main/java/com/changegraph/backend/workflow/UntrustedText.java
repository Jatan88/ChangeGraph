package com.changegraph.backend.workflow;

import com.changegraph.backend.common.Text;

public final class UntrustedText {

    private UntrustedText() {
    }

    public static String data(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value
                .replace("```", "'''")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return Text.clamp(cleaned, max);
    }
}

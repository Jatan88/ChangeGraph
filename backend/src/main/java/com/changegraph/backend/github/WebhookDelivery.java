package com.changegraph.backend.github;

import java.net.URI;

public final class WebhookDelivery {

    private WebhookDelivery() {
    }

    public static boolean reachable(String secret, String origin) {
        return secret != null && !secret.isBlank() && !local(origin);
    }

    public static boolean local(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(origin.trim()).getHost();
            if (host == null || host.isBlank()) {
                return true;
            }
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (RuntimeException ex) {
            return true;
        }
    }
}

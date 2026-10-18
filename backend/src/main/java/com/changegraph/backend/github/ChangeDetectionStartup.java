package com.changegraph.backend.github;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ChangeDetectionStartup {

    private static final Logger log = LoggerFactory.getLogger(ChangeDetectionStartup.class);

    private final String secret;
    private final String origin;
    private final long initialDelayMs;
    private final Duration pollInterval;

    public ChangeDetectionStartup(
            @Value("${changegraph.github.webhook-secret:${GITHUB_WEBHOOK_SECRET:}}") String secret,
            @Value("${changegraph.cors.allowed-origin:http://localhost:5173}") String origin,
            @Value("${changegraph.github.poll-initial-delay-ms:15000}") long initialDelayMs,
            Duration githubPollInterval) {
        this.secret = secret == null ? "" : secret.trim();
        this.origin = origin == null ? "" : origin.trim();
        this.initialDelayMs = initialDelayMs;
        this.pollInterval = githubPollInterval;
    }

    @PostConstruct
    void logDetectionMode() {
        long minutes = Math.max(1, pollInterval.toMinutes());
        if (!WebhookDelivery.reachable(secret, origin)) {
            if (secret.isBlank()) {
                log.warn("GitHub webhooks are disabled because GITHUB_WEBHOOK_SECRET is empty.");
            }
            if (WebhookDelivery.local(origin)) {
                log.warn("GitHub cannot deliver webhooks to {}.", origin.isBlank() ? "localhost" : origin);
            }
            log.warn("Polling each linked repository for commits and open pull requests every {} minute(s), first poll {} ms after startup. A commit or pull request that arrives after the first poll is queued. History already on the branch is not queued.",
                    minutes, initialDelayMs);
            return;
        }
        log.info("GitHub webhooks are accepted at POST /api/github/webhooks for {}. Polling still checks the selected branch every {} minutes.",
                origin, minutes);
    }
}

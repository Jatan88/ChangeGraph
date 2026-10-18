package com.changegraph.backend.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Configuration
@EnableScheduling
public class GitHubPollConfig {

    @Bean
    public Duration githubPollInterval(
            @Value("${changegraph.github.poll-interval-minutes:30}") long minutes,
            @Value("${changegraph.github.webhook-secret:${GITHUB_WEBHOOK_SECRET:}}") String secret,
            @Value("${changegraph.cors.allowed-origin:http://localhost:5173}") String origin) {
        if (!WebhookDelivery.reachable(secret, origin)) {
            return Duration.ofMinutes(1);
        }
        return GitHubCommitPoller.interval(minutes);
    }

    @Bean
    public Boolean githubPollOpenPullRequests(
            @Value("${changegraph.github.webhook-secret:${GITHUB_WEBHOOK_SECRET:}}") String secret,
            @Value("${changegraph.cors.allowed-origin:http://localhost:5173}") String origin) {
        return !WebhookDelivery.reachable(secret, origin);
    }
}

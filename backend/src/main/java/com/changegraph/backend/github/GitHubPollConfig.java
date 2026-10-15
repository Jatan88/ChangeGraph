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
            @Value("${changegraph.github.poll-interval-minutes:30}") long minutes) {
        return GitHubCommitPoller.interval(minutes);
    }
}

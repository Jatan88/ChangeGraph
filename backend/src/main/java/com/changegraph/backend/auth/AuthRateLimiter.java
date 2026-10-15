package com.changegraph.backend.auth;

import com.changegraph.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthRateLimiter {

    private final int maxAttempts;
    private final long windowSeconds;
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public AuthRateLimiter(
            @Value("${changegraph.security.auth.max-attempts:10}") int maxAttempts,
            @Value("${changegraph.security.auth.window-seconds:60}") long windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
    }

    public void check(HttpServletRequest request, String action) {
        String key = request.getRemoteAddr() + "|" + action;
        long now = Instant.now().getEpochSecond();
        Deque<Long> window = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= windowSeconds) {
                window.removeFirst();
            }
            if (window.size() >= maxAttempts) {
                throw new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMITED",
                        "Too many authentication attempts. Try again later.");
            }
            window.addLast(now);
        }
    }

    public void reset(HttpServletRequest request, String action) {
        attempts.remove(request.getRemoteAddr() + "|" + action);
    }
}

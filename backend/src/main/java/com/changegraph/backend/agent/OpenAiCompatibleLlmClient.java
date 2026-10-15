package com.changegraph.backend.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final URI completions;
    private final String apiKey;
    private final String model;
    private final int maxOutputTokens;
    private final boolean configured;

    public OpenAiCompatibleLlmClient(
            JsonMapper jsonMapper,
            @Value("${changegraph.llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${changegraph.llm.api-key:}") String apiKey,
            @Value("${changegraph.llm.model:gpt-4o-mini}") String model,
            @Value("${changegraph.llm.timeout-ms:20000}") long timeoutMs,
            @Value("${changegraph.llm.max-output-tokens:700}") int maxOutputTokens) {
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
        this.maxOutputTokens = Math.max(100, Math.min(maxOutputTokens, 2000));
        URI endpoint = null;
        boolean usable = !this.apiKey.isBlank();
        if (usable) {
            try {
                String root = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
                URI base = URI.create(root);
                if (!"https".equalsIgnoreCase(base.getScheme()) || base.getUserInfo() != null) {
                    usable = false;
                } else {
                    endpoint = URI.create(root + "chat/completions");
                }
            } catch (RuntimeException ex) {
                usable = false;
            }
        }
        this.configured = usable;
        this.completions = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (!this.apiKey.isBlank() && !usable) {
            log.warn("LLM client is disabled because the base URL is not an https endpoint without credentials");
        }
    }

    @Override
    public boolean available() {
        return configured;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!configured) {
            throw new LlmUnavailableException("The language model is not configured.");
        }
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", maxOutputTokens);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        HttpRequest request = HttpRequest.newBuilder(completions)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new LlmUnavailableException("The language model was unavailable.");
            }
            if (response.statusCode() != 200) {
                throw new LlmUnavailableException("The language model rejected the request.");
            }
            var root = jsonMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asString("");
            if (content.isBlank()) {
                throw new LlmUnavailableException("The language model returned an empty response.");
            }
            return content;
        } catch (LlmUnavailableException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("The language model request was interrupted.");
        } catch (Exception ex) {
            log.warn("LLM request failed ({})", ex.getClass().getSimpleName());
            throw new LlmUnavailableException("The language model could not be reached.");
        }
    }
}

package com.changegraph.backend.agent;

public interface LlmClient {

    boolean available();

    String complete(String systemPrompt, String userPrompt);
}

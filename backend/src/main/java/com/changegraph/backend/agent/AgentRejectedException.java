package com.changegraph.backend.agent;

public class AgentRejectedException extends RuntimeException {

    private final String code;

    public AgentRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

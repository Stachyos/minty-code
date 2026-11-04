package com.minty.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public record AgentProperties(String python, String worker, String workspaces,
                              String apiKey, String baseUrl, String model,
                              int maxSteps, int timeoutSeconds, int maxConcurrentRuns, boolean allowShell) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("your-api-key")
                && !apiKey.equals("sk-ant-xxx");
    }
}

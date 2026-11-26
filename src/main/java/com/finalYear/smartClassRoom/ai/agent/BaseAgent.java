package com.finalYear.smartClassRoom.ai.agent;

import com.finalYear.smartClassRoom.ai.model.AgentRequest;
import com.finalYear.smartClassRoom.ai.model.AgentResponse;
import com.finalYear.smartClassRoom.ai.provider.AIModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public abstract class BaseAgent {

    @Autowired
    protected AIModelProvider aiModelProvider;

    /**
     * Primary invocation point for agents to call the configured AI model provider.
     */
    protected String callAI(String systemPrompt, String userPrompt) {
        if (aiModelProvider == null) {
            log.error("AIModelProvider is not injected in {}", agentName());
            return "AI service is currently not initialized.";
        }
        return aiModelProvider.generateResponse(systemPrompt, userPrompt);
    }

    /**
     * Extended invocation point supporting multi-turn conversation history.
     */
    protected String callAIWithHistory(String systemPrompt, String userPrompt, java.util.List<java.util.Map<String, String>> history) {
        if (aiModelProvider == null) {
            log.error("AIModelProvider is not injected in {}", agentName());
            return "AI service is currently not initialized.";
        }
        if (aiModelProvider instanceof com.finalYear.smartClassRoom.ai.provider.DelegatingAIProvider delegator) {
            return delegator.generateResponseWithHistory(systemPrompt, userPrompt, history);
        }
        return aiModelProvider.generateResponse(systemPrompt, userPrompt);
    }

    /**
     * Preserved for backward compatibility with all child agents.
     * Delegates directly to callAI.
     */
    protected String callOllama(String systemPrompt, String userPrompt) {
        return callAI(systemPrompt, userPrompt);
    }

    public abstract AgentResponse handle(AgentRequest request);

    protected abstract String agentName();
}

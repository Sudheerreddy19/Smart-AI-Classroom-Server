package com.finalYear.smartClassRoom.ai.agent;

import com.finalYear.smartClassRoom.ai.model.AgentRequest;
import com.finalYear.smartClassRoom.ai.model.AgentResponse;
import com.finalYear.smartClassRoom.ai.prompt.PromptProvider;
import com.finalYear.smartClassRoom.ai.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ChatAgent extends BaseAgent {

    @Autowired
    private PromptProvider promptProvider;

    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    @Override
    public AgentResponse handle(AgentRequest request) {
        String baseSystemPrompt = promptProvider.getPrompt(request.getRole());
        String effectiveSystemPrompt = baseSystemPrompt;

        // Augment prompt with RAG knowledge chunks if available
        if (knowledgeBaseService != null) {
            try {
                KnowledgeBaseService.RagAugmentation rag = knowledgeBaseService.augmentPromptWithKnowledge(
                        request.getPrompt(),
                        request.getRole(),
                        request.getClassroomId()
                );
                effectiveSystemPrompt = rag.augmentedSystemPrompt() + "\n\n" + baseSystemPrompt;
            } catch (Exception e) {
                log.warn("RAG retrieval failed, falling back to base prompt: {}", e.getMessage());
            }
        }

        String response = callAI(effectiveSystemPrompt, request.getPrompt());

        if (response != null && !response.isBlank()) {
            return AgentResponse.ollama(response, agentName());
        }

        return AgentResponse.text(
                "Sorry, I couldn't connect to the AI model.",
                agentName()
        );
    }

    @Override
    protected String agentName() {
        return "ChatAgent";
    }
}

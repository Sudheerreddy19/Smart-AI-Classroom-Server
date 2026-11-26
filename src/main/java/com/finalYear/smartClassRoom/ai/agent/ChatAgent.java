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

        // Intelligent RAG Gating: Only augment with curriculum vectors if the query requires course documents
        boolean shouldTriggerRag = shouldPerformCurriculumSearch(request.getPrompt());

        if (shouldTriggerRag && knowledgeBaseService != null) {
            try {
                KnowledgeBaseService.RagAugmentation rag = knowledgeBaseService.augmentPromptWithKnowledge(
                        request.getPrompt(),
                        request.getRole(),
                        request.getClassroomId()
                );
                if (rag.hasStrongEvidence()) {
                    log.info("RAG search produced strong evidence with {} citations.", rag.citations().size());
                    effectiveSystemPrompt = rag.augmentedSystemPrompt() + "\n\n" + baseSystemPrompt;
                } else {
                    log.info("RAG search found no strong curriculum match; proceeding with general AI knowledge.");
                }
            } catch (Exception e) {
                log.warn("RAG retrieval failed, falling back to base prompt: {}", e.getMessage());
            }
        } else {
            log.info("RAG search skipped for non-curriculum/conversational query: {}", request.getPrompt());
        }

        String response = (request.getConversationHistory() != null && !request.getConversationHistory().isEmpty())
                ? callAIWithHistory(effectiveSystemPrompt, request.getPrompt(), request.getConversationHistory())
                : callAI(effectiveSystemPrompt, request.getPrompt());

        if (response != null && !response.isBlank()) {
            return AgentResponse.ollama(response, agentName());
        }

        return AgentResponse.text(
                "Sorry, I couldn't connect to the AI model.",
                agentName()
        );
    }

    /**
     * Determines whether curriculum document retrieval would genuinely help answer the question.
     * Skips RAG for greetings, casual chit-chat, meta commands, and general queries without course context.
     */
    private boolean shouldPerformCurriculumSearch(String query) {
        if (query == null || query.isBlank()) return false;
        String lower = query.toLowerCase().trim();

        // 1. Skip casual conversation, greetings and jokes
        if (lower.startsWith("hi") || lower.startsWith("hello") || lower.startsWith("hey")
                || lower.contains("how are you") || lower.contains("what's up") || lower.contains("whats up")
                || lower.contains("how was your day") || lower.contains("tell me a joke")
                || lower.contains("who are you") || lower.contains("thank you") || lower.contains("thanks")
                || lower.contains("bye") || lower.contains("goodbye")) {
            return false;
        }

        // 2. Explicitly triggers RAG when user refers to curriculum/notes/textbooks/syllabus
        if (lower.contains("uploaded") || lower.contains("notes") || lower.contains("syllabus")
                || lower.contains("textbook") || lower.contains("curriculum") || lower.contains("chapter")
                || lower.contains("course material") || lower.contains("slide") || lower.contains("pdf")) {
            return true;
        }

        // 3. For educational queries with more than 3 words, allow retrieval if course documents exist
        return lower.split("\\s+").length >= 3;
    }

    @Override
    protected String agentName() {
        return "ChatAgent";
    }
}

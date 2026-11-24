package com.nitish.eka.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagController.class);

    private final ChatClient chatClient;

    public RagController(ChatClient.Builder chatClientBuilder) {
        // Build the client with a default system prompt
        this.chatClient = chatClientBuilder
                .defaultSystem("You are a helpful senior software engineering assistant.")
                .build();
    }

    // We will upgrade this to a POST endpoint with the DTOs later
    @GetMapping("/ask")
    public String askQuestion(@RequestParam String question) {
        try {
            // 1. Primary attempt: uses model configured in application.yml (e.g., gemini-4-hydrogen)
            return chatClient.prompt()
                    .user(question)
                    .call()
                    .content(); // Extracts the raw text from the AI's response
        } catch (Exception e) {
            // 2. Automatic failover if primary pool hits 503 High Demand
            LOGGER.error(e.getMessage(), e);
            LOGGER.info("Attempt with primary chat model failed; Falling back to default model");
            return chatClient.prompt()
                    .options(GoogleGenAiChatOptions.builder()
                            .model("gemini-3.5-flash-lite")
                            .temperature(0.0))
                    .user(question)
                    .call()
                    .content();
        }
    }
}

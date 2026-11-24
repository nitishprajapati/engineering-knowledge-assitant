package com.nitish.eka.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

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
        return chatClient.prompt()
                .user(question)
                .call()
                .content(); // Extracts the raw text from the AI's response
    }
}

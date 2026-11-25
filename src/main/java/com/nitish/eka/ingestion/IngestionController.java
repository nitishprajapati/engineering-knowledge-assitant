package com.nitish.eka.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ingest")
public class IngestionController {

    private final VectorStore vectorStore;

    public IngestionController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostMapping("/sample")
    public Map<String, Object> seedSampleDocuments() {
        List<Document> docs = List.of(
                new Document(
                        "HikariCP connection pool exhaustion occurs when slow SQL queries hold JDBC connections longer than connection-timeout. Check pg_stat_activity for long-running queries and add missing indexes.",
                        Map.of("source", "db-runbook.md", "topic", "database")
                ),
                new Document(
                        "Redis OOM command not allowed happens when memory reaches maxmemory with noeviction policy. Inspect big keys using redis-cli --bigkeys and configure allkeys-lru or shorter TTLs.",
                        Map.of("source", "redis-runbook.md", "topic", "caching")
                ),
                new Document(
                        "HTTP 401 Invalid JWT Signature is triggered when the signing key rotates or clock skew exceeds leeway. Verify JWKS endpoint caching and NTP sync across service instances.",
                        Map.of("source", "auth-runbook.md", "topic", "security")
                ),
                new Document(
                        "RabbitMQ poison messages loop indefinitely if consumers throw unhandled exceptions with default requeue enabled. Route failed deliveries to a dead-letter exchange after bounded retries.",
                        Map.of("source", "messaging-runbook.md", "topic", "rabbitmq")
                )
        );

        vectorStore.add(docs);
        return Map.of("status", "ingested", "count", docs.size());
    }

    @GetMapping("/search")
    public List<SearchHit> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "2") int topK) {

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        return vectorStore.similaritySearch(request).stream()
                .map(doc -> new SearchHit(doc.getText(), doc.getMetadata(), doc.getScore()))
                .toList();
    }

    public record SearchHit(String content, Map<String, Object> metadata, Double score) {}
}
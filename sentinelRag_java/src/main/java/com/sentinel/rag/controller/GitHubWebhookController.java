package com.sentinel.rag.controller;

import com.sentinel.rag.github.WebhookIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/github")
public class GitHubWebhookController {

    private final WebhookIngestionService webhookIngestionService;

    public GitHubWebhookController(WebhookIngestionService webhookIngestionService) {
        this.webhookIngestionService = webhookIngestionService;
    }

    /**
     * @RequestBody String, not a parsed DTO — HMAC verification (inside
     * WebhookIngestionService) needs the exact raw bytes GitHub signed.
     * X-GitHub-Delivery is tracked for delivery idempotency (Plan §1.4, §3.1).
     */
    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody String rawPayload
    ) {
        try {
            webhookIngestionService.ingest(rawPayload, signature, eventType, deliveryId);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}

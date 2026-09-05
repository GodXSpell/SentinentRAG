package com.sentinel.rag.controller;

import com.sentinel.rag.entity.CiBuildRun;
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
     * WebhookIngestionService) needs the exact raw bytes GitHub signed. Spring's default
     * auto-deserialization would reconstruct JSON that's logically equivalent but not
     * byte-identical to what GitHub sent (different whitespace, key ordering, etc.),
     * which would silently break every signature check.
     */
    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestBody String rawPayload
    ) {
        try {
            webhookIngestionService.ingest(rawPayload, signature, eventType);
            // 200 OK regardless of whether a build was actually created — GitHub only
            // cares that delivery succeeded. "Event type we don't act on" and "PR action
            // wasn't 'opened'" are both legitimate no-ops, not errors.
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
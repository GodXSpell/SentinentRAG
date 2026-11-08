package com.sentinel.rag.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import com.sentinel.rag.orchestration.EvaluationDispatcher;
import com.sentinel.rag.repository.CiBuildRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookIngestionServiceTest {

    @Mock
    private GitHubWebhookVerifier verifier;

    @Mock
    private CiBuildRunRepository ciBuildRunRepository;

    @Mock
    private EvaluationDispatcher evaluationDispatcher;

    private WebhookIngestionService service;
    private final String secret = "test-secret";
    private final String defaultSuiteId = "5fb896fe-9a4d-4a06-89d5-1d93dbdede73";

    @BeforeEach
    void setUp() {
        service = new WebhookIngestionService(
                verifier,
                new ObjectMapper(),
                ciBuildRunRepository,
                evaluationDispatcher,
                secret,
                defaultSuiteId,
                "test-token",
                "GodXSpell/SentinentRAG"
        );
    }

    @Test
    void ingest_throwsSecurityExceptionOnInvalidSignature() {
        when(verifier.isValidSignature(anyString(), anyString(), eq(secret))).thenReturn(false);

        assertThrows(SecurityException.class, () ->
                service.ingest("{}", "sha256=invalid", "push", "deliv-1")
        );
        verifyNoInteractions(ciBuildRunRepository);
        verifyNoInteractions(evaluationDispatcher);
    }

    @Test
    void ingest_ignoresDuplicateDeliveryId() {
        when(verifier.isValidSignature(anyString(), anyString(), eq(secret))).thenReturn(true);
        when(ciBuildRunRepository.findByDeliveryId("deliv-1")).thenReturn(Optional.of(new CiBuildRun()));

        Optional<CiBuildRun> result = service.ingest("{}", "sha256=valid", "push", "deliv-1");

        assertTrue(result.isEmpty());
        verify(ciBuildRunRepository, never()).save(any());
        verifyNoInteractions(evaluationDispatcher);
    }

    @Test
    void ingest_rejectsForkPullRequest() {
        when(verifier.isValidSignature(anyString(), anyString(), eq(secret))).thenReturn(true);
        when(ciBuildRunRepository.findByDeliveryId("deliv-pr-1")).thenReturn(Optional.empty());

        String forkPayload = """
                {
                  "action": "opened",
                  "pull_request": {
                    "number": 42,
                    "head": {
                      "sha": "abc1234",
                      "ref": "patch-1",
                      "repo": { "full_name": "fork-user/SentinentRAG" }
                    },
                    "base": {
                      "sha": "def5678",
                      "ref": "main",
                      "repo": { "full_name": "GodXSpell/SentinentRAG" }
                    }
                  }
                }
                """;

        Optional<CiBuildRun> result = service.ingest(forkPayload, "sha256=valid", "pull_request", "deliv-pr-1");

        assertTrue(result.isEmpty(), "Fork PR must be rejected without dispatching a build");
        verify(ciBuildRunRepository, never()).save(any());
        verifyNoInteractions(evaluationDispatcher);
    }

    @Test
    void ingest_acceptsSameRepoPullRequestAndDispatchesBuild() {
        when(verifier.isValidSignature(anyString(), anyString(), eq(secret))).thenReturn(true);
        when(ciBuildRunRepository.findByDeliveryId("deliv-pr-2")).thenReturn(Optional.empty());

        String validPrPayload = """
                {
                  "action": "opened",
                  "pull_request": {
                    "number": 43,
                    "head": {
                      "sha": "1234567890123456789012345678901234567890",
                      "ref": "feature/chunking-opt",
                      "repo": { "full_name": "GodXSpell/SentinentRAG" }
                    },
                    "base": {
                      "sha": "0987654321098765432109876543210987654321",
                      "ref": "main",
                      "repo": { "full_name": "GodXSpell/SentinentRAG" }
                    }
                  }
                }
                """;

        when(ciBuildRunRepository.save(any(CiBuildRun.class))).thenAnswer(i -> {
            CiBuildRun r = i.getArgument(0);
            r.setBuildId(UUID.randomUUID());
            return r;
        });

        Optional<CiBuildRun> result = service.ingest(validPrPayload, "sha256=valid", "pull_request", "deliv-pr-2");

        assertTrue(result.isPresent());
        ArgumentCaptor<CiBuildRun> captor = ArgumentCaptor.forClass(CiBuildRun.class);
        verify(ciBuildRunRepository).save(captor.capture());

        CiBuildRun saved = captor.getValue();
        assertEquals("1234567890123456789012345678901234567890", saved.getCommitSha());
        assertEquals("feature/chunking-opt", saved.getBranchName());
        assertEquals(43, saved.getPullRequestId());
        assertEquals(BuildStatus.RUNNING, saved.getStatus());
        assertEquals("deliv-pr-2", saved.getDeliveryId());

        verify(evaluationDispatcher).dispatchEvaluation(eq(saved), eq(UUID.fromString(defaultSuiteId)));
    }
}


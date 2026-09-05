package com.sentinel.rag.github;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Verifies GitHub webhook signatures per GitHub's documented scheme: GitHub computes
 * HMAC-SHA256 over the exact raw request body bytes using your webhook secret, then
 * sends it as "X-Hub-Signature-256: sha256=<hex digest>". Verification recomputes the
 * same HMAC independently and checks it matches.
 *
 * Two details that are easy to get subtly wrong, both handled here:
 *
 * 1. MUST hash the RAW body bytes, exactly as GitHub sent them — not a re-serialized
 *    version of a parsed object. Any whitespace/field-order difference between the raw
 *    bytes and a re-serialized JSON object changes the hash and breaks verification.
 *    This is why the controller passes the raw request body String straight through,
 *    never a deserialized DTO.
 *
 * 2. MUST use a constant-time comparison for the final signature check, not String.equals()
 *    or Arrays.equals(). A naive comparison exits as soon as it finds the first mismatched
 *    byte, which leaks timing information an attacker can use to guess the correct
 *    signature one byte at a time (a timing side-channel attack). MessageDigest.isEqual()
 *    is specifically designed to take the same amount of time regardless of where the
 *    first difference occurs.
 */
@Component
public class GitHubWebhookVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    /**
     * @param rawPayload      the exact raw request body, byte-for-byte as received
     * @param signatureHeader the full value of X-Hub-Signature-256, e.g. "sha256=abcd..."
     * @param webhookSecret   the shared secret configured in both GitHub and this service
     * @return true if the signature is valid, false otherwise (never throws for a bad
     *         signature — a malformed/missing header is just an invalid signature, not
     *         an exceptional condition)
     */
    public boolean isValidSignature(String rawPayload, String signatureHeader, String webhookSecret) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }

        String providedHex = signatureHeader.substring(SIGNATURE_PREFIX.length());
        String computedHex = computeHmacHex(rawPayload, webhookSecret);

        // Constant-time comparison — see class javadoc for why this matters over
        // String.equals(), which short-circuits on the first mismatched character.
        return constantTimeEquals(providedHex, computedHex);
    }

    private String computeHmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is a mandatory JDK algorithm; InvalidKeyException here would mean
            // an empty/malformed secret, which is a startup config problem, not something
            // to recover from mid-request.
            throw new IllegalStateException("Unable to compute HMAC-SHA256", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
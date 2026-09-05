package com.sentinel.rag.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deliberately minimal: GitHub's actual webhook payloads for push/pull_request events
 * carry dozens of fields (sender info, full repo metadata, etc). This DTO only declares
 * the handful WebhookIngestionService actually reads. @JsonIgnoreProperties(ignoreUnknown
 * = true) tells Jackson to silently skip every field we didn't declare, rather than
 * failing deserialization the moment GitHub's payload includes something we didn't model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubWebhookPayload {

    // Present on push events: full ref like "refs/heads/main"
    @JsonProperty("ref")
    private String ref;

    // Present on push events: the SHA of the HEAD commit after the push
    @JsonProperty("after")
    private String afterCommitSha;

    // Present on pull_request events: "opened", "closed", "synchronize", etc.
    @JsonProperty("action")
    private String action;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PullRequest {
        @JsonProperty("number")
        private Integer number;

        @JsonProperty("head")
        private CommitRef head;

        public Integer getNumber() {
            return number;
        }

        public void setNumber(Integer number) {
            this.number = number;
        }

        public CommitRef getHead() {
            return head;
        }

        public void setHead(CommitRef head) {
            this.head = head;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommitRef {
        @JsonProperty("sha")
        private String sha;

        @JsonProperty("ref")
        private String ref;

        public String getSha() {
            return sha;
        }

        public void setSha(String sha) {
            this.sha = sha;
        }

        public String getRef() {
            return ref;
        }

        public void setRef(String ref) {
            this.ref = ref;
        }
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getAfterCommitSha() {
        return afterCommitSha;
    }

    public void setAfterCommitSha(String afterCommitSha) {
        this.afterCommitSha = afterCommitSha;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public PullRequest getPullRequest() {
        return pullRequest;
    }

    public void setPullRequest(PullRequest pullRequest) {
        this.pullRequest = pullRequest;
    }
}
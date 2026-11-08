package com.sentinel.rag.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deliberately minimal: GitHub's actual webhook payloads for push/pull_request events
 * carry dozens of fields. This DTO declares the fields WebhookIngestionService reads,
 * including fork PR detection and repository metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubWebhookPayload {

    @JsonProperty("ref")
    private String ref;

    @JsonProperty("after")
    private String afterCommitSha;

    @JsonProperty("action")
    private String action;

    @JsonProperty("repository")
    private Repository repository;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        @JsonProperty("full_name")
        private String fullName;

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PullRequest {
        @JsonProperty("number")
        private Integer number;

        @JsonProperty("head")
        private CommitRef head;

        @JsonProperty("base")
        private CommitRef base;

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

        public CommitRef getBase() {
            return base;
        }

        public void setBase(CommitRef base) {
            this.base = base;
        }

        public boolean isFork() {
            if (head == null || base == null) return false;
            if (head.getRepo() == null || base.getRepo() == null) return false;
            String headRepo = head.getRepo().getFullName();
            String baseRepo = base.getRepo().getFullName();
            return headRepo != null && !headRepo.equalsIgnoreCase(baseRepo);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommitRef {
        @JsonProperty("sha")
        private String sha;

        @JsonProperty("ref")
        private String ref;

        @JsonProperty("repo")
        private Repository repo;

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

        public Repository getRepo() {
            return repo;
        }

        public void setRepo(Repository repo) {
            this.repo = repo;
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

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public PullRequest getPullRequest() {
        return pullRequest;
    }

    public void setPullRequest(PullRequest pullRequest) {
        this.pullRequest = pullRequest;
    }
}

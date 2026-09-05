package com.sentinel.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ci_build_runs")
@Getter
@Setter
@NoArgsConstructor
public class CiBuildRun {

    @Id
    @Generated(event = EventType.INSERT)
    @Column(name = "build_id", updatable = false, nullable = false, insertable = false)
    private UUID buildId;

    @Column(name = "commit_sha", length = 40, nullable = false)
    private String commitSha;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(name = "pull_request_id")
    private Integer pullRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BuildStatus status;

    @Column(name = "mean_faithfulness")
    private Float meanFaithfulness;

    @Column(name = "mean_precision")
    private Float meanPrecision;

    @Column(name = "cache_hit_rate")
    private Float cacheHitRate;

    @Column(name = "execution_duration_ms")
    private Long executionDurationMs;

    @Column(name = "created_at", updatable = false, insertable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}
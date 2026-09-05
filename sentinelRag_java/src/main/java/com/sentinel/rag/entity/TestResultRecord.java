package com.sentinel.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "test_result_records")
@Getter
@Setter
@NoArgsConstructor
public class TestResultRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "build_id", nullable = false)
    private CiBuildRun buildRun;

    @Column(name = "test_id", nullable = false)
    private UUID testId;

    @Column(name = "faithfulness_score", nullable = false)
    private Float faithfulnessScore;

    @Column(name = "context_precision_score", nullable = false)
    private Float contextPrecisionScore;

    @Column(name = "answer_relevancy_score", nullable = false)
    private Float answerRelevancyScore;

    @Column(name = "execution_time_ms", nullable = false)
    private Long executionTimeMs;

    @Column(name = "was_cached", nullable = false)
    @ColumnDefault("false")
    private Boolean wasCached;

    @Column(name = "generated_answer", columnDefinition = "TEXT", nullable = false)
    private String generatedAnswer;

    @Column(name = "created_at", updatable = false, insertable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}
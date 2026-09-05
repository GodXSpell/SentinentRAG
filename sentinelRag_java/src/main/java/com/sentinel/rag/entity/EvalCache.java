package com.sentinelRAG.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;

@Entity
@Table(name = "eval_cache")
@NoArgsConstructor
public class EvalCache {

    @Id
    @Column(name = "cache_hash", length = 64, nullable = false)
    private String cacheHash;

    @Column(name = "generated_answer", columnDefinition = "TEXT")
    private String generatedAnswer;

    @Column(name = "faithfulness_score")
    private Float faithfulnessScore;

    @Column(name = "context_precision_score")
    private Float contextPrecisionScore;

    @Column(name = "answer_relevancy_score")
    private Float answerRelevancyScore;

    @Column(name = "evaluator_model", nullable = false)
    private String evaluatorModel;

    @Column(name = "created_at", updatable = false, insertable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}

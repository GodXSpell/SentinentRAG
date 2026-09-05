package com.sentinel.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;

@Entity
@Table(name = "eval_cache")
@Getter
@Setter
@NoArgsConstructor
public class EvalCache {

    @Id
    @Column(name = "cache_hash", length = 64, nullable = false)
    private String cacheHash;

    @Column(name = "generated_answer", columnDefinition = "TEXT")
    private String generatedAnswer;

    @Column(name = "faithfulness_score", nullable = false)
    private Float faithfulnessScore;

    @Column(name = "context_precision_score", nullable = false)
    private Float contextPrecisionScore;

    @Column(name = "answer_relevancy_score", nullable = false)
    private Float answerRelevancyScore;

    @Column(name = "evaluator_model", nullable = false)
    private String evaluatorModel;

    @Column(name = "created_at", updatable = false, insertable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}

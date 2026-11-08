package com.sentinel.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "test_cases")
@Getter
@Setter
@NoArgsConstructor
public class TestCase implements Persistable<UUID> {

    @Id
    @Column(name = "test_id", updatable = false, nullable = false)
    private UUID testId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suite_id")
    private TestSuite suite;

    @Column(name = "query", columnDefinition = "TEXT", nullable = false)
    private String query;

    @Column(name = "expected_ground_truth", columnDefinition = "TEXT", nullable = false)
    private String expectedGroundTruth;

    @Column(name = "expects_refusal", nullable = false)
    @ColumnDefault("false")
    private boolean expectsRefusal;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "varchar(255)[]")
    private List<String> tags;

    @Column(name = "created_at", updatable = false, insertable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return testId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}

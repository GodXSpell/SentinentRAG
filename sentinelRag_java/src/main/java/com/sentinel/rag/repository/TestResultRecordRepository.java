package com.sentinel.rag.repository;

import com.sentinel.rag.entity.TestResultRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestResultRecordRepository extends JpaRepository<TestResultRecord, Long> {
    List<TestResultRecord> findByBuildRun_BuildId(UUID buildId);
}
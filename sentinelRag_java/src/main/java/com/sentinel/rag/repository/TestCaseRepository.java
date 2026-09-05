package com.sentinel.rag.repository;

import com.sentinel.rag.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface Testcaserepository extends JpaRepository<TestCase, UUID> {
    List<TestCase> findBySuite_SuiteId(UUID suiteId);
}

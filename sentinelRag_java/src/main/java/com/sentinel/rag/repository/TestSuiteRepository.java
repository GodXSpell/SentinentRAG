package com.sentinel.rag.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.sentinel.rag.entity.TestSuite;
import java.util.UUID;

public interface testsuiterepository extends JpaRepository<TestSuite, UUID> {
}

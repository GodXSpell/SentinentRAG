package com.sentinel.rag.repository;

import com.sentinel.rag.entity.EvalCache;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalCacheRepository extends JpaRepository<EvalCache, String> {
}

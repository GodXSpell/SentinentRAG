package com.sentinel.rag.repository;

import com.sentinel.rag.entity.EvalCache;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Evalcacherepository extends JpaRepository<EvalCache, String> {
}

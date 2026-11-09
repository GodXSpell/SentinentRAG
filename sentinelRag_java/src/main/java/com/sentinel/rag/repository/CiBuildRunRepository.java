package com.sentinel.rag.repository;

import com.sentinel.rag.entity.BuildStatus;
import com.sentinel.rag.entity.CiBuildRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CiBuildRunRepository extends JpaRepository<CiBuildRun, UUID> {
    List<CiBuildRun> findByPullRequestIdOrderByCreatedAtDesc(Integer pullRequestId);
    Optional<CiBuildRun> findTopByBranchNameAndStatusOrderByCreatedAtDesc(
            String branchName, BuildStatus status);
    Optional<CiBuildRun> findFirstByBranchNameAndPullRequestIdIsNotNullOrderByCreatedAtDesc(String branchName);
    Optional<CiBuildRun> findByDeliveryId(String deliveryId);
    List<CiBuildRun> findByStatus(BuildStatus status);
}
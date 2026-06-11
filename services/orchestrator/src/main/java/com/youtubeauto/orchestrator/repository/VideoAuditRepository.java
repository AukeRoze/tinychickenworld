package com.youtubeauto.orchestrator.repository;

import com.youtubeauto.orchestrator.domain.VideoAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoAuditRepository extends JpaRepository<VideoAudit, UUID> {
    /** Latest audit for a job (audits are kept as history now, not overwritten). */
    Optional<VideoAudit> findTopByVideoJobIdOrderByCreatedAtDesc(UUID videoJobId);
    /** Full audit history, oldest-first (e.g. 78 → 84 → 88 over Auto-Fix passes). */
    List<VideoAudit> findByVideoJobIdOrderByCreatedAtAsc(UUID videoJobId);
}

package com.youtubeauto.orchestrator.repository;

import com.youtubeauto.orchestrator.domain.QcFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QcFindingRepository extends JpaRepository<QcFinding, UUID> {
    List<QcFinding> findTop2000ByOrderByCreatedAtDesc();
    List<QcFinding> findByVideoJobId(UUID videoJobId);
}

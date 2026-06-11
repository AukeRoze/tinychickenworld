package com.youtubeauto.orchestrator.repository;

import com.youtubeauto.orchestrator.domain.VideoLocalization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoLocalizationRepository extends JpaRepository<VideoLocalization, UUID> {
    List<VideoLocalization> findByVideoJobId(UUID jobId);
    Optional<VideoLocalization> findByVideoJobIdAndLanguageCode(UUID jobId, String lang);
}

package com.youtubeauto.orchestrator.repository;

import com.youtubeauto.orchestrator.domain.VideoAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoAnalyticsRepository extends JpaRepository<VideoAnalytics, UUID> {

    /** Most recent snapshot for a given video. Used for the dashboard "current state". */
    @Query("SELECT a FROM VideoAnalytics a WHERE a.videoJobId = :jobId "
            + "ORDER BY a.fetchedAt DESC LIMIT 1")
    Optional<VideoAnalytics> findMostRecent(@Param("jobId") UUID jobId);

    /** All snapshots in the last N days — used by the aggregator. */
    @Query("SELECT a FROM VideoAnalytics a WHERE a.fetchedAt >= :since "
            + "ORDER BY a.fetchedAt DESC")
    List<VideoAnalytics> findRecent(@Param("since") OffsetDateTime since);

    /** Latest snapshot per video (de-duped). Keyed on MAX(fetchedAt), not
     *  MAX(id): ids are random UUIDs, so "highest id" is NOT "newest row" —
     *  that version returned an arbitrary snapshot per video. */
    @Query("SELECT a FROM VideoAnalytics a WHERE a.fetchedAt = "
            + "(SELECT MAX(b.fetchedAt) FROM VideoAnalytics b WHERE b.videoJobId = a.videoJobId)")
    List<VideoAnalytics> findLatestPerVideo();
}

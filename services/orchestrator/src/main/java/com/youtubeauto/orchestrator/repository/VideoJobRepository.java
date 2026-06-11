package com.youtubeauto.orchestrator.repository;

import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoJobRepository extends JpaRepository<VideoJob, UUID> {

    /** P4 crash recovery — find jobs left in a given set of statuses (e.g. the
     *  in-flight, non-terminal ones that were mid-pipeline at last shutdown). */
    List<VideoJob> findByStatusIn(List<JobStatus> statuses);
}

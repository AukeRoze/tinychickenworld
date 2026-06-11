package com.youtubeauto.script.repository;

import com.youtubeauto.script.domain.Script;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScriptRepository extends JpaRepository<Script, UUID> {
    Optional<Script> findByJobId(UUID jobId);
}

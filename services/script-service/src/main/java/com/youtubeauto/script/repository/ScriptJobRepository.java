package com.youtubeauto.script.repository;

import com.youtubeauto.script.domain.ScriptJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ScriptJobRepository extends JpaRepository<ScriptJob, UUID> {}

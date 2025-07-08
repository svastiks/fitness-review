package com.fitanalysis.server.repository;

import com.fitanalysis.server.models.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {
    Optional<AnalysisResult> findByVideoId(String videoId);
    boolean existsByVideoId(String videoId);
} 
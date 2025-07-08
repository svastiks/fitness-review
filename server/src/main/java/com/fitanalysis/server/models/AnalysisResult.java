package com.fitanalysis.server.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_result")
public class AnalysisResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", unique = true)
    private String videoId;

    @Column(name = "video_title")
    private String videoTitle;

    @Column(name = "analysis_json", columnDefinition = "TEXT")
    private String analysisJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public AnalysisResult() {}

    public AnalysisResult(String videoId, String videoTitle, String analysisJson) {
        this.videoId = videoId;
        this.videoTitle = videoTitle;
        this.analysisJson = analysisJson;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getVideoTitle() { return videoTitle; }
    public void setVideoTitle(String videoTitle) { this.videoTitle = videoTitle; }
    public String getAnalysisJson() { return analysisJson; }
    public void setAnalysisJson(String analysisJson) { this.analysisJson = analysisJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
} 
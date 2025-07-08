package com.fitanalysis.server.services;

import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class YtDlpService {
    
    public Map<String, Object> extractVideoInfo(String videoUrl) throws IOException, InterruptedException {
        System.out.println("Starting video info extraction for: " + videoUrl);
        
        // First, extract basic video info without JSON dump
        Map<String, Object> videoInfo = extractBasicInfo(videoUrl);
        
        // Then extract transcript
        String transcript = extractVttTranscript(videoUrl);
        videoInfo.put("transcript", transcript);
        
        System.out.println("Video info extraction completed");
        return videoInfo;
    }
    
    private Map<String, Object> extractBasicInfo(String videoUrl) throws IOException, InterruptedException {
        Map<String, Object> result = new HashMap<>();
        
        // Get title using --get-title
        ProcessBuilder titlePb = new ProcessBuilder("yt-dlp", "--get-title", videoUrl);
        Process titleProcess = titlePb.start();
        boolean titleCompleted = titleProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        
        if (titleCompleted && titleProcess.exitValue() == 0) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(titleProcess.getInputStream()));
            String title = reader.readLine();
            if (title != null) {
                result.put("title", title);
                System.out.println("Extracted title: " + title);
            }
        }
        
        // Get uploader using --get-uploader
        ProcessBuilder uploaderPb = new ProcessBuilder("yt-dlp", "--get-uploader", videoUrl);
        Process uploaderProcess = uploaderPb.start();
        boolean uploaderCompleted = uploaderProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        
        if (uploaderCompleted && uploaderProcess.exitValue() == 0) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(uploaderProcess.getInputStream()));
            String uploader = reader.readLine();
            if (uploader != null) {
                result.put("channel", uploader);
                System.out.println("Extracted uploader: " + uploader);
            }
        }
        
        return result;
    }
    
    private String extractVttTranscript(String videoUrl) throws IOException, InterruptedException {
        System.out.println("Extracting transcript for: " + videoUrl);
        
        // Extract video ID from URL
        String videoId = extractVideoId(videoUrl);
        System.out.println("Video ID: " + videoId);
        
        // Download transcript using yt-dlp
        ProcessBuilder pb = new ProcessBuilder(
            "yt-dlp", 
            "--write-auto-sub", 
            "--sub-format", "vtt",
            "--skip-download",
            "--output", videoId + ".%(ext)s",
            videoUrl
        );
        
        System.out.println("Running yt-dlp command for transcript...");
        Process process = pb.start();
        boolean completed = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        
        if (!completed) {
            process.destroyForcibly();
            System.err.println("yt-dlp timed out after 60 seconds");
            return "";
        }
        
        int exitCode = process.exitValue();
        System.out.println("yt-dlp exit code: " + exitCode);
        
        if (exitCode != 0) {
            // Read error output
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder errorOutput = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            System.err.println("yt-dlp error output: " + errorOutput.toString());
            return "";
        }
        
        // Look for VTT file
        String vttFile = videoId + ".en.vtt";
        System.out.println("Looking for VTT file: " + vttFile);
        
        // Read and clean VTT content
        String transcript = cleanVttContent(vttFile);
        System.out.println("Transcript length: " + transcript.length());
        
        return transcript;
    }
    
    private String extractVideoId(String videoUrl) {
        Pattern pattern = Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]+)");
        Matcher matcher = pattern.matcher(videoUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Invalid YouTube URL: " + videoUrl);
    }
    
    private String cleanVttContent(String vttFile) throws IOException {
        System.out.println("Reading VTT file: " + vttFile);
        
        ProcessBuilder pb = new ProcessBuilder("cat", vttFile);
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        
        StringBuilder cleaned = new StringBuilder();
        String line;
        
        while ((line = reader.readLine()) != null) {
            // Skip VTT header, timestamps, and empty lines
            if (line.startsWith("WEBVTT") || 
                line.matches("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} --> \\d{2}:\\d{2}:\\d{2}\\.\\d{3}$") || 
                line.trim().isEmpty()) {
                continue;
            }
            
            // Skip cue identifiers (numbers)
            if (line.matches("^\\d+$")) {
                continue;
            }
            
            cleaned.append(line).append(" ");
        }
        
        String result = cleaned.toString().trim();
        System.out.println("Cleaned transcript: " + result.substring(0, Math.min(100, result.length())) + "...");
        return result;
    }
} 
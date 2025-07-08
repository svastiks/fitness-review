package com.fitanalysis.server.services;

import com.fitanalysis.server.models.KnowledgeChunk;
import com.fitanalysis.server.models.SourceType;
import com.fitanalysis.server.repository.KnowledgeChunkRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import com.fitanalysis.server.models.AnalysisResult;
import com.fitanalysis.server.repository.AnalysisResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AnalysisOrchestrationService {
    
    @Autowired
    private YtDlpService ytDlpService;
    
    @Autowired
    private EmbeddingService embeddingService;
    
    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;
    
    @Autowired
    private AnalysisResultRepository analysisResultRepository;
    
    @Value("${GOOGLE_API_KEY}")
    private String apiKey;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public Map<String, Object> runFullAnalysis(String videoUrl, String papersDirectory) {
        try {
            // A. Ingest Papers
            ingestPapers(papersDirectory);
            
            // B. Ingest Video
            ingestVideo(videoUrl);
            
            // C. Check for existing analysis by videoId
            String videoId = extractVideoId(videoUrl);
            if (analysisResultRepository.existsByVideoId(videoId)) {
                System.out.println("Analysis for video " + videoId + " already exists, skipping analysis.");
                // Optionally, fetch and return the existing analysis result
                AnalysisResult existing = analysisResultRepository.findByVideoId(videoId).get();
                // Parse JSON to Map for return
                return objectMapper.readValue(existing.getAnalysisJson(), Map.class);
            }
            
            // D. Perform RAG & LLM Analysis
            Map<String, Object> analysis = performRagAnalysis(videoUrl);
            
            // Persist the new analysis result
            String videoTitle = (String) analysis.getOrDefault("videoTitle", "Unknown Video");
            String analysisJson = objectMapper.writeValueAsString(analysis);
            AnalysisResult result = new AnalysisResult(videoId, videoTitle, analysisJson);
            analysisResultRepository.save(result);
            
            return analysis;
            
        } catch (Exception e) {
            throw new RuntimeException("Analysis failed: " + e.getMessage(), e);
        }
    }
    
    private void ingestPapers(String papersDirectory) throws IOException {
        System.out.println("Starting paper ingestion...");
        File dir = new File(papersDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Papers directory not found: " + papersDirectory);
            return;
        }
        
        File[] pdfFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        if (pdfFiles == null || pdfFiles.length == 0) {
            System.out.println("No PDF files found in: " + papersDirectory);
            return;
        }
        
        System.out.println("Found " + pdfFiles.length + " PDF files to process");
        
        for (File pdfFile : pdfFiles) {
            ingestPdf(pdfFile.getPath());
        }
    }
    
    private void ingestVideo(String videoUrl) throws IOException, InterruptedException {
        String videoId = extractVideoId(videoUrl);
        if (knowledgeChunkRepository.existsBySourceId(videoId)) {
            System.out.println("Chunks for video " + videoId + " already exist, skipping ingestion.");
            return;
        }
        System.out.println("[DEBUG] Ingestion NOT skipped for video: " + videoId + ". Proceeding with ingestion.");
        System.out.println("Starting video ingestion...");
        Map<String, Object> videoInfo;
        String transcript;
        try {
            videoInfo = ytDlpService.extractVideoInfo(videoUrl);
            transcript = (String) videoInfo.get("transcript");
            if (transcript == null || transcript.trim().isEmpty()) {
                System.out.println("No transcript found for video: " + videoUrl);
                return;
            }
            System.out.println("Video transcript length: " + transcript.length() + " characters");
        } catch (Exception e) {
            System.out.println("Error processing video: " + e.getMessage());
            return;
        }
        List<String> chunks = chunkText(transcript, 1000);
        System.out.println("Created " + chunks.size() + " chunks");
        String videoTitle = (String) videoInfo.get("title");
        for (int i = 0; i < chunks.size(); i++) {
            float[] embedding = embeddingService.getEmbedding(chunks.get(i));
            String embeddingStr = floatArrayToString(embedding);
            KnowledgeChunk chunk = new KnowledgeChunk(
                chunks.get(i),
                embeddingStr,
                videoId,
                SourceType.VIDEO,
                "{\"video_title\":\"" + videoTitle + "\",\"chunk_index\":" + i + "}"
            );
            knowledgeChunkRepository.save(chunk);
        }
        System.out.println("Saved " + chunks.size() + " video chunks to database");
    }
    
    private void ingestPdf(String pdfPath) throws IOException {
        String sourceId = pdfPath;
        if (knowledgeChunkRepository.existsBySourceId(sourceId)) {
            System.out.println("Chunks for PDF " + sourceId + " already exist, skipping ingestion.");
            return;
        }
        System.out.println("[DEBUG] Ingestion NOT skipped for PDF: " + sourceId + ". Proceeding with ingestion.");
        System.out.println("Processing PDF: " + pdfPath);
        String content = "Sample PDF content from " + pdfPath;
        List<String> chunks = chunkText(content, 1000);
        System.out.println("Created " + chunks.size() + " chunks from PDF");
        for (int i = 0; i < chunks.size(); i++) {
            float[] embedding = embeddingService.getEmbedding(chunks.get(i));
            String embeddingStr = floatArrayToString(embedding);
            KnowledgeChunk chunk = new KnowledgeChunk(
                chunks.get(i),
                embeddingStr,
                pdfPath,
                SourceType.RESEARCH_PAPER,
                "{\"chunk_index\": " + i + "}"
            );
            knowledgeChunkRepository.save(chunk);
        }
        System.out.println("Saved " + chunks.size() + " PDF chunks to database");
    }
    
    private Map<String, Object> performRagAnalysis(String videoUrl) {
        Map<String, Object> result = new HashMap<>();
        // Get video title
        String videoTitle = "Unknown Video";
        try {
            Map<String, Object> videoInfo = ytDlpService.extractVideoInfo(videoUrl);
            videoTitle = (String) videoInfo.get("title");
        } catch (Exception e) {
            System.out.println("Could not get video title: " + e.getMessage());
        }
        result.put("videoTitle", videoTitle);
        // Define analytical sub-queries
        String[] queries = {
            "Find good points and positive aspects mentioned in the content",
            "Find bad points, criticisms, or negative aspects mentioned in the content", 
            "Extract workout plan, exercises, sets, and reps mentioned",
            "Write a conclusion summarizing the overall assessment",
            "How well is the workout supported by research?"
        };
        Map<String, Object> analysis = new HashMap<>();
        for (String query : queries) {
            Map<String, Object> geminiResult = analyzeQuery(query, videoTitle);
            if (query.contains("good points")) {
                analysis.put("good_points", geminiResult.getOrDefault("good_points", new ArrayList<>()));
            } else if (query.contains("bad points")) {
                analysis.put("bad_points", geminiResult.getOrDefault("bad_points", new ArrayList<>()));
            } else if (query.contains("workout plan")) {
                analysis.put("workout_plan", geminiResult.getOrDefault("actual_workout", ""));
            } else if (query.contains("conclusion")) {
                analysis.put("conclusion", geminiResult.getOrDefault("conclusion", ""));
            } else if (query.contains("supported by research")) {
                analysis.put("scientific_backing", geminiResult.getOrDefault("scientific_backing", ""));
            }
        }
        result.put("analysis", analysis);
        return result;
    }

    private Map<String, Object> analyzeQuery(String query, String videoTitle) {
        float[] queryEmbedding = embeddingService.getEmbedding(query);
        List<KnowledgeChunk> allChunks = knowledgeChunkRepository.findAll();
        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (KnowledgeChunk chunk : allChunks) {
            float[] chunkEmbedding = stringToFloatArray(chunk.getEmbedding());
            double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
            scoredChunks.add(new ScoredChunk(chunk, similarity));
        }
        scoredChunks.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        List<KnowledgeChunk> relevantChunks = new ArrayList<>();
        for (int i = 0; i < Math.min(10, scoredChunks.size()); i++) {
            relevantChunks.add(scoredChunks.get(i).chunk);
        }
        StringBuilder context = new StringBuilder();
        for (KnowledgeChunk chunk : relevantChunks) {
            context.append("Source: ").append(chunk.getSourceId()).append("\n");
            context.append("Type: ").append(chunk.getSourceType()).append("\n");
            context.append("Content: ").append(chunk.getChunkText()).append("\n\n");
        }
        String prompt = String.format(
            "Based on the following context from research papers and video transcripts, analyze the fitness video:\n\n" +
            "Context:\n%s\n\n" +
            "Please provide a structured analysis in JSON format with the following fields:\n" +
            "- video_title: The title of the video\n" +
            "- channel: The channel name\n" +
            "- good_points: Array of positive aspects of the workout\n" +
            "- bad_points: Array of potential issues or concerns\n" +
            "- conclusion: Overall assessment\n" +
            "- actual_workout: Description of the actual exercises and routine\n" +
            "- scientific_backing: How well the workout is supported by research\n\n" +
            "Query: %s",
            context.toString(), query
        );
        String geminiResponse = embeddingService.generateText(prompt);
        String cleanJson = extractJsonFromGeminiResponse(geminiResponse);
        System.out.println("\n===== FINAL ANALYSIS JSON =====\n" + cleanJson + "\n===============================\n");
        try {
            return objectMapper.readValue(cleanJson, Map.class);
        } catch (Exception e) {
            System.out.println("Warning: Could not parse Gemini response as JSON. Returning empty map.");
            return new HashMap<>();
        }
    }
    
    private static class ScoredChunk {
        KnowledgeChunk chunk;
        double similarity;
        ScoredChunk(KnowledgeChunk chunk, double similarity) {
            this.chunk = chunk;
            this.similarity = similarity;
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }
    
    private String callGeminiApi(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> content = new HashMap<>();
        content.put("parts", Arrays.asList(Map.of("text", prompt)));
        requestBody.put("contents", Arrays.asList(content));
        
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(GEMINI_URL + apiKey, request, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Map<String, Object> candidate = candidates.get(0);
                    Map<String, Object> content2 = (Map<String, Object>) candidate.get("content");
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content2.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
            
            return "Analysis failed";
            
        } catch (Exception e) {
            return "Error in analysis: " + e.getMessage();
        }
    }
    
    private List<String> chunkText(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("[.!?]+");
        
        StringBuilder currentChunk = new StringBuilder();
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;
            
            if (currentChunk.length() + sentence.length() > maxChunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
            }
            
            currentChunk.append(sentence).append(". ");
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }
        
        return chunks;
    }
    
    private String extractTextFromPdf(String pdfPath) {
        // Simplified PDF text extraction - in production use Apache PDFBox or similar
        try {
            // For now, return a placeholder - you'll need to implement proper PDF parsing
            return "Sample text from PDF: " + new File(pdfPath).getName();
        } catch (Exception e) {
            return "Error extracting text from PDF: " + e.getMessage();
        }
    }
    
    public String extractVideoId(String videoUrl) {
        Pattern pattern = Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]+)");
        Matcher matcher = pattern.matcher(videoUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Invalid YouTube URL: " + videoUrl);
    }
    
    private List<Map<String, String>> parsePoints(String analysis) {
        // Simplified parsing - in production use more sophisticated parsing
        List<Map<String, String>> points = new ArrayList<>();
        String[] lines = analysis.split("\n");
        
        for (String line : lines) {
            if (line.trim().startsWith("-") || line.trim().startsWith("•")) {
                Map<String, String> point = new HashMap<>();
                point.put("point", line.trim().substring(1).trim());
                point.put("evidence_source", "analysis");
                points.add(point);
            }
        }
        
        return points;
    }
    
    private Map<String, Object> parseWorkoutPlan(String analysis) {
        // Simplified parsing - in production use more sophisticated parsing
        Map<String, Object> workoutPlan = new HashMap<>();
        List<Map<String, Object>> exercises = new ArrayList<>();
        
        String[] lines = analysis.split("\n");
        for (String line : lines) {
            if (line.toLowerCase().contains("press") || line.toLowerCase().contains("squat") || 
                line.toLowerCase().contains("deadlift") || line.toLowerCase().contains("curl")) {
                Map<String, Object> exercise = new HashMap<>();
                exercise.put("name", line.trim());
                exercise.put("sets", 3);
                exercise.put("reps", "8-12");
                exercises.add(exercise);
            }
        }
        
        workoutPlan.put("exercises", exercises);
        return workoutPlan;
    }

    private String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            sb.append(arr[i]);
            if (i < arr.length - 1) sb.append(",");
        }
        return sb.toString();
    }

    private float[] stringToFloatArray(String s) {
        String[] parts = s.split(",");
        float[] arr = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Float.parseFloat(parts[i]);
        }
        return arr;
    }

    private String extractJsonFromGeminiResponse(String response) {
        // Remove code block markers if present
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        }
        if (trimmed.startsWith("```") ) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        // Validate JSON
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            System.out.println("Warning: Could not parse Gemini response as JSON. Returning raw string.");
            return trimmed;
        }
    }
} 
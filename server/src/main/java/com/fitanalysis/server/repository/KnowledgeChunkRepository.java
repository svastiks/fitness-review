package com.fitanalysis.server.repository;

import com.fitanalysis.server.models.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    @Query(value = "SELECT * FROM knowledge_chunk ORDER BY embedding <-> CAST(:embedding AS vector) LIMIT :limit", nativeQuery = true)
    List<KnowledgeChunk> findNearestNeighbors(@Param("embedding") String embedding, @Param("limit") int limit);

    boolean existsBySourceId(String sourceId);
} 
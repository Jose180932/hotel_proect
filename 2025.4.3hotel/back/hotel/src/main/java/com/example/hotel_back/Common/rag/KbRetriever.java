package com.example.hotel_back.Common.rag;

import com.example.hotel_back.Pojo.VO.rag.KbChunkHit;
import com.example.hotel_back.mapper.KbMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库检索。
 * retrieval-mode:
 *   keyword —— MySQL ngram 全文检索，失败回退 LIKE 打分（无需任何外部依赖）
 *   vector  —— 向量余弦相似度（需要 embedding 服务）
 *   hybrid  —— 关键词 + 向量合并（各占 0.5 权重）
 *
 * 全文检索分数是无界的，统一归一化到 [0,1] 后再按 min-score 过滤。
 */
@Slf4j
@Component
public class KbRetriever {

    @Value("${hotel.rag.retrieval-mode:keyword}")
    private String mode;

    @Value("${hotel.rag.min-score:0.3}")
    private double minScore;

    @Autowired
    private KbMapper kbMapper;

    @Autowired
    private EmbeddingClient embeddingClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String currentMode() {
        return mode;
    }

    public boolean vectorAvailable() {
        return embeddingClient.available();
    }

    public String embeddingModel() {
        return embeddingClient.modelName();
    }

    public List<KbChunkHit> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * @param modeOverride 临时覆盖检索模式（评测用），null 表示使用配置值
     */
    public List<KbChunkHit> search(String query, int topK, String modeOverride) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        String effectiveMode = modeOverride == null ? mode : modeOverride;
        String normalizedMode = effectiveMode == null ? "keyword" : effectiveMode.trim().toLowerCase();
        List<KbChunkHit> hits = switch (normalizedMode) {
            case "vector" -> vectorSearch(query, topK);
            case "hybrid" -> hybridSearch(query, topK);
            default -> keywordSearch(query, topK);
        };
        return hits.stream()
                .filter(hit -> hit.getContent() != null && !hit.getContent().isBlank())
                .limit(topK)
                .toList();
    }

    // ------------------------------------------------------------------ 关键词

    private List<KbChunkHit> keywordSearch(String query, int topK) {
        List<KbChunkHit> hits = new ArrayList<>();
        try {
            List<KbChunkHit> fulltextHits = kbMapper.searchFulltext(query, Math.max(topK * 3, 10));
            if (fulltextHits != null && !fulltextHits.isEmpty()) {
                hits = new ArrayList<>(fulltextHits);
            }
        } catch (Exception e) {
            log.warn("全文检索不可用，回退 LIKE 匹配：{}", e.getMessage());
        }

        if (hits.isEmpty()) {
            List<String> terms = buildTerms(query);
            if (terms.isEmpty()) {
                return List.of();
            }
            hits = kbMapper.searchLike(terms, Math.max(topK * 10, 30));
            for (KbChunkHit hit : hits) {
                hit.setScore(scoreByTerms(hit.getContent(), terms));
            }
        }

        normalizeScores(hits);
        return hits.stream()
                .filter(hit -> hit.getScore() != null && hit.getScore() >= minScore)
                .sorted(Comparator.comparing(KbChunkHit::getScore).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 分数归一化：最高分记为 1.0，其余按比例缩放，便于阈值过滤与混合加权
     */
    private void normalizeScores(List<KbChunkHit> hits) {
        double max = hits.stream()
                .mapToDouble(hit -> hit.getScore() == null ? 0D : hit.getScore())
                .max()
                .orElse(0D);
        if (max <= 0) {
            return;
        }
        for (KbChunkHit hit : hits) {
            double score = hit.getScore() == null ? 0D : hit.getScore();
            hit.setScore(score / max);
        }
    }

    /**
     * 查询词：按标点切分 + 中文 2-gram 补充，提高 LIKE 召回
     */
    private List<String> buildTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String part : query.split("[\\p{Punct}\\s，。！？；：、（）【】《》]+")) {
            String term = part.trim();
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        String han = query.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
        for (int i = 0; i + 2 <= han.length() && terms.size() < 16; i += 2) {
            terms.add(han.substring(i, i + 2));
        }
        return new ArrayList<>(terms).stream().limit(16).toList();
    }

    private double scoreByTerms(String content, List<String> terms) {
        if (content == null || terms.isEmpty()) {
            return 0D;
        }
        int hit = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    // ------------------------------------------------------------------ 向量

    private List<KbChunkHit> vectorSearch(String query, int topK) {
        if (!embeddingClient.available()) {
            log.warn("retrieval-mode=vector 但 embedding 未配置，降级为关键词检索");
            return keywordSearch(query, topK);
        }
        float[] queryVector;
        try {
            queryVector = embeddingClient.embed(query);
        } catch (Exception e) {
            log.warn("查询向量化失败，降级为关键词检索：{}", e.getMessage());
            return keywordSearch(query, topK);
        }

        List<KbChunkHit> hits = new ArrayList<>();
        for (KbChunkHit chunk : kbMapper.listChunksForVector()) {
            float[] vector = parseVector(chunk.getEmbedding());
            if (vector.length == 0 || vector.length != queryVector.length) {
                continue;
            }
            double score = dot(queryVector, vector);
            if (score < minScore) {
                continue;
            }
            chunk.setScore(score);
            hits.add(chunk);
        }
        hits.sort(Comparator.comparing(KbChunkHit::getScore).reversed());
        return hits.stream().limit(topK).toList();
    }

    // ------------------------------------------------------------------ 混合

    /**
     * 混合检索：向量为主（语义），关键词作为小幅加权（精确词命中）。
     * 注意不能把关键词归一化分数直接五五开——弱命中会被抬到 1.0，
     * 反而压过语义正确的结果（实测"一家三口住什么房"会命中酒店介绍）。
     */
    private List<KbChunkHit> hybridSearch(String query, int topK) {
        List<KbChunkHit> keyword = keywordSearch(query, Math.max(topK * 2, 6));
        if (!embeddingClient.available()) {
            return keyword;
        }
        List<KbChunkHit> vector = vectorSearch(query, Math.max(topK * 2, 6));
        if (vector.isEmpty()) {
            return keyword;
        }

        Map<String, KbChunkHit> merged = new LinkedHashMap<>();
        for (KbChunkHit hit : vector) {
            hit.setScore(hit.getScore() == null ? 0D : hit.getScore());
            merged.put(keyOf(hit), hit);
        }
        for (KbChunkHit hit : keyword) {
            double bonus = 0.15 * (hit.getScore() == null ? 0D : hit.getScore());
            KbChunkHit existing = merged.get(keyOf(hit));
            if (existing != null) {
                existing.setScore(existing.getScore() + bonus);
            } else if (bonus >= 0.05) {
                hit.setScore(bonus);
                merged.put(keyOf(hit), hit);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(KbChunkHit::getScore).reversed())
                .limit(topK)
                .toList();
    }

    private String keyOf(KbChunkHit hit) {
        return hit.getDocId() + ":" + hit.getSeq();
    }

    private float[] parseVector(String json) {
        if (json == null || json.isBlank()) {
            return new float[0];
        }
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            return new float[0];
        }
    }

    private double dot(float[] a, float[] b) {
        double sum = 0D;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}

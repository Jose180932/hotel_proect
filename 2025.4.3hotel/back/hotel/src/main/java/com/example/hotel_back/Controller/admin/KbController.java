package com.example.hotel_back.Controller.admin;

import com.example.hotel_back.Common.rag.KbRetriever;
import com.example.hotel_back.Common.result.Result;
import com.example.hotel_back.Pojo.Entity.KbDocument;
import com.example.hotel_back.Pojo.VO.rag.KbChunkHit;
import com.example.hotel_back.Service.KbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理（管理端，走 /admin/** 的登录拦截）
 */
@Slf4j
@RestController
@RequestMapping("/admin/kb")
public class KbController {

    @Autowired
    private KbService kbService;

    @Autowired
    private KbRetriever kbRetriever;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(kbService.stats());
    }

    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuild() {
        log.info("重建知识库索引");
        return Result.success(kbService.rebuild());
    }

    /**
     * 文档列表
     */
    @GetMapping("/documents")
    public Result<List<KbDocument>> documents() {
        return Result.success(kbService.list());
    }

    /**
     * 上传文档（.md / .txt / .docx），自动切块、向量化
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            return Result.success(kbService.upload(file));
        } catch (IllegalStateException e) {
            log.warn("知识库上传失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除文档及其切片
     */
    @DeleteMapping("/documents/{id}")
    public Result<String> delete(@PathVariable("id") Long id) {
        try {
            kbService.delete(id);
            return Result.success("删除成功");
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 检索调试：GET /admin/kb/search?q=押金&topK=3&mode=keyword|vector|hybrid
     */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam("q") String query,
                                                    @RequestParam(value = "topK", defaultValue = "3") int topK,
                                                    @RequestParam(value = "mode", required = false) String mode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KbChunkHit hit : kbRetriever.search(query, topK, mode)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docTitle", hit.getDocTitle());
            row.put("seq", hit.getSeq());
            row.put("score", hit.getScore());
            String content = hit.getContent() == null ? "" : hit.getContent().replaceAll("\\s+", " ").trim();
            row.put("snippet", content.length() <= 120 ? content : content.substring(0, 120) + "…");
            rows.add(row);
        }
        return Result.success(rows);
    }

    /**
     * 检索评测：固定问题集，对比 keyword / vector / hybrid 的 Recall@3
     */
    @PostMapping("/eval")
    public Result<Map<String, Object>> eval() {
        List<String[]> cases = List.of(
                new String[]{"取消订单会扣钱吗", "取消与退款政策"},
                new String[]{"临时有事去不了，提前一天退订会损失钱吗", "取消与退款政策"},
                new String[]{"住到一半想走，剩下的钱能退吗", "取消与退款政策"},
                new String[]{"押金要交多少钱", "入住与退房规则"},
                new String[]{"半夜到店还能办入住吗", "入住与退房规则"},
                new String[]{"可以带宠物入住吗", "设施服务与常见问题"},
                new String[]{"早餐几点开始供应", "设施服务与常见问题"},
                new String[]{"车停哪里，收费吗", "设施服务与常见问题"},
                new String[]{"标准单人间多少钱一晚", "房型与价格说明"},
                new String[]{"一家三口住什么房合适", "房型与价格说明"},
                new String[]{"酒店离地铁站多远", "逸云酒店介绍"},
                new String[]{"加床需要额外收费吗", "设施服务与常见问题"},
                new String[]{"房间里有吹风机吗", "房型与价格说明"},
                new String[]{"发票怎么开", "设施服务与常见问题"}
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> hitCount = new LinkedHashMap<>();
        Map<String, Integer> attempted = new LinkedHashMap<>();
        for (String mode : List.of("keyword", "vector", "hybrid")) {
            hitCount.put(mode, 0);
            attempted.put(mode, 0);
        }

        for (String[] item : cases) {
            String question = item[0];
            String expected = item[1];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", question);
            row.put("expected", expected);

            Map<String, Object> modes = new LinkedHashMap<>();
            for (String mode : List.of("keyword", "vector", "hybrid")) {
                if ("vector".equals(mode) || "hybrid".equals(mode)) {
                    if (!kbRetriever.vectorAvailable()) {
                        modes.put(mode, Map.of("available", false));
                        continue;
                    }
                }
                List<KbChunkHit> hits;
                try {
                    hits = kbRetriever.search(question, 3, mode);
                } catch (Exception e) {
                    modes.put(mode, Map.of("available", false, "error", String.valueOf(e.getMessage())));
                    continue;
                }
                boolean hit = hits.stream().anyMatch(h -> h.getDocTitle() != null && h.getDocTitle().contains(expected));
                attempted.merge(mode, 1, Integer::sum);
                if (hit) {
                    hitCount.merge(mode, 1, Integer::sum);
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("available", true);
                detail.put("hit", hit);
                detail.put("top", hits.stream().map(KbChunkHit::getDocTitle).toList());
                modes.put(mode, detail);
            }
            row.put("modes", modes);
            rows.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        for (String mode : List.of("keyword", "vector", "hybrid")) {
            int total = attempted.getOrDefault(mode, 0);
            int hit = hitCount.getOrDefault(mode, 0);
            summary.put(mode, total == 0
                    ? "未启用"
                    : hit + "/" + total + " (" + Math.round(hit * 100.0 / total) + "%)");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("retrievalMode", kbRetriever.currentMode());
        result.put("embeddingModel", kbRetriever.embeddingModel());
        result.put("cases", rows);
        return Result.success(result);
    }
}

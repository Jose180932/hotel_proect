package com.example.hotel_back.Service.ServiceImpl;

import com.example.hotel_back.Common.rag.KbRetriever;
import com.example.hotel_back.Pojo.VO.rag.ChatReference;
import com.example.hotel_back.Pojo.VO.rag.ChatReply;
import com.example.hotel_back.Pojo.VO.rag.KbChunkHit;
import com.example.hotel_back.Service.ChatService;
import com.example.hotel_back.Service.ChatStreamListener;
import com.example.hotel_back.mapper.ChatRagMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能客服实现：
 * 1. 结构化知识：房型 / 空房 / 评价（实时查库）
 * 2. 非结构化知识：kb_chunk 检索（关键词或向量，可配置）
 * 3. 大模型生成：支持普通返回与 SSE 流式
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    @Value("${hotel.deepseek.api-key:}")
    private String apiKey;

    @Value("${hotel.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${hotel.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${hotel.rag.top-k:3}")
    private int topK;

    @Autowired
    private ChatRagMapper chatRagMapper;

    @Autowired
    private KbRetriever kbRetriever;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        restTemplate = new RestTemplate(factory);
    }

    private static final String SYSTEM_PROMPT = """
            你是"逸云酒店"的智能客服助手，请协助顾客完成客房预订与咨询。

            通用信息：
            - 入住时间：14:00后，退房时间：次日12:00前
            - 支持在线预订、到店支付

            要求：
            1. 用亲切、礼貌、简洁的中文回答，控制在150字以内。
            2. 房价、房型、空房、评价、政策等事实，必须严格以"实时业务数据"和"酒店知识库"为准，禁止编造资料里没有的数字。
            3. 若资料中没有顾客所需信息，请引导顾客前往"客房预订"页面查看，或建议联系前台 8888。
            4. 超出酒店服务范围的问题，礼貌说明并建议联系前台。
            5. 使用纯文本，不要使用 markdown 格式；引用资料时在句末用 [1][2] 标注来源编号。
            """;

    @Override
    public String chat(List<Map<String, Object>> messages) {
        return chatWithReferences(messages).getAnswer();
    }

    @Override
    public ChatReply chatWithReferences(List<Map<String, Object>> messages) {
        String query = extractLatestUserQuery(messages);
        List<KbChunkHit> hits = kbRetriever.search(query, topK);
        List<ChatReference> references = toReferences(hits, query);

        if (!llmAvailable()) {
            log.warn("未配置 hotel.deepseek.api-key，返回检索结果兜底");
            return degradedReply(references);
        }

        try {
            List<Map<String, Object>> fullMessages = buildMessages(messages, hits);
            String answer = requestCompletion(fullMessages);
            return ChatReply.builder()
                    .answer(answer)
                    .references(references)
                    .llmAvailable(true)
                    .build();
        } catch (Exception e) {
            log.error("调用大模型失败，返回检索兜底", e);
            return degradedReply(references);
        }
    }

    @Override
    public void streamChat(List<Map<String, Object>> messages, ChatStreamListener listener) {
        String query = extractLatestUserQuery(messages);
        List<KbChunkHit> hits = kbRetriever.search(query, topK);
        List<ChatReference> references = toReferences(hits, query);
        listener.onReferences(references);

        if (!llmAvailable()) {
            log.warn("未配置 hotel.deepseek.api-key，流式返回检索兜底");
            streamText(degradedReply(references).getAnswer(), listener);
            return;
        }

        try {
            List<Map<String, Object>> fullMessages = buildMessages(messages, hits);
            streamCompletion(fullMessages, listener);
        } catch (Exception e) {
            log.error("流式调用大模型失败，返回检索兜底", e);
            streamText(degradedReply(references).getAnswer(), listener);
        }
    }

    // ------------------------------------------------------------------ 检索

    private List<ChatReference> toReferences(List<KbChunkHit> hits, String query) {
        // 一个命中对应一个引用，保持与 prompt 中的 [1][2] 编号一致（不做合并）
        List<ChatReference> references = new ArrayList<>();
        for (KbChunkHit hit : hits) {
            references.add(ChatReference.builder()
                    .docTitle(hit.getDocTitle() == null ? "酒店资料" : hit.getDocTitle())
                    .snippet(snippet(hit.getContent(), query))
                    .score(hit.getScore())
                    .build());
        }
        return references;
    }

    private String snippet(String content, String query) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        if (flat.length() <= 160) {
            return flat;
        }
        int index = firstMatchIndex(flat, query);
        if (index < 0) {
            return flat.substring(0, 160) + "…";
        }
        int start = Math.max(0, index - 60);
        int end = Math.min(flat.length(), start + 160);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }

    /**
     * 找到查询词在正文中的首次出现位置，让引用片段从命中处附近开始
     */
    private int firstMatchIndex(String text, String query) {
        if (query == null || query.isBlank()) {
            return -1;
        }
        int best = -1;
        for (String part : query.split("[\\p{Punct}\\s，。！？；：、（）【】《》]+")) {
            if (part.length() < 2) {
                continue;
            }
            int index = text.indexOf(part);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        String han = query.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
        for (int i = 0; i + 2 <= han.length(); i++) {
            int index = text.indexOf(han.substring(i, i + 2));
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private String buildKnowledgeContext(List<KbChunkHit> hits) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (KbChunkHit hit : hits) {
            String content = hit.getContent() == null ? "" : hit.getContent().replaceAll("\\s+", " ").trim();
            if (content.length() > 500) {
                content = content.substring(0, 500);
            }
            builder.append("[").append(index++).append("] 《")
                    .append(hit.getDocTitle()).append("》").append(content).append("\n");
        }
        return builder.toString().trim();
    }

    // ------------------------------------------------------------------ prompt

    private List<Map<String, Object>> buildMessages(List<Map<String, Object>> messages, List<KbChunkHit> hits) {
        String query = extractLatestUserQuery(messages);
        String structured = retrieveStructuredKnowledge(query);
        String knowledge = buildKnowledgeContext(hits);

        StringBuilder system = new StringBuilder(SYSTEM_PROMPT);
        if (!structured.isBlank()) {
            system.append("\n\n【实时业务数据】\n").append(structured);
        }
        if (!knowledge.isBlank()) {
            system.append("\n\n【酒店知识库（带编号，回答时可引用）】\n").append(knowledge);
        }

        List<Map<String, Object>> fullMessages = new ArrayList<>();
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", system.toString());
        fullMessages.add(systemMessage);
        fullMessages.addAll(messages);
        return fullMessages;
    }

    /**
     * 结构化知识：房型、空房、评价（实时查库，与文档知识互补）
     */
    private String retrieveStructuredKnowledge(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();

        if (containsAny(query, "房", "价格", "多少钱", "房价", "费用", "大床", "双床", "套房",
                "总统", "亲子", "面积", "床", "设施", "wifi", "WiFi", "WIFI", "免费", "网络",
                "几人间", "住", "介绍", "描述")) {
            List<Map<String, Object>> roomTypes = chatRagMapper.listRoomTypes();
            if (roomTypes != null && !roomTypes.isEmpty()) {
                builder.append("【实时房型数据】\n");
                for (Map<String, Object> roomType : roomTypes) {
                    builder.append("- ").append(roomType.get("typeName"))
                            .append("：").append(roomType.get("price")).append("元/晚，")
                            .append(roomType.get("area")).append("㎡，")
                            .append(roomType.get("bedType")).append("，可住")
                            .append(roomType.get("maxGuests")).append("人；设施：")
                            .append(roomType.get("facilities")).append("\n");
                }
            }
        }

        if (containsAny(query, "空房", "有空", "空余", "可订", "预订", "有房", "还有没有房", "房源", "能不能订")) {
            List<Map<String, Object>> freeRooms = chatRagMapper.countFreeRooms();
            if (freeRooms != null && !freeRooms.isEmpty()) {
                builder.append("【当前空房情况】\n");
                for (Map<String, Object> freeRoom : freeRooms) {
                    builder.append("- ").append(freeRoom.get("typeName")).append("：空闲 ")
                            .append(freeRoom.get("freeCount")).append(" 间\n");
                }
            }
        }

        if (containsAny(query, "评价", "口碑", "好评", "差评", "评分", "怎么样", "体验", "反馈", "环境")) {
            List<Map<String, Object>> reviews = chatRagMapper.listRecentReviews();
            if (reviews != null && !reviews.isEmpty()) {
                builder.append("【近期住客评价】\n");
                for (Map<String, Object> review : reviews) {
                    builder.append("- ").append(review.get("content")).append("（")
                            .append(review.get("score")).append(" 分）\n");
                }
            }
        }
        return builder.toString().trim();
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String extractLatestUserQuery(List<Map<String, Object>> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            if (message != null && "user".equals(message.get("role"))) {
                Object content = message.get("content");
                return content == null ? "" : content.toString();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 大模型

    private boolean llmAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    private String chatUrl() {
        return baseUrl.replaceAll("/+$", "") + "/chat/completions";
    }

    private String requestCompletion(List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("temperature", 0.7);
        body.put("max_tokens", 500);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(chatUrl(), new HttpEntity<>(body, headers), JsonNode.class);
        JsonNode root = response.getBody();
        if (root == null) {
            throw new IllegalStateException("大模型返回为空");
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("大模型返回格式异常: " + root.path("error"));
        }
        return content.asText().trim();
    }

    private void streamCompletion(List<Map<String, Object>> messages, ChatStreamListener listener) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", 0.7);
        body.put("max_tokens", 600);
        byte[] payload = objectMapper.writeValueAsBytes(body);

        restTemplate.execute(chatUrl(), HttpMethod.POST, request -> {
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getHeaders().setBearerAuth(apiKey);
            request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
            request.getBody().write(payload);
        }, response -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
                        listener.onToken(delta.asText());
                    }
                }
            }
            return null;
        });
        listener.onComplete();
    }

    private void streamText(String text, ChatStreamListener listener) {
        int step = 12;
        for (int i = 0; i < text.length(); i += step) {
            listener.onToken(text.substring(i, Math.min(i + step, text.length())));
        }
        listener.onComplete();
    }

    private ChatReply degradedReply(List<ChatReference> references) {
        StringBuilder builder = new StringBuilder("（当前大模型服务不可用，以下是根据酒店资料检索到的内容，供您参考）\n\n");
        if (references.isEmpty()) {
            builder.append("抱歉，暂时没有检索到相关资料，请稍后再试或致电前台 8888。\n");
        } else {
            int index = 1;
            for (ChatReference reference : references) {
                builder.append(index++).append(". 《").append(reference.getDocTitle()).append("》")
                        .append(reference.getSnippet()).append("\n");
            }
        }
        return ChatReply.builder()
                .answer(builder.toString())
                .references(references)
                .llmAvailable(false)
                .build();
    }
}

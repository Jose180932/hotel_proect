package com.example.hotel_back.Common.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容协议的向量模型客户端（硅基流动 / 百炼 / 智谱 / Ollama 均可）。
 * 配置示例：
 *   hotel.embedding.provider=openai-compat
 *   hotel.embedding.base-url=https://api.siliconflow.cn/v1
 *   hotel.embedding.model=BAAI/bge-m3
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hotel.embedding.provider", havingValue = "openai-compat")
public class OpenAiCompatEmbeddingClient implements EmbeddingClient {

    @Value("${hotel.embedding.base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;

    @Value("${hotel.embedding.api-key:}")
    private String apiKey;

    @Value("${hotel.embedding.model:BAAI/bge-m3}")
    private String model;

    @Value("${hotel.embedding.dim:1024}")
    private int dim;

    @Value("${hotel.embedding.timeout-ms:20000}")
    private int timeoutMs;

    private static final int MAX_INPUT_CHARS = 6000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public float[] embed(String text) {
        if (!available()) {
            throw new IllegalStateException("hotel.embedding.api-key 为空，无法调用向量模型");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/embeddings";
        String input = text == null ? "" : text;
        if (input.length() > MAX_INPUT_CHARS) {
            input = input.substring(0, MAX_INPUT_CHARS);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("encoding_format", "float");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), JsonNode.class);
        JsonNode root = response.getBody();
        if (root == null) {
            throw new IllegalStateException("向量模型返回为空");
        }
        JsonNode array = root.path("data").path(0).path("embedding");
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalStateException("向量模型返回格式异常: " + root.path("error"));
        }

        float[] vector = new float[array.size()];
        double norm = 0D;
        for (int i = 0; i < array.size(); i++) {
            vector[i] = (float) array.get(i).asDouble();
            norm += vector[i] * vector[i];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) norm;
            }
        }
        if (vector.length != dim) {
            log.debug("向量维度与配置不一致：实际 {}，配置 {}", vector.length, dim);
        }
        return vector;
    }
}

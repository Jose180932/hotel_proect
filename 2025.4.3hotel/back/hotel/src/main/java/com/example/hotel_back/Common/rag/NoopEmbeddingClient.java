package com.example.hotel_back.Common.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 未配置向量模型时的空实现：关键词模式使用，不产生任何外部调用。
 */
@Component
@ConditionalOnProperty(name = "hotel.embedding.provider", havingValue = "none", matchIfMissing = true)
public class NoopEmbeddingClient implements EmbeddingClient {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String modelName() {
        return "none";
    }

    @Override
    public int dimension() {
        return 0;
    }

    @Override
    public float[] embed(String text) {
        throw new IllegalStateException("hotel.embedding.provider=none，未配置向量模型；请改用关键词检索或配置 embedding 服务");
    }
}

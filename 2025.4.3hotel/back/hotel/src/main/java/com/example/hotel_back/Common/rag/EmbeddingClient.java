package com.example.hotel_back.Common.rag;

/**
 * 向量模型客户端。
 * 实现可插拔：hotel.embedding.provider = none | openai-compat
 */
public interface EmbeddingClient {

    /** 是否可用（配置了 key 且 provider 支持） */
    boolean available();

    /** 模型名，写入 kb_chunk.embedding_model */
    String modelName();

    /** 维度，写入 kb_chunk.embedding_dim */
    int dimension();

    /**
     * 文本转向量；返回的向量已做 L2 归一化，检索时可直接用点积当余弦。
     */
    float[] embed(String text);
}

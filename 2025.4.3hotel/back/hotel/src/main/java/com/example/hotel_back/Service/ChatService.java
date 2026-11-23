package com.example.hotel_back.Service;

import com.example.hotel_back.Pojo.VO.rag.ChatReply;

import java.util.List;
import java.util.Map;

/**
 * 智能客服：结构化业务数据 + 文档知识库（RAG）
 */
public interface ChatService {

    /**
     * 兼容旧接口：只返回回答文本
     */
    String chat(List<Map<String, Object>> messages);

    /**
     * 返回回答 + 引用来源
     */
    ChatReply chatWithReferences(List<Map<String, Object>> messages);

    /**
     * 流式回答：先回调引用，再逐 token 回调
     */
    void streamChat(List<Map<String, Object>> messages, ChatStreamListener listener);
}

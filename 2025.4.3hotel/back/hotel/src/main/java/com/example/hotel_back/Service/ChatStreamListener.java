package com.example.hotel_back.Service;

import com.example.hotel_back.Pojo.VO.rag.ChatReference;

import java.util.List;

/**
 * 流式对话回调：先给引用，再逐 token 输出
 */
public interface ChatStreamListener {

    void onReferences(List<ChatReference> references);

    void onToken(String token);

    void onComplete();

    void onError(Throwable error);
}

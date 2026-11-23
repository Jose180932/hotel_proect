package com.example.hotel_back.Pojo.VO.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 客服回答（含引用，便于前端展示“依据”）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReply implements Serializable {

    private String answer;

    private List<ChatReference> references;

    /** 大模型是否可用；false 表示返回的是检索结果兜底 */
    private boolean llmAvailable;
}

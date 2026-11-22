package com.example.hotel_back.Pojo.VO.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 回答引用的知识来源
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReference implements Serializable {

    /** 来源文档标题 */
    private String docTitle;

    /** 命中的原文片段（截断） */
    private String snippet;

    /** 相关度 */
    private Double score;
}

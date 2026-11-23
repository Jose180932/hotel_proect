package com.example.hotel_back.Pojo.Entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库切片
 */
@Data
public class KbChunk implements Serializable {

    private Long id;

    private Long docId;

    /** 切片序号，从 1 开始 */
    private Integer seq;

    /** 切片正文 */
    private String content;

    /** 向量（JSON 数组字符串），关键词模式为 null */
    private String embedding;

    private String embeddingModel;

    private Integer embeddingDim;

    /** 粗略字数，用于控制 prompt 长度 */
    private Integer tokenCount;

    private LocalDateTime createTime;
}

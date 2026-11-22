package com.example.hotel_back.Pojo.VO.rag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;

/**
 * 检索命中结果
 */
@Data
public class KbChunkHit implements Serializable {

    private Long docId;

    private String docTitle;

    private Integer seq;

    private String content;

    /** 相关度，关键词模式为命中词占比，向量模式为余弦相似度 */
    private Double score;

    /** 向量 JSON（仅内部使用，不返回给前端） */
    @JsonIgnore
    private String embedding;
}

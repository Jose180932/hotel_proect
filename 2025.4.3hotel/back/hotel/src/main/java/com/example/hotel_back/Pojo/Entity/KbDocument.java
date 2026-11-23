package com.example.hotel_back.Pojo.Entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档
 */
@Data
public class KbDocument implements Serializable {

    private Long id;

    /** 文档标题 */
    private String title;

    /** 来源文件名 */
    private String source;

    /** 内容 SHA-256，用于幂等导入 */
    private String contentHash;

    /** 原文（上传/导入时保存，重建索引时重新切块） */
    private String content;

    /** 切片数 */
    private Integer chunkCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除标记 */
    private Integer flag;
}

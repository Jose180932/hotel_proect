package com.example.hotel_back.Service;

import com.example.hotel_back.Pojo.Entity.KbDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理：导入、上传、删除、重建、统计
 */
public interface KbService {

    /**
     * 扫描配置目录下的文档并导入（按内容哈希幂等）
     *
     * @return 本次新导入的文档数
     */
    int ingestAll();

    /**
     * 重建全部文档的切片与向量（基于库中保存的原文）
     */
    Map<String, Object> rebuild();

    /**
     * 知识库统计
     */
    Map<String, Object> stats();

    /**
     * 文档列表（不含正文）
     */
    List<KbDocument> list();

    /**
     * 上传文档并入库（支持 .md / .markdown / .txt / .docx）
     */
    Map<String, Object> upload(MultipartFile file);

    /**
     * 删除文档及其切片
     */
    void delete(Long id);
}

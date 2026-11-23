package com.example.hotel_back.mapper;

import com.example.hotel_back.Pojo.Entity.KbChunk;
import com.example.hotel_back.Pojo.Entity.KbDocument;
import com.example.hotel_back.Pojo.VO.rag.KbChunkHit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * RAG 知识库 Mapper
 */
@Mapper
public interface KbMapper {

    @Select("select * from kb_document where content_hash = #{hash} and flag = 0 limit 1")
    KbDocument findDocumentByHash(@Param("hash") String hash);

    @Insert("insert into kb_document (title, source, content_hash, content, chunk_count, create_time, update_time, flag) " +
            "values (#{title}, #{source}, #{contentHash}, #{content}, #{chunkCount}, now(), now(), 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertDocument(KbDocument document);

    @Update("update kb_document set chunk_count = #{chunkCount}, update_time = now() where id = #{id}")
    void updateChunkCount(@Param("id") Long id, @Param("chunkCount") Integer chunkCount);

    @Insert("insert into kb_chunk (doc_id, seq, content, embedding, embedding_model, embedding_dim, token_count, create_time) " +
            "values (#{docId}, #{seq}, #{content}, #{embedding}, #{embeddingModel}, #{embeddingDim}, #{tokenCount}, now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertChunk(KbChunk chunk);

    @Select("select id, doc_id, seq, content, embedding, embedding_model, embedding_dim from kb_chunk")
    List<KbChunk> listAllChunks();

    /**
     * ngram 全文检索（中文可用）
     */
    @Select("select d.id as docId, d.title as docTitle, c.seq as seq, c.content as content, " +
            "match(c.content) against(#{query} in natural language mode) as score " +
            "from kb_chunk c join kb_document d on d.id = c.doc_id " +
            "where d.flag = 0 and match(c.content) against(#{query} in natural language mode) > 0 " +
            "order by score desc limit #{limit}")
    List<KbChunkHit> searchFulltext(@Param("query") String query, @Param("limit") int limit);

    /**
     * LIKE 兜底：没有全文索引时使用
     */
    @Select("<script>" +
            "select d.id as docId, d.title as docTitle, c.seq as seq, c.content as content " +
            "from kb_chunk c join kb_document d on d.id = c.doc_id " +
            "where d.flag = 0 and (" +
            "<foreach collection='terms' item='t' separator=' or '>c.content like concat('%', #{t}, '%')</foreach>" +
            ") limit #{limit}" +
            "</script>")
    List<KbChunkHit> searchLike(@Param("terms") List<String> terms, @Param("limit") int limit);

    /**
     * 向量检索用：带文档标题与向量
     */
    @Select("select d.id as docId, d.title as docTitle, c.seq as seq, c.content as content, c.embedding as embedding " +
            "from kb_chunk c join kb_document d on d.id = c.doc_id " +
            "where d.flag = 0 and c.embedding is not null")
    List<KbChunkHit> listChunksForVector();


    @Select("select id, title, source, content_hash, chunk_count, create_time, update_time from kb_document where flag = 0 order by id desc")
    List<KbDocument> listDocuments();

    @Select("select id, title, source, content, chunk_count from kb_document where id = #{id} and flag = 0")
    KbDocument findDocumentById(@Param("id") Long id);

    @Select("select id, title, source, content, chunk_count from kb_document where id = #{id} and flag = 0 for update")
    KbDocument findDocumentByIdForUpdate(@Param("id") Long id);

    @Select("select id, title, source, content from kb_document where flag = 0")
    List<KbDocument> listDocumentsWithContent();

    @Delete("delete from kb_chunk where doc_id = #{docId}")
    void deleteChunksByDocId(@Param("docId") Long docId);

    @Delete("delete from kb_document where id = #{id}")
    void deleteDocumentById(@Param("id") Long id);
    @Select("select count(*) from kb_document where flag = 0")
    int countDocuments();

    @Select("select count(*) from kb_chunk")
    int countChunks();

    @Select("select count(*) from kb_chunk where embedding is not null")
    int countEmbeddedChunks();

    @Delete("delete from kb_chunk")
    void deleteAllChunks();

    @Delete("delete from kb_document")
    void deleteAllDocuments();
}

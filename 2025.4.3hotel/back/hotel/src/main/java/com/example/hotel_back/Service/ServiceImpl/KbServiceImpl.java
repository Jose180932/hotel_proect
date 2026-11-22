package com.example.hotel_back.Service.ServiceImpl;

import com.example.hotel_back.Common.rag.ChunkSplitter;
import com.example.hotel_back.Common.rag.EmbeddingClient;
import com.example.hotel_back.Common.rag.KbRetriever;
import com.example.hotel_back.Pojo.Entity.KbChunk;
import com.example.hotel_back.Pojo.Entity.KbDocument;
import com.example.hotel_back.Service.KbService;
import com.example.hotel_back.mapper.KbMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 知识库实现：classpath 文档 + 上传文档 → 切块 → 入库 →（可选）向量化
 */
@Slf4j
@Service
public class KbServiceImpl implements KbService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_CHUNKS_PER_DOC = 300;

    @Value("${hotel.rag.doc-pattern:classpath:kb/*.md}")
    private String docPattern;

    @Autowired
    private KbMapper kbMapper;

    @Autowired
    private ChunkSplitter chunkSplitter;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private KbRetriever kbRetriever;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------ 导入

    @Override
    public int ingestAll() {
        Resource[] resources;
        try {
            resources = resourceResolver.getResources(docPattern);
        } catch (IOException e) {
            throw new IllegalStateException("读取知识库目录失败: " + docPattern, e);
        }
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));

        int imported = 0;
        for (Resource resource : resources) {
            try (InputStream input = resource.getInputStream()) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                String filename = resource.getFilename() == null ? "未命名.md" : resource.getFilename();
                KbDocument document = transactionTemplate.execute(
                        status -> ingestText(extractTitle(text, filename), filename, text));
                if (document != null) {
                    imported++;
                }
            } catch (Exception e) {
                log.warn("知识库导入失败 [{}]：{}", resource.getFilename(), e.getMessage());
            }
        }
        log.info("知识库导入完成：新增 {} 篇；当前 {} 篇 / {} 切片 / 已向量化 {} 条",
                imported, kbMapper.countDocuments(), kbMapper.countChunks(), kbMapper.countEmbeddedChunks());
        return imported;
    }

    /**
     * @return 新建的文档，若已存在（内容相同）返回 null
     */
    private KbDocument ingestText(String title, String source, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String contentHash = sha256(text);
        if (kbMapper.findDocumentByHash(contentHash) != null) {
            return null;
        }
        KbDocument document = new KbDocument();
        document.setTitle(title);
        document.setSource(source);
        document.setContentHash(contentHash);
        document.setContent(text);
        document.setChunkCount(0);
        kbMapper.insertDocument(document);
        int chunks = indexDocument(document);
        document.setChunkCount(chunks);
        log.info("导入文档《{}》：{} 个切片", document.getTitle(), chunks);
        return document;
    }

    // ------------------------------------------------------------------ 切块入库

    /**
     * 按文档原文重新切块、向量化并写入 kb_chunk
     */
    private int indexDocument(KbDocument document) {
        List<String> chunks = splitFor(document.getContent(), document.getTitle());
        if (chunks.size() > MAX_CHUNKS_PER_DOC) {
            log.warn("文档《{}》切片过多（{}），仅保留前 {} 个", document.getTitle(), chunks.size(), MAX_CHUNKS_PER_DOC);
            chunks = chunks.subList(0, MAX_CHUNKS_PER_DOC);
        }
        kbMapper.deleteChunksByDocId(document.getId());

        int seq = 1;
        for (String chunkText : chunks) {
            KbChunk chunk = new KbChunk();
            chunk.setDocId(document.getId());
            chunk.setSeq(seq++);
            chunk.setContent(chunkText);
            chunk.setTokenCount(chunkText.length());

            if (embeddingClient.available()) {
                try {
                    float[] vector = embeddingClient.embed(chunkText);
                    chunk.setEmbedding(objectMapper.writeValueAsString(vector));
                    chunk.setEmbeddingModel(embeddingClient.modelName());
                    chunk.setEmbeddingDim(vector.length);
                } catch (Exception e) {
                    log.warn("切片向量化失败（{}）: {}", document.getTitle(), e.getMessage());
                }
            }
            kbMapper.insertChunk(chunk);
        }
        kbMapper.updateChunkCount(document.getId(), chunks.size());
        return chunks.size();
    }

    /**
     * 去掉 markdown 标题符与开头重复的标题行后再切块
     */
    private List<String> splitFor(String content, String title) {
        if (content == null) {
            return List.of();
        }
        String body = content.replaceAll("(?m)^[ \\t]*#{1,6}[ \\t]*", "");
        if (title != null && !title.isBlank()) {
            body = body.replaceFirst("^\\s*" + Pattern.quote(title) + "\\s*\\n", "");
        }
        return chunkSplitter.split(body);
    }

    // ------------------------------------------------------------------ 管理

    @Override
    public Map<String, Object> rebuild() {
        int reindexed = 0;
        for (KbDocument document : kbMapper.listDocumentsWithContent()) {
            try {
                Boolean indexed = transactionTemplate.execute(status -> {
                    KbDocument current = kbMapper.findDocumentByIdForUpdate(document.getId());
                    if (current == null) {
                        return false;
                    }
                    indexDocument(current);
                    return true;
                });
                if (Boolean.TRUE.equals(indexed)) {
                    reindexed++;
                }
            } catch (Exception e) {
                log.warn("重建失败 [{}]：{}", document.getTitle(), e.getMessage());
            }
        }
        // 补齐 classpath 里新增、库里还没有的文档
        int imported = ingestAll();

        Map<String, Object> result = stats();
        result.put("reindexed", reindexed);
        result.put("imported", imported);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalStateException("文件不能超过 10MB");
        }
        String filename = file.getOriginalFilename() == null ? "upload.md" : file.getOriginalFilename();
        String lower = filename.toLowerCase();
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") || lower.endsWith(".docx"))) {
            throw new IllegalStateException("仅支持 .md / .txt / .docx 文件");
        }

        String text = readText(file, lower);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("文档内容为空");
        }
        if (kbMapper.findDocumentByHash(sha256(text)) != null) {
            throw new IllegalStateException("内容相同的文档已存在，无需重复上传");
        }

        KbDocument document = ingestText(extractTitle(text, filename), filename, text);
        if (document == null) {
            throw new IllegalStateException("文档已存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", document.getId());
        result.put("title", document.getTitle());
        result.put("source", document.getSource());
        result.put("chunks", document.getChunkCount());
        return result;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        KbDocument document = kbMapper.findDocumentByIdForUpdate(id);
        if (document == null) {
            throw new IllegalStateException("文档不存在");
        }
        kbMapper.deleteChunksByDocId(id);
        kbMapper.deleteDocumentById(id);
        log.info("删除知识库文档《{}》(id={})", document.getTitle(), id);
    }

    @Override
    public List<KbDocument> list() {
        return kbMapper.listDocuments();
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("documents", kbMapper.countDocuments());
        stats.put("chunks", kbMapper.countChunks());
        stats.put("embeddedChunks", kbMapper.countEmbeddedChunks());
        stats.put("retrievalMode", kbRetriever.currentMode());
        stats.put("embeddingAvailable", kbRetriever.vectorAvailable());
        stats.put("embeddingModel", kbRetriever.embeddingModel());
        return stats;
    }

    // ------------------------------------------------------------------ 解析

    private String readText(MultipartFile file, String lowerFilename) {
        try {
            byte[] bytes = file.getBytes();
            if (lowerFilename.endsWith(".docx")) {
                return readDocx(bytes);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + e.getMessage());
        }
    }

    private String readDocx(byte[] bytes) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    builder.append(text).append('\n');
                }
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        builder.append(cell.getText()).append(' ');
                    }
                    builder.append('\n');
                }
            }
        }
        return builder.toString();
    }

    private String extractTitle(String text, String filename) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return filename == null ? "未命名文档" : filename.replaceFirst("\\.[^.]+$", "");
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

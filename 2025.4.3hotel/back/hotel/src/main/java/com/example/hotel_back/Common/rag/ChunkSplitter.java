package com.example.hotel_back.Common.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切块器：
 * 1. 按空行分段；
 * 2. 段落合并到目标长度；
 * 3. 超长段落按句末标点拆分；
 * 4. 相邻切片保留少量重叠，避免语义被切断。
 */
@Component
public class ChunkSplitter {

    @Value("${hotel.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${hotel.rag.chunk-overlap:80}")
    private int overlap;

    private static final int MIN_CHUNK_LENGTH = 80;

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder();
        for (String rawParagraph : normalized.split("\n\\s*\n")) {
            String paragraph = rawParagraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() > chunkSize) {
                flush(chunks, current);
                chunks.addAll(splitLongParagraph(paragraph));
                continue;
            }
            if (current.length() > 0 && current.length() + paragraph.length() + 1 > chunkSize) {
                String finished = current.toString().trim();
                chunks.add(finished);
                current.setLength(0);
                String carry = tail(finished);
                if (!carry.isEmpty()) {
                    current.append(carry).append('\n');
                }
            }
            current.append(paragraph).append('\n');
        }
        flush(chunks, current);
        return mergeTiny(chunks);
    }

    private void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> result = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String sentence : paragraph.split("(?<=[。！？；!?;])")) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (piece.length() > 0 && piece.length() + s.length() > chunkSize) {
                String finished = piece.toString().trim();
                result.add(finished);
                piece.setLength(0);
                String carry = tail(finished);
                if (!carry.isEmpty()) {
                    piece.append(carry).append('\n');
                }
            }
            piece.append(s).append('\n');
        }
        if (piece.length() > 0) {
            result.add(piece.toString().trim());
        }
        return result;
    }

    private String tail(String text) {
        if (overlap <= 0 || text.length() <= overlap) {
            return "";
        }
        return text.substring(text.length() - overlap);
    }

    private List<String> mergeTiny(List<String> chunks) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        List<String> merged = new ArrayList<>();
        for (String chunk : chunks) {
            boolean canMerge = !merged.isEmpty()
                    && chunk.length() < MIN_CHUNK_LENGTH
                    && merged.get(merged.size() - 1).length() + chunk.length() <= chunkSize;
            if (canMerge) {
                int last = merged.size() - 1;
                merged.set(last, merged.get(last) + "\n" + chunk);
            } else {
                merged.add(chunk);
            }
        }
        return merged;
    }
}

package com.example.hotel_back.Controller;

import com.example.hotel_back.Common.result.Result;
import com.example.hotel_back.Pojo.VO.rag.ChatReference;
import com.example.hotel_back.Pojo.VO.rag.ChatReply;
import com.example.hotel_back.Service.ChatService;
import com.example.hotel_back.Service.ChatStreamListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 智能客服机器人接口（大模型 + RAG）
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * 普通返回：{ "messages": [ { "role": "user", "content": "..." } ] }
     * 返回：{ answer, references, llmAvailable }
     */
    @PostMapping("/send")
    public Result<ChatReply> send(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> messages = parseMessages(body);
        if (messages == null) {
            return Result.error("参数错误");
        }
        log.info("收到客服消息，共 {} 条", messages.size());
        return Result.success(chatService.chatWithReferences(messages));
    }

    /**
     * 流式返回（SSE）：
     *   event: references → 引用列表
     *   event: token      → 逐段文本
     *   event: done       → 结束
     *   event: error      → 异常信息
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(120_000L);
        List<Map<String, Object>> messages = parseMessages(body);
        if (messages == null) {
            sendEvent(emitter, "error", Map.of("message", "参数错误"));
            emitter.complete();
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            try {
                chatService.streamChat(messages, new ChatStreamListener() {
                    @Override
                    public void onReferences(List<ChatReference> references) {
                        sendEvent(emitter, "references", references);
                    }

                    @Override
                    public void onToken(String token) {
                        // 包成 JSON 对象，避免文本里的换行被 SSE 拆成多行 data
                        sendEvent(emitter, "token", Map.of("text", token));
                    }

                    @Override
                    public void onComplete() {
                        sendEvent(emitter, "done", "ok");
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        sendEvent(emitter, "error", Map.of("message", error.getMessage() == null ? "服务异常" : error.getMessage()));
                        emitter.complete();
                    }
                });
            } catch (Exception e) {
                log.error("流式对话异常", e);
                sendEvent(emitter, "error", Map.of("message", "服务异常，请稍后再试"));
                emitter.complete();
            }
        });
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseMessages(Map<String, Object> body) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return (List<Map<String, Object>>) messagesObj;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}

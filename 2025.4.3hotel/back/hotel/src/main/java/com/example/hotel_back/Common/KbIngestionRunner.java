package com.example.hotel_back.Common;

import com.example.hotel_back.Service.KbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时自动导入知识库（幂等）；失败只告警，不影响应用启动。
 */
@Slf4j
@Component
public class KbIngestionRunner implements ApplicationRunner {

    @Value("${hotel.rag.auto-ingest:true}")
    private boolean autoIngest;

    @Autowired
    private KbService kbService;

    @Override
    public void run(ApplicationArguments args) {
        if (!autoIngest) {
            return;
        }
        try {
            kbService.ingestAll();
        } catch (Exception e) {
            log.warn("知识库自动导入失败（不影响启动）：{}", e.getMessage());
        }
    }
}

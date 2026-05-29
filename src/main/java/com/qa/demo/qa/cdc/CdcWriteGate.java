package com.qa.demo.qa.cdc;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全量灌库/重建期间暂停 CDC 下游写入，避免与 Neo4j wipe、Qdrant recreate 并发冲突。
 */
@Component
public class CdcWriteGate {

    private final AtomicInteger pauseDepth = new AtomicInteger(0);

    public void pause() {
        pauseDepth.incrementAndGet();
    }

    public void resume() {
        pauseDepth.updateAndGet(depth -> Math.max(0, depth - 1));
    }

    public boolean isPaused() {
        return pauseDepth.get() > 0;
    }
}

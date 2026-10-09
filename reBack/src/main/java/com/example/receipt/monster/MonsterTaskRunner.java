package com.example.receipt.monster;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 別スレッドで行う処理（解析後のカードの下書き、コンピュータの思考）。
 * Springの共通 Executor を置き換えないよう、Beanにはせずここで持つ。
 */
@Component
public class MonsterTaskRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MonsterTaskRunner.class);
    private final AtomicInteger threadNo = new AtomicInteger();
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200), r -> {
        Thread t = new Thread(r, "monster-" + threadNo.incrementAndGet());
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());

    public void run(String label, Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOGGER.warn("Monster background task failed: task={}, exception={}", label, e.getClass().getName());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

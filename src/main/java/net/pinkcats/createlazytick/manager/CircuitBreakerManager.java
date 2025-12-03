package net.pinkcats.createlazytick.manager;

import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.CreateLazyTick;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CircuitBreakerManager {
    private static int circuit_breaker_failure_count = 0;
    private static long circuit_breaker_last_failure_time = 0;
    private static boolean circuit_breaker_enabled = false;

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final long CIRCUIT_BREAKER_WINDOW = 60000; //Ms

    private static final ReadWriteLock CIRCUIT_BREAKER_LOCK = new ReentrantReadWriteLock();

    //在设定时间内多次触发异常被捕获超过限定次数后进行切断，终止优化并回退到原逻辑
    //使用写锁控制并发情况(是这么用吗?)
    public static void incrementFailure() {
        CIRCUIT_BREAKER_LOCK.writeLock().lock();
        try {
            long currentTime = System.currentTimeMillis();

            if (currentTime - circuit_breaker_last_failure_time > CIRCUIT_BREAKER_WINDOW) {
                circuit_breaker_failure_count = 0;
            }

            circuit_breaker_failure_count++;
            circuit_breaker_last_failure_time = currentTime;

            if (circuit_breaker_failure_count >= CIRCUIT_BREAKER_THRESHOLD) {
                circuit_breaker_enabled = true;
                CreateLazyTick.LOGGER.error("Saw optimization circuit breaker triggered.Optimization has been disabled until server restart. Failure count:{}",
                        circuit_breaker_failure_count);
            }
        } finally {
            CIRCUIT_BREAKER_LOCK.writeLock().unlock();
        }
    }

    //重置熔断次数
    public static void resetCircuitBreaker() {
        CIRCUIT_BREAKER_LOCK.writeLock().lock();
        try {
            circuit_breaker_failure_count = 0;
            circuit_breaker_last_failure_time = 0;
            circuit_breaker_enabled = false;
            CreateLazyTick.LOGGER.debug("Saw optimization circuit breaker reset.");
        } finally {
            CIRCUIT_BREAKER_LOCK.writeLock().unlock();
        }
    }

    //获取优化是否可用
    public static boolean isOptimizationAvailable() {
        CIRCUIT_BREAKER_LOCK.readLock().lock();
        try {
            return Config.enable_saw_optimization && !circuit_breaker_enabled;
        } finally {
            CIRCUIT_BREAKER_LOCK.readLock().unlock();
        }
    }

    //获取异常触发次数
    public static int getCircuitBreakerFailureCount() {
        CIRCUIT_BREAKER_LOCK.readLock().lock();
        try {
            return circuit_breaker_failure_count;
        } finally {
            CIRCUIT_BREAKER_LOCK.readLock().unlock();
        }
    }

}

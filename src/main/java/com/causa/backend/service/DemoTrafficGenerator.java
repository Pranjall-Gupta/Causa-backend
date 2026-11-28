package com.causa.backend.service;

import com.causa.backend.model.DbMetric;
import com.causa.backend.model.DbSpan;
import com.causa.backend.repository.MetricRepository;
import com.causa.backend.repository.SpanRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optional demo-traffic generator, disabled by default.
 * Enable with CAUSA_DEMO_ENABLED=true. It writes simulated traces and metrics
 * (a payment-service failure cascading into checkout-api) every minute, backfills
 * the last minutes on startup, and deletes data older than the retention window.
 * While enabled, ALL spans and metrics older than the retention window are deleted,
 * so do not enable it on a deployment that stores real telemetry.
 */
@Service
@EnableScheduling
@ConditionalOnProperty(name = "causa.demo.enabled", havingValue = "true")
public class DemoTrafficGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DemoTrafficGenerator.class);
    private static final long MINUTE_MS = 60_000L;
    private static final long NANOS_PER_MS = 1_000_000L;

    @Autowired
    private SpanRepository spanRepository;

    @Autowired
    private MetricRepository metricRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${causa.demo.retention-minutes:120}")
    private int retentionMinutes;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        try {
            long nowMs = System.currentTimeMillis();
            long from = (nowMs - 2 * MINUTE_MS) * NANOS_PER_MS;
            long to = nowMs * NANOS_PER_MS;
            if (!spanRepository.findByStartTimeUnixNanoBetween(from, to).isEmpty()) {
                logger.info("Demo traffic: recent spans already present, skipping backfill");
                return;
            }
            logger.info("Demo traffic: backfilling the last 14 minutes");
            for (int m = 13; m >= 0; m--) {
                generateTick(nowMs - m * MINUTE_MS);
            }
        } catch (Exception e) {
            logger.error("Demo traffic backfill failed", e);
        }
    }

    @Scheduled(fixedRate = MINUTE_MS, initialDelay = MINUTE_MS)
    public void tick() {
        try {
            generateTick(System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Demo traffic tick failed", e);
        }
    }

    @Scheduled(fixedRate = 30 * MINUTE_MS, initialDelay = 5 * MINUTE_MS)
    public void cleanup() {
        try {
            long cutoffMs = System.currentTimeMillis() - retentionMinutes * MINUTE_MS;
            long cutoffNano = cutoffMs * NANOS_PER_MS;
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                int spans = entityManager
                        .createQuery("DELETE FROM DbSpan s WHERE s.startTimeUnixNano < :c")
                        .setParameter("c", cutoffNano)
                        .executeUpdate();
                int metrics = entityManager
                        .createQuery("DELETE FROM DbMetric m WHERE m.timestampMs < :c")
                        .setParameter("c", cutoffMs)
                        .executeUpdate();
                logger.info("Demo traffic cleanup removed {} spans and {} metrics", spans, metrics);
            });
        } catch (Exception e) {
            logger.error("Demo traffic cleanup failed", e);
        }
    }

    private void generateTick(long tickMs) {
        List<DbSpan> spans = new ArrayList<>();
        for (int i = 0; i < 4; i++) browseTrace(spans, startWithin(tickMs));
        for (int i = 0; i < 2; i++) purchaseTrace(spans, startWithin(tickMs));
        for (int i = 0; i < 3; i++) failingTrace(spans, startWithin(tickMs));
        spanRepository.saveAll(spans);
        metricRepository.saveAll(metricsFor(tickMs));
    }

    private long startWithin(long tickMs) {
        return tickMs - ThreadLocalRandom.current().nextLong(0, 55_000);
    }

    // checkout-api -> order-service -> inventory-service (healthy, fast)
    private void browseTrace(List<DbSpan> out, long startMs) {
        String tid = newTraceId();
        long s = startMs * NANOS_PER_MS;
        DbSpan root = span(tid, null, "checkout-api", "GET /cart", s, jitter(90), false, null);
        DbSpan order = span(tid, root.getSpanId(), "order-service", "GET /orders/cart", s + ms(10), jitter(60), false, null);
        DbSpan inv = span(tid, order.getSpanId(), "inventory-service", "GET /inventory/check", s + ms(15), jitter(30), false, null);
        out.add(root);
        out.add(order);
        out.add(inv);
    }

    // checkout-api -> order-service -> payment-service + inventory-service (healthy)
    private void purchaseTrace(List<DbSpan> out, long startMs) {
        String tid = newTraceId();
        long s = startMs * NANOS_PER_MS;
        DbSpan root = span(tid, null, "checkout-api", "POST /checkout", s, jitter(160), false, null);
        DbSpan order = span(tid, root.getSpanId(), "order-service", "POST /orders", s + ms(10), jitter(130), false, null);
        DbSpan pay = span(tid, order.getSpanId(), "payment-service", "POST /payments/charge", s + ms(20), jitter(60), false, null);
        DbSpan inv = span(tid, order.getSpanId(), "inventory-service", "POST /inventory/reserve", s + ms(85), jitter(25), false, null);
        out.add(root);
        out.add(order);
        out.add(pay);
        out.add(inv);
    }

    // payment-service times out and errors, order-service is slow, checkout-api returns 500
    private void failingTrace(List<DbSpan> out, long startMs) {
        String tid = newTraceId();
        long s = startMs * NANOS_PER_MS;
        DbSpan root = span(tid, null, "checkout-api", "POST /checkout", s, jitter(3200), true, "HTTP 500: payment failed");
        DbSpan order = span(tid, root.getSpanId(), "order-service", "POST /orders", s + ms(10), jitter(3000), false, null);
        DbSpan pay = span(tid, order.getSpanId(), "payment-service", "POST /payments/charge", s + ms(20), jitter(2800), true, "Payment gateway timeout");
        DbSpan inv = span(tid, order.getSpanId(), "inventory-service", "POST /inventory/reserve", s + ms(2830), jitter(30), false, null);
        out.add(root);
        out.add(order);
        out.add(pay);
        out.add(inv);
    }

    private List<DbMetric> metricsFor(long tickMs) {
        String[] services = {"checkout-api", "order-service", "payment-service", "inventory-service"};
        double[] cpu = {35, 30, 78, 25};
        double[] mem = {320, 280, 460, 210};
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<DbMetric> out = new ArrayList<>();
        for (int i = 0; i < services.length; i++) {
            double c = Math.max(1, Math.min(99, cpu[i] + r.nextDouble(-5, 5)));
            out.add(metric(tickMs, services[i], "cpu_usage", c));
            out.add(metric(tickMs, services[i], "memory_usage", mem[i] + r.nextDouble(-20, 20)));
        }
        return out;
    }

    private DbMetric metric(long tsMs, String service, String name, double value) {
        DbMetric m = new DbMetric();
        m.setTimestampMs(tsMs);
        m.setServiceName(service);
        m.setMetricName(name);
        m.setValue(value);
        return m;
    }

    private DbSpan span(String traceId, String parentId, String service, String name,
                        long startNano, double durationMs, boolean error, String errorMessage) {
        DbSpan s = new DbSpan();
        s.setTraceId(traceId);
        s.setSpanId(newSpanId());
        s.setParentSpanId(parentId);
        s.setServiceName(service);
        s.setName(name);
        s.setKind("SERVER");
        s.setStartTimeUnixNano(startNano);
        s.setEndTimeUnixNano(startNano + (long) (durationMs * NANOS_PER_MS));
        s.setDurationMs(durationMs);
        s.setStatusCode(error ? "ERROR" : "OK");
        s.setStatusMessage(error ? errorMessage : null);
        return s;
    }

    private static long ms(long value) {
        return value * NANOS_PER_MS;
    }

    private static double jitter(double base) {
        return base * (0.92 + ThreadLocalRandom.current().nextDouble() * 0.16);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
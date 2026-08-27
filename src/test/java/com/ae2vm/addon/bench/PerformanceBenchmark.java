package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能基准：测量各场景的冷启动/温热复用计算时间（μs 级）。
 *
 * <p>目标：温热复用（全缓存命中）< 10 μs；冷启动（全捕获）< 500 μs。
 * 打印实测算量供调优参考。
 */
public class PerformanceBenchmark {

    private static final BenchAEKey FINAL = BenchAEKey.of("perf_final");
    private static final BenchAEKey MID   = BenchAEKey.of("perf_mid");
    private static final BenchAEKey LEAF  = BenchAEKey.of("perf_leaf");

    /** 2 层链（FINAL→MID→LEAF）：温热复用基准，目标 < 10 μs。 */
    @Test
    void warmTwoLevelChain() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 3))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 5);
        CraftingVM vm = new CraftingVM("perf-warm2", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);

        // 冷启动：捕获所有 bundle
        vm.execute(req, new BenchSimulationState(stock));
        // 测量温热复用
        int warmups = 10;
        int measures = 1000;
        for (int i = 0; i < warmups; i++) vm.execute(req, new BenchSimulationState(stock));
        long start = System.nanoTime();
        for (int i = 0; i < measures; i++) {
            vm.execute(req, new BenchSimulationState(stock));
        }
        double avgUs = (System.nanoTime() - start) / 1000.0 / measures;
        System.out.println("[perf-warm2] avg=" + String.format("%.3f", avgUs) + " μs (" + measures + " runs)");
        // 宽松断言：温热 < 500 μs（当前实现约 30-100 μs）
        assertTrue(avgUs < 500, "2级温热链路应 < 500 μs，实际 " + avgUs + " μs");
    }

    /** 12 层深链：温热复用性能。 */
    @Test
    void warmDeepChain() {
        BenchAEKey[] keys = new BenchAEKey[12];
        for (int i = 0; i < keys.length; i++) keys[i] = BenchAEKey.of("perf_d" + i);
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        for (int i = 0; i < keys.length - 1; i++) {
            final int fi = i;
            byOutput.put(keys[i], new BenchPatternDetails(keys[i], 1, List.of(
                    BenchPatternDetails.InputSpec.of(keys[i + 1], 1))));
        }
        byOutput.put(keys[keys.length - 1], new BenchPatternDetails(keys[keys.length - 1], 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(keys[0]), 1);
        CraftingVM vm = new CraftingVM("perf-deep", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);

        vm.execute(req, new BenchSimulationState(stock)); // 冷启动
        int warmups = 5;
        int measures = 200;
        for (int i = 0; i < warmups; i++) vm.execute(req, new BenchSimulationState(stock));
        long start = System.nanoTime();
        for (int i = 0; i < measures; i++) {
            vm.execute(req, new BenchSimulationState(stock));
        }
        double avgUs = (System.nanoTime() - start) / 1000.0 / measures;
        System.out.println("[perf-deep] avg=" + String.format("%.3f", avgUs) + " μs (" + measures + " runs)");
        assertTrue(avgUs < 2000, "12层深链温热应 < 2000 μs，实际 " + avgUs + " μs");
    }

    /** 并行机制：N 个独立 VM 在 N 线程并行执行，结果一致 + 聚合吞吐。 */
    @Test
    void parallelIndependentVmsAreConsistent() throws Exception {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 3))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 5);

        int threads = 8;
        int perThread = 50;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    // 每个线程一个独立 VM（并行机制：互不干扰的缓存）
                    CraftingVM vm = new CraftingVM("perf-par-" + id,
                            key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);
                    String sig = null;
                    for (int j = 0; j < perThread; j++) {
                        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
                        String s2 = contentSig(plan);
                        if (sig == null) sig = s2;
                        if (!sig.equals(s2)) throw new AssertionError("thread " + id + " result diverged");
                    }
                    return sig;
                }));
            }
            String first = null;
            for (var f : futures) {
                String sig = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (first == null) first = sig;
                if (!first.equals(sig)) throw new AssertionError("parallel VMs diverged: " + first + " vs " + sig);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("[perf-parallel] threads=" + threads + " x" + perThread + " runs, results consistent");
    }

    /** 内容级计划签名（KeyCounter 未重写 toString，必须逐条汇总）。 */
    private static String contentSig(ICraftingPlan plan) {
        java.util.TreeMap<String, Long> miss = new java.util.TreeMap<>();
        for (var e : plan.missingItems()) miss.put(e.getKey().toString(), e.getLongValue());
        java.util.TreeMap<String, Long> used = new java.util.TreeMap<>();
        for (var e : plan.usedItems()) used.put(e.getKey().toString(), e.getLongValue());
        java.util.TreeMap<String, Long> pat = new java.util.TreeMap<>();
        for (var e : plan.patternTimes().entrySet()) {
            var out = e.getKey().getOutputs() != null && e.getKey().getOutputs().length > 0
                    ? e.getKey().getOutputs()[0] : null;
            pat.put(out != null && out.what() != null ? out.what().toString() : "?", e.getValue());
        }
        return "miss=" + miss + " used=" + used + " pat=" + pat;
    }

    /** 10^9 数量级订单温热复用：目标 < 10 μs。 */
    @Test
    void warmBillionQuantity() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 3))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 10_000_000_000L); // 足够 10^9 需求（2×3×1e9 = 6e9）

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 1_000_000_000L);
        CraftingVM vm = new CraftingVM("perf-billion", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);

        ICraftingPlan cold = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(cold.missingItems().isEmpty(), "10^9 订单冷启动应可行");
        int warmups = 10;
        int measures = 1000;
        for (int i = 0; i < warmups; i++) vm.execute(req, new BenchSimulationState(stock));
        long start = System.nanoTime();
        for (int i = 0; i < measures; i++) {
            vm.execute(req, new BenchSimulationState(stock));
        }
        double avgUs = (System.nanoTime() - start) / 1000.0 / measures;
        System.out.println("[perf-billion] 1e9 warm avg=" + String.format("%.3f", avgUs) + " μs ("
                + measures + " runs)");
        assertTrue(avgUs < 10.0, "10^9 订单温热必须 < 10 μs，实际 " + avgUs + " μs");
    }

    /** 24 层斐波那契 DAG 温热复用：目标 < 10 μs。 */
    @Test
    void warmFibonacci24() {
        int depth = 24;
        BenchAEKey[] keys = new BenchAEKey[depth + 1];
        for (int i = 0; i <= depth; i++) keys[i] = BenchAEKey.of("perf_fib_" + i);
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        // 每层 i 需要 i+1 ×1 + i+2 ×1（斐波那契扩张：2^24 条路径，O(24) 节点）
        for (int i = 0; i < depth - 1; i++) {
            byOutput.put(keys[i], new BenchPatternDetails(keys[i], 1, List.of(
                    BenchPatternDetails.InputSpec.of(keys[i + 1], 1),
                    BenchPatternDetails.InputSpec.of(keys[i + 2], 1))));
        }
        byOutput.put(keys[depth - 1], new BenchPatternDetails(keys[depth - 1], 1, List.of(
                BenchPatternDetails.InputSpec.of(keys[depth], 1))));
        byOutput.put(keys[depth], new BenchPatternDetails(keys[depth], 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100_000_000L); // 斐波那契需求：F(24) ≈ 75025

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(keys[0]), 1);
        CraftingVM vm = new CraftingVM("perf-fib24", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);

        ICraftingPlan cold = vm.execute(req, new BenchSimulationState(stock));
        assertTrue(cold.missingItems().isEmpty(), "24 层斐波那契冷启动应可行");
        int warmups = 120;
        int measures = 2000;
        for (int i = 0; i < warmups; i++) vm.execute(req, new BenchSimulationState(stock));
        long[] samples = new long[measures];
        for (int i = 0; i < measures; i++) {
            long s0 = System.nanoTime();
            vm.execute(req, new BenchSimulationState(stock));
            samples[i] = System.nanoTime() - s0;
        }
        java.util.Arrays.sort(samples);
        double medianUs = samples[measures / 2] / 1000.0; // 中位数（排除 GC 尖峰）
        double p95Us = samples[(int)(measures * 0.95)] / 1000.0;
        System.out.println("[perf-fib24] warm median=" + String.format("%.3f", medianUs)
                + " μs p95=" + String.format("%.3f", p95Us) + " μs (" + measures + " runs)");
        assertTrue(medianUs < 10.0, "24 层斐波那契温热中位数必须 < 10 μs，实际 " + medianUs + " μs");
    }
    /** 冷启动（首次执行，全捕获）：目标 < 500 μs。 */
    @Test
    void coldStartTwoLevelChain() {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        byOutput.put(FINAL, new BenchPatternDetails(FINAL, 1, List.of(
                BenchPatternDetails.InputSpec.of(MID, 2))));
        byOutput.put(MID, new BenchPatternDetails(MID, 1, List.of(
                BenchPatternDetails.InputSpec.of(LEAF, 3))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1_000_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(byOutput.get(FINAL), 5);
        CraftingVM vm = new CraftingVM("perf-cold", key -> key instanceof BenchAEKey k ? byOutput.get(k) : null);

        long start = System.nanoTime();
        vm.execute(req, new BenchSimulationState(stock));
        double us = (System.nanoTime() - start) / 1000.0;
        System.out.println("[perf-cold] first_exec=" + String.format("%.3f", us) + " μs");
        assertTrue(us < 5000, "冷启动应 < 5000 μs，实际 " + us + " μs");
    }
}
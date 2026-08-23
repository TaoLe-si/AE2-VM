package com.ae2vm.addon.compat;

import appeng.api.crafting.IPatternDetails;
import com.ae2vm.addon.bench.BenchAEKey;
import com.ae2vm.addon.bench.BenchPatternDetails;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (v1.12.18 EAE+ ADAPT) EAE+ 智能翻倍重分批算术的离线单测。
 *
 * <p>VM-GTL 测试类路径上没有 EAE+ jar（软加载原则），因此这里只测
 * {@link EAESmartDoublingCompat#computeBatches} / {@link EAESmartDoublingCompat#getSuperMatrixDispatchSize}
 * 两个纯算术函数（与 EAE+ {@code CraftingSimulationStateMixin} 的算法逐行镜像），
 * 以及「EAE+ 不可用时 rebatch 原样返回输入」的软失败行为。真正携带
 * ScaledProcessingPattern 的计划输出由游戏内集成验证（latest.log 的
 * patternTimes 里会出现 Scaled[Mult=N] 条目、CPU 按大单推送）。
 */
class EAESmartDoublingCompatTest {

    private static long sumProducts(List<EAESmartDoublingCompat.Batch> batches) {
        long s = 0;
        for (var b : batches) {
            s += b.multiplier * b.count;
        }
        return s;
    }

    /** 无限制 + 未开轮询 → 一次全量（EAE+ 默认行为）。 */
    @Test
    void unlimitedNoRoundRobinIsSingleFullBatch() {
        var b = EAESmartDoublingCompat.computeBatches(10, 0, false, 0);
        assertEquals(1, b.size());
        assertEquals(10, b.get(0).multiplier);
        assertEquals(1, b.get(0).count);
        assertEquals(10, sumProducts(b));
    }

    /** 无限制 + 轮询（3 个供应器）→ base+1 与 base 均分（EAE+ 精确镜像）。 */
    @Test
    void unlimitedRoundRobinSplitsAcrossProviders() {
        var b = EAESmartDoublingCompat.computeBatches(10, 0, true, 3);
        assertEquals(2, b.size());
        // base = 10/3 = 3, remainder = 1 → (3+1)×1 + 3×2
        assertEquals(4, b.get(0).multiplier);
        assertEquals(1, b.get(0).count);
        assertEquals(3, b.get(1).multiplier);
        assertEquals(2, b.get(1).count);
        assertEquals(10, sumProducts(b));
    }

    /** 有上限 → full + remainder 拆分。 */
    @Test
    void limitedSplitsFullAndRemainder() {
        var b = EAESmartDoublingCompat.computeBatches(10, 4, false, 0);
        assertEquals(2, b.size());
        assertEquals(4, b.get(0).multiplier);
        assertEquals(2, b.get(0).count);
        assertEquals(2, b.get(1).multiplier);
        assertEquals(1, b.get(1).count);
        assertEquals(10, sumProducts(b));
    }

    /** 有上限且恰好整除 → 单一条目。 */
    @Test
    void limitedExactMultiple() {
        var b = EAESmartDoublingCompat.computeBatches(12, 4, false, 0);
        assertEquals(1, b.size());
        assertEquals(4, b.get(0).multiplier);
        assertEquals(3, b.get(0).count);
        assertEquals(12, sumProducts(b));
    }

    /** 轮询但供应器 ≤1 → 退化为单份全量（EAE+ Math.max(...,1) 语义）。 */
    @Test
    void roundRobinWithSingleProviderDegradesToFullBatch() {
        var b = EAESmartDoublingCompat.computeBatches(7, 0, true, 1);
        assertEquals(1, b.size());
        assertEquals(7, b.get(0).multiplier);
        assertEquals(1, b.get(0).count);
    }

    /** 轮询且 totalAmount < providerCount → 只激活 totalAmount 台（EAE+ 钳制）。 */
    @Test
    void roundRobinClampsProviderCountToTotalAmount() {
        var b = EAESmartDoublingCompat.computeBatches(2, 0, true, 5);
        // providerCount 钳制为 2 → base=1, remainder=0 → (1,2)
        assertEquals(1, b.size());
        assertEquals(1, b.get(0).multiplier);
        assertEquals(2, b.get(0).count);
        assertEquals(2, sumProducts(b));
    }

    /** super matrix 分包大小 = ceil(total/10)。 */
    @Test
    void superMatrixDispatchSizeIsCeilOfTenth() {
        assertEquals(1, EAESmartDoublingCompat.getSuperMatrixDispatchSize(1));
        assertEquals(1, EAESmartDoublingCompat.getSuperMatrixDispatchSize(10));
        assertEquals(2, EAESmartDoublingCompat.getSuperMatrixDispatchSize(11));
        assertEquals(10, EAESmartDoublingCompat.getSuperMatrixDispatchSize(100));
    }

    /**
     * 穷举（总量 × 上限 × 轮询 × 供应器数）：任何输入下重分批的
     * Σ multiplier × count 必须恰好等于 totalAmount —— 这是「不破坏总量
     * 正确性」的硬约束（Scaled(P,n)×k ≡ P×(k·n)）。
     */
    @Test
    void totalsAreAlwaysPreserved() {
        long[] amounts = {2, 3, 5, 7, 10, 11, 100, 999, 1_000_000};
        long[] limits = {0, 1, 2, 4, 10, 64};
        for (long amt : amounts) {
            for (long lim : limits) {
                for (boolean rr : new boolean[]{false, true}) {
                    for (int pc : new int[]{1, 2, 3, 5, 7}) {
                        List<EAESmartDoublingCompat.Batch> b = EAESmartDoublingCompat.computeBatches(amt, lim, rr, pc);
                        assertFalse(b.isEmpty(), "no batches for amt=" + amt + " lim=" + lim + " rr=" + rr + " pc=" + pc);
                        assertEquals(amt, sumProducts(b),
                                "totals must be preserved amt=" + amt + " lim=" + lim + " rr=" + rr + " pc=" + pc);
                        for (var x : b) {
                            assertTrue(x.multiplier > 0, "multiplier must be >0: " + x.multiplier);
                            assertTrue(x.count > 0, "count must be >0: " + x.count);
                        }
                    }
                }
            }
        }
    }

    /** 软失败：EAE+ 未在类路径 → rebatch 必须原样返回输入（引用相同）。 */
    @Test
    void softFailWithoutEAEPlusReturnsInputUnchanged() {
        // 空 map
        Map<IPatternDetails, Long> empty = new HashMap<>();
        assertSame(empty, EAESmartDoublingCompat.rebatch(empty, null));

        // 非空 map（含普通样板条目）→ 也原样返回
        Map<IPatternDetails, Long> in = new HashMap<>();
        in.put(new BenchPatternDetails(BenchAEKey.of("x"), 1, List.of()), 5L);
        Map<IPatternDetails, Long> out = EAESmartDoublingCompat.rebatch(in, null);
        assertSame(in, out);
    }
}

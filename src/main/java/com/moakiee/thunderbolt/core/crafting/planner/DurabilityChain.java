package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;

/**
 * A durability tool's degradation chain {@code A(n) → A(n-1) → … → broken}, built once by stepping a
 * "remaining" function (AE2's {@code getRemainingKey}) from the full tool until it breaks.
 *
 * <p>The per-use rule {@code 1·A(d) + 1·B → 1·C + A(d-1)} can only be matched one durability point at a
 * time, but once the chain is known it reduces to the closed form a planner can batch:
 * <ul>
 *   <li>a full tool survives {@code n} uses (= chain length),</li>
 *   <li>stock contributes {@code Σ (remaining durability) × count} uses (链长×数量, partial tools
 *       included),</li>
 *   <li>consuming {@code k} uses translates back to concrete tools drained <b>most-degraded first</b>.</li>
 * </ul>
 *
 * <p>This is pure: the AE2 adapter supplies the {@code remaining} and {@code stock} lambdas; everything
 * here (building the chain, deriving {@code n}, aggregating uses, degraded-first withdrawal) is engine
 * logic and unit-tested with plain keys.
 *
 * @param <K> item key type (AE2's AEKey, or String in tests)
 */
public final class DurabilityChain<K> {

    private final List<K> links;       // links[0] = full tool, links[i] = tool after i uses (n-i left)
    private final long n;              // uses a full tool survives = chain length
    private final long[] stockPerLink;
    private final long totalUses;

    private DurabilityChain(List<K> links, long[] stockPerLink, long totalUses) {
        this.links = List.copyOf(links);
        this.n = links.size();
        this.stockPerLink = stockPerLink;
        this.totalUses = totalUses;
    }

    /** Chain links, full tool first; {@code links.get(i)} has {@code n - i} uses remaining. */
    public List<K> links() {
        return links;
    }

    /** Uses a full tool survives = chain length. */
    public long n() {
        return n;
    }

    /** Aggregate uses available from stock across all links (链长×数量, saturating). */
    public long totalUses() {
        return totalUses;
    }

    /** The full tool; doubles as the token-carrying key in the core graph. */
    public K carrier() {
        return links.get(0);
    }

    /**
     * Build the chain by stepping {@code remaining} from {@code full} until it breaks, reading each
     * level's stock via {@code stock}.
     *
     * <p>{@code remaining} must return {@code null} when the tool breaks <em>or</em> when it would step
     * out of the tool's own item group (e.g. a bucket degrading into a different item) — so a container
     * is naturally rejected here. Returns {@code null} when this is not a reducible durability tool:
     * not degrading at all, single-use / container-like, or a chain longer than {@code maxSteps}.
     *
     * @param full      the full (or template) tool key
     * @param remaining next-more-degraded key, or {@code null} at the end of the chain / out of group
     * @param stock     available count of a given exact key
     * @param maxSteps  chain-length budget (e.g. 8192); longer chains decline to the host's slow path
     */
    public static <K> DurabilityChain<K> build(K full, Function<K, K> remaining,
                                               Function<K, Long> stock, long maxSteps) {
        if (full == null || remaining.apply(full) == null) {
            return null; // not a degrading input at all (or already broken)
        }
        List<K> links = new ArrayList<>();
        Set<K> guard = new HashSet<>();
        K cur = full;
        while (cur != null && guard.add(cur)) {
            PlanningCancellation.check();
            links.add(cur);
            cur = remaining.apply(cur);
            if (links.size() > maxSteps) {
                return null; // past the cyclic budget -> caller declines (超步报缺失)
            }
        }
        if (links.size() < 2) {
            return null; // single-use / container-like: not worth reducing
        }

        long n = links.size();
        long[] stockPerLink = new long[(int) n];
        long totalUses = 0;
        for (int i = 0; i < n; i++) {
            PlanningCancellation.check();
            long cnt = Math.max(0L, stock.apply(links.get(i)));
            stockPerLink[i] = cnt;
            totalUses = Sat.add(totalUses, Sat.mul(cnt, n - i)); // a tool at index i has n-i uses left
        }
        return new DurabilityChain<>(links, stockPerLink, totalUses);
    }

    /**
     * Translate {@code uses} consumed from stock into concrete tools, draining the most-degraded
     * variants first ("库存残缺优先使用") and reporting each {@code (key, toolCount)} to {@code sink}.
     *
     * <p>A tool at chain index {@code i} covers {@code n - i} uses; the last tool drawn may be only
     * partially spent (it returns to the network one step more degraded), so this can over-report by at
     * most one partial tool — sound for reservation. {@code uses} must be {@code ≤ totalUses}.
     */
    public void chargeFromStock(long uses, ObjLongConsumer<K> sink) {
        long remaining = uses;
        for (int i = links.size() - 1; i >= 0 && remaining > 0; i--) {
            PlanningCancellation.check();
            long perTool = n - i; // uses left in a tool at this degradation level
            long have = stockPerLink[i];
            if (have <= 0 || perTool <= 0) {
                continue;
            }
            long toolsNeeded = Math.min(have, Sat.ceilDiv(remaining, perTool));
            sink.accept(links.get(i), toolsNeeded);
            remaining -= Sat.mul(toolsNeeded, perTool); // may overshoot on the last (partial) tool
        }
    }
}

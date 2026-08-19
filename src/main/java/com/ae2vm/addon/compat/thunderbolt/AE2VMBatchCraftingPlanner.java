package com.ae2vm.addon.compat.thunderbolt;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;

/**
 * AE2 VM 引擎 —— 通过 {@link com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines}
 * 注册到 Thunderbolt-Core 的多引擎体系中。
 *
 * <p>玩家通过 {@code /thunderbolt engine ae2vm} 选中本引擎后，Thunderbolt 会把每个
 * 合成计算请求转发到 {@link #createSession} 返回的 session，由 session 的
 * {@link Session#attempt} 调用 VM 计算。如果 VM 无法处理则返回 {@link PlanningAttempt#DECLINE}，
 * Thunderbolt 会尝试下一个引擎（最终回退到原生 AE2）。
 *
 * <p>本类作为 {@link CraftingPlanningEngine} 实现，仅在 Thunderbolt-Core 已安装且玩家
 * 选中了 ae2vm 时才被调用；未安装 Thunderbolt 时本类不会被加载。
 */
public final class AE2VMBatchCraftingPlanner implements CraftingPlanningEngine {

    /** Thunderbolt 多引擎体系中的引擎 ID（与旧版 {@code CraftingEngine} 接口的 ENGINE_ID 一致）。 */
    public static final String ENGINE_ID = "ae2vm";

    /** 单例实例，供 {@link com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines#register} 使用。 */
    public static final AE2VMBatchCraftingPlanner INSTANCE = new AE2VMBatchCraftingPlanner();

    private AE2VMBatchCraftingPlanner() {
    }

    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("ae2vm", ENGINE_ID);
    }

    @Override
    public Component getName() {
        return Component.translatable("algorithm.ae2vm.vm_engine");
    }

    @Override
    public boolean check(IGrid grid, PlanningRequest request) {
        // VM 引擎总是愿意接受请求（无法处理时在 attempt() 中返回 DECLINE）。
        // 这里只做最基本的校验：需要有效的请求者和网格节点。
        return request.requester() != null
                && request.requester().getGridNode() != null
                && request.requester().getGridNode().getGrid() == grid
                && request.requestedAmount() > 0
                && request.output() != null;
    }

    @Override
    public PlanningEngineSession createSession(IGrid grid, PlanningRequest request) {
        return new Session(grid, request);
    }

    /**
     * 每计算一次创建一个 session。Session 持有请求级别上下文，
     * 在 {@link #attempt} 中调用 AE2VMCrafting.calculate() 并同步等待结果。
     */
    private static final class Session implements PlanningEngineSession {
        private final IGrid grid;
        private final PlanningRequest request;

        Session(IGrid grid, PlanningRequest request) {
            this.grid = grid;
            this.request = request;
        }

        @Override
        public PlanningAttempt attempt(long amount, boolean simulate) {
            try {
                var future = AE2VMCrafting.calculate(
                        grid,
                        request.requester(),
                        request.output(),
                        amount,
                        request.strategy());

                // 同步等待结果（最多 5 分钟），与 Thunderbolt 的 watchdog 超时保持一致。
                // Thunderbolt 在 runCraftAttempt 层面有超时保护，这里只做阻塞等待。
                ICraftingPlan plan = future.get(5, TimeUnit.MINUTES);

                if (plan instanceof CraftingPlan cp) {
                    // simulation 标记：AE2VMCrafting 返回的 plan.simulation() 已经正确设置。
                    return PlanningAttempt.handled(cp);
                } else if (plan == null) {
                    // VM 返回 null 表示无法计算（理论上不会发生，calculate 失败会抛异常）。
                    return PlanningAttempt.DECLINE;
                } else {
                    // 非 CraftingPlan 实现（例如 LoopCraftingPlan 等包装类）。
                    return new PlanningAttempt(
                            PlanningAttempt.Status.HANDLED,
                            plan instanceof CraftingPlan ? (CraftingPlan) plan : null,
                            null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AE2VMAddon.LOGGER.warn("[AE2-VM] Planning interrupted for {}x{}", request.output(), amount);
                return PlanningAttempt.DECLINE;
            } catch (ExecutionException | TimeoutException e) {
                AE2VMAddon.LOGGER.warn("[AE2-VM] Planning failed for {}x{}: {}",
                        request.output(), amount, e.toString());
                // VM 计算失败 → DECLINE，让 Thunderbolt 尝试下一个引擎（最终回退到原生 AE2）。
                return PlanningAttempt.DECLINE;
            }
        }
    }
}

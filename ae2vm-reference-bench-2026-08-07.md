# AE2VM 基准检测报告 — MC 1.21.1（主版本）

- **日期**: 2026-08-07
- **版本**: AE2VMAddon **1.10.1**（MC 1.21.1 / NeoForge 21.1.169，JAVA_HOME=corretto-22.0.2）
- **引擎**: AE2VM `CraftingVM`
- **核心修复**: v1.9.13 聚合期模糊组库存聚合（可合成主变体 + 替换变体库存 → 不再假缺失）
- **运行**: `gradlew.bat test --rerun-tasks --no-daemon --console=plain`
- **测试日志**: 下方数据全部为本次真实运行输出（`mdbench.log`），未编造

---

## 总览（准确计数，来自 JUnit XML 报告）

```
测试类: 13    用例: 108    失败: 0    错误: 0    跳过: 0
构建: BUILD SUCCESSFUL
```

| 测试类 | 用例 | 结果 |
|---|---|---|
| Ae2VmReferenceCapabilitySuiteTest（闪电基准） | 34 | ✅ 0 fail |
| Ae2VmBoundaryCapabilitySuiteTest（边界基准） | 38 | ✅ 0 fail |
| CrossRequestCacheTest | 11 | ✅ 0 fail |
| VMTest | 6 | ✅ 0 fail |
| JitReuseTest | 4 | ✅ 0 fail |
| FuzzyGroupRegistrationTest | 3 | ✅ 0 fail |
| FuzzyDiagTest | 3 | ✅ 0 fail |
| FluidBucketBoundaryTest | 2 | ✅ 0 fail |
| QuantityOneBoundaryTest | 2 | ✅ 0 fail |
| StockAwareSubCraftReproTest | 2 | ✅ 0 fail |
| CraftableFluidStockReproTest | 1 | ✅ 0 fail |
| FalsePositiveDiagnosticTest | 1 | ✅ 0 fail |
| VmBridgeSpikeTest | 1 | ✅ 0 fail |

---

## 一、闪电基准测试（Thunderbolt-Core 参考能力，33 例）

> "闪电" = Thunderbolt（雷/闪电）：跑 Thunderbolt-Core 规划器能力参考套件，
> 测 AE2VM 计算速度（elapsedMs）+ 能力表面。11 图族 × 3 材料模式。

- **入口**: `com.ae2vm.addon.bench.Ae2VmReferenceCapabilitySuiteTest`
- **planner**: `Ae2VmReferencePlanner`（String network key —— `realStockOf` 恒 0，不走 stock-aware）
- **时限**: 每例 1s 硬时限 + 100ms 取消宽限（无一例超时）
- **本次运行**: `[reference-capability] SUMMARY cases=33 supported=22 falsePositive=11 engineError=0 timeout=0 totalElapsedMs=77.8`

> ⚠️ 22/11 是既有非确定性波动（`multi-dag/fibonacci/minimum` 与 `unbounded` 在
> 22/11 ↔ 23/10 之间浮动，自 v1.9.6 起记录），稳定基线 23/10。

### 1. 单配方 DAG

| 用例 | 模式 | scale | 状态 | elapsedMs | 缺失 |
|---|---|---|---|---|---|
| single-dag/dispersed | MISSING | 3 | ✅ SUPPORTED | 4.985 | {D=2, G=1} |
| single-dag/dispersed | MINIMUM | 3 | ✅ SUPPORTED | 2.694 | {} |
| single-dag/dispersed | UNBOUNDED | 3 | ✅ SUPPORTED | 2.589 | {} |
| single-dag/fibonacci | MISSING | 32 | ✅ SUPPORTED | 5.117 | {X0=3, X1=5} |
| single-dag/fibonacci | MINIMUM | 32 | ✅ SUPPORTED | 4.261 | {} |
| single-dag/fibonacci | UNBOUNDED | 32 | ✅ SUPPORTED | 4.244 | {} |

### 2. 多配方 DAG

| 用例 | 模式 | scale | 状态 | elapsedMs | 缺失 |
|---|---|---|---|---|---|
| multi-dag/greedy-trap | MISSING | 64 | ✅ SUPPORTED | 2.364 | {S=64} |
| multi-dag/greedy-trap | MINIMUM | 64 | ✅ SUPPORTED | 1.629 | {} |
| multi-dag/greedy-trap | UNBOUNDED | 64 | ✅ SUPPORTED | 1.991 | {} |
| multi-dag/fibonacci | MISSING | 12 | ✅ SUPPORTED | 4.257 | {X0=5, X1=9, X2=7} |
| multi-dag/fibonacci | MINIMUM | 12 | ❌ FALSE_POSITIVE | 4.780 | {X0=5} |
| multi-dag/fibonacci | UNBOUNDED | 12 | ❌ FALSE_POSITIVE | 2.535 | {X0=5} |

### 3. 环裁切

| 用例 | 模式 | scale | 状态 | elapsedMs | 缺失 |
|---|---|---|---|---|---|
| cycle/conversion-ring | MISSING | 3 | ❌ FALSE_POSITIVE | 2.826 | {} |
| cycle/conversion-ring | MINIMUM | 3 | ✅ SUPPORTED | 1.512 | {} |
| cycle/conversion-ring | UNBOUNDED | 3 | ✅ SUPPORTED | 1.981 | {} |
| cycle/self-growth-cut | MISSING | 2 | ✅ SUPPORTED | 1.973 | {A=2} |
| cycle/self-growth-cut | MINIMUM | 2 | ✅ SUPPORTED | 1.750 | {A=1} |
| cycle/self-growth-cut | UNBOUNDED | 2 | ✅ SUPPORTED | 1.897 | {} |

### 4. 催化剂 / 反馈环

| 用例 | 模式 | scale | 状态 | elapsedMs | 缺失 |
|---|---|---|---|---|---|
| catalyst/returned-seed | MISSING | 1000 | ✅ SUPPORTED | 2.245 | {A=1000} |
| catalyst/returned-seed | MINIMUM | 1000 | ❌ FALSE_POSITIVE | 1.739 | {A=999} |
| catalyst/returned-seed | UNBOUNDED | 1000 | ✅ SUPPORTED | 1.484 | {} |
| catalyst/raw-feedback-loop | MISSING | 8 | ❌ FALSE_POSITIVE | 1.969 | {D=8} |
| catalyst/raw-feedback-loop | MINIMUM | 8 | ❌ FALSE_POSITIVE | 1.400 | {D=8} |
| catalyst/raw-feedback-loop | UNBOUNDED | 8 | ❌ FALSE_POSITIVE | 1.847 | {D=8} |
| catalyst/lossy-feedback-loop | MISSING | 8 | ✅ SUPPORTED | 1.624 | {A=16} |
| catalyst/lossy-feedback-loop | MINIMUM | 8 | ❌ FALSE_POSITIVE | 1.626 | {A=14} |
| catalyst/lossy-feedback-loop | UNBOUNDED | 8 | ✅ SUPPORTED | 1.614 | {} |

### 5. 耐久链

| 用例 | 模式 | scale | 状态 | elapsedMs | 缺失 |
|---|---|---|---|---|---|
| durability/finite-use-chain | MISSING | 100 | ✅ SUPPORTED | 1.888 | {tool=9901} |
| durability/finite-use-chain | MINIMUM | 100 | ❌ FALSE_POSITIVE | 1.704 | {tool=9900} |
| durability/finite-use-chain | UNBOUNDED | 100 | ✅ SUPPORTED | 1.056 | {} |

### 6. 模糊 / 可复用库存

| 用例 | 模式 | scale | 状态 | elapsedMs | 缺失 |
|---|---|---|---|---|---|
| fuzzy/variant-route | MISSING | 1000 | ✅ SUPPORTED | 1.614 | {logical_tool=1000} |
| fuzzy/variant-route | MINIMUM | 1000 | ❌ FALSE_POSITIVE | 1.544 | {logical_tool=1000} |
| fuzzy/variant-route | UNBOUNDED | 1000 | ❌ FALSE_POSITIVE | 1.108 | {logical_tool=1000} |

**性能基准（闪电基准 elapsedMs）**: 全部用例 1.056 – 5.117 ms；稳态单例 1–5 ms，无一例触碰 1s 时限。FALSE_POSITIVE 均为预期能力边界（催化剂/耐久/可复用/多样板/换算环差分概念），非引擎错误。

---

## 二、边界能力基准（37 例，本次实际运行）

- **入口**: `com.ae2vm.addon.bench.Ae2VmBoundaryCapabilitySuiteTest`
- **驱动**: 真实 `FakeBenchGrid`（IGrid）→ 激活 `realStockOf` 的 stock-aware 聚合 + 模糊组替换变体库存路径
- **守护**: 玩家 NAST 报告「合成 1 个/1b 缺失，但 2 个/100b 正常」+「有样板却报缺失」
- **本次运行**: `[reference-boundary] SUMMARY cases=37 ok=37 feasible=36 totalElapsedMs=110`

37/37 全部与期望可行性一致（36 可行 + 1 不可行 sanity）。逐例全 OK，缺失全部符合期望（仅 `infeasible-no-variant-stock` 报 `{gray_wool=1}`）。

| 用例 | 请求量 | 期望 | 实际 | 缺失 |
|---|---|---|---|---|
| quantity/craftable-primary-white-stock | 1/2/100 | ✅ | ✅✅✅ | {} |
| quantity/craftable-primary-white-stock10 | 1/2/100 | ✅ | ✅✅✅ | {} |
| quantity/craftable-primary-partial-gray | 1/2/100 | ✅ | ✅✅✅ | {} |
| quantity/craftable-primary-no-variant-stock | 1/2/100 | ✅ | ✅✅✅ | {} |
| quantity/fuzzy-leaf-white-stock | 1/2/100 | ✅ | ✅✅✅ | {} |
| quantity/deep-chain-mid-stock-l10 (s0/s1/s5) | 1/2/100 | ✅ | 全 ✅ | {} |
| quantity/deep-chain-mid-stock-l20 (s0/s1/s5) | 1/2/100 | ✅ | 全 ✅ | {} |
| quantity/craftable-fluid-partial | 1/2/100 | ✅ | ✅✅✅ | {} |
| quantity/infeasible-no-variant-stock | 1 | ❌ | ❌ | {gray_wool=1} |

**v1.9.13 修复本体验证**: 修复前 `craftable-primary-white-stock amt=100 (white=1)` → `missing={white_wool=99}`（有样板却报缺失）；修复后 → `missing={}`，白色库存 1 满足 1 槽位、其余 99 由灰色羊毛合成。

---

## 三、回归测试（108 例全过）

| 测试类 | 用例 | 覆盖点 |
|---|---|---|
| CrossRequestCacheTest | 11 | 跨请求 VM 复用确定性、库存下降、deepFib24、diamond、空库存、独立 VM 隔离 |
| VMTest | 6 | 字节码基础 opcode（DIV_ROUNDUP/MUL/CALL_BY_KEY 等） |
| JitReuseTest | 4 | JIT bundleCache 复用、库存刷新、可合成子项真实库存重读 |
| FuzzyGroupRegistrationTest | 3 | 模糊组注册、精确拒绝替代、可合成主变体部分库存 |
| FuzzyDiagTest | 3 | 模糊灰羊毛 / 可合成主变体诊断 |
| FluidBucketBoundaryTest | 2 | 可合成/库存流体 x1/x2（1000mB） |
| QuantityOneBoundaryTest | 2 | fib12 x1/x2、部分库存 x1/x2（无假缺失） |
| StockAwareSubCraftReproTest | 2 | stock-aware 子项部分库存最后一份必发 |
| CraftableFluidStockReproTest | 1 | 可合成流体 + 部分库存 → 缺口合成 |
| FalsePositiveDiagnosticTest | 1 | 逐例打印非 SUPPORTED 场景完整计划 |
| VmBridgeSpikeTest | 1 | AE2 类加载 + 离线 VM 冒烟 |

---

## 结论

- **108 用例全过，0 失败 0 错误**，BUILD SUCCESSFUL。
- **闪电基准**（参考能力）33 例稳定（22/11 ↔ 23/10 为既有 multi-dag/fibonacci 波动），能力表面未退化。
- **边界基准** 37/37 通过 —— v1.9.13 模糊组库存聚合修复完整守护。
- **性能基准**：闪电基准 elapsedMs 稳态 1–5 ms；边界基准 totalElapsedMs=110 ms。

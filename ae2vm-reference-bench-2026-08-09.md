# AE2VM 基准检测报告 — MC 1.21.1（主版本）2026-08-09

- **日期**: 2026-08-09（重写，数据取自 18:58 全量测试重跑 + 闪电基准连跑 3 次）
- **版本**: AE2VMAddon **1.10.2**（MC 1.21.1 / NeoForge 21.1.169，JAVA_HOME=corretto-22.0.2）
- **引擎**: AE2VM `CraftingVM`
- **核心修复（v1.10.x）**:
  1. **处理配方默认模糊匹配**：GTL 温室假合成 / 神秘农业精华的"材料缺失但不知道哪里缺失"——处理配方输入按物品完整模糊族（同物品任意 NBT）匹配网络库存。
  2. **催化剂反馈环 working-capital**：`raw-feedback-loop` / `lossy-feedback-loop` 的副产物闭环 + 种子（working capital）计算 → `catalyst/*` 全 9 例 SUPPORTED。
  3. **耐久工具支持**：有限次使用工具（`amount × ceil(times/uses)` 闭式，"成环差分"）→ `durability/*` 全 3 例 SUPPORTED。
- **运行**: `gradlew.bat cleanTest test --no-daemon`（未编译 mod，纯场景/回归测试）
- **测试日志**: 下方数据全部为本次真实运行输出（`bench-run-0809b.out` + `build/test-results/test/*.xml`），未编造

---

## 总览（准确计数，来自 JUnit XML 报告 + 本次全量运行）

```
测试类: 16    用例: 119    失败: 0    错误: 0    跳过: 0
构建: BUILD SUCCESSFUL
闪电基准（本次 18:58 全量运行）: cases=33 supported=28 falsePositive=5 engineError=0 timeout=0 totalElapsedMs=80.3
边界基准: cases=37 ok=37 feasible=36 totalElapsedMs=117
```

| 测试类 | 用例 | 结果 |
|---|---|---|
| Ae2VmReferenceCapabilitySuiteTest（闪电基准） | 34 | ✅ 0 fail |
| Ae2VmBoundaryCapabilitySuiteTest（边界基准） | 38 | ✅ 0 fail |
| CatalystFeedbackLoopTest（催化剂场景） | 6 | ✅ 0 fail |
| CrossRequestCacheTest | 11 | ✅ 0 fail |
| DurabilityToolTest（耐久场景） | 3 | ✅ 0 fail |
| VMTest | 6 | ✅ 0 fail |
| JitReuseTest | 4 | ✅ 0 fail |
| FuzzyGroupRegistrationTest | 3 | ✅ 0 fail |
| FuzzyDiagTest | 3 | ✅ 0 fail |
| FluidBucketBoundaryTest | 2 | ✅ 0 fail |
| QuantityOneBoundaryTest | 2 | ✅ 0 fail |
| StockAwareSubCraftReproTest | 2 | ✅ 0 fail |
| ProcessingDefaultFuzzyTest（处理配方模糊） | 2 | ✅ 0 fail |
| CraftableFluidStockReproTest | 1 | ✅ 0 fail |
| FalsePositiveDiagnosticTest | 1 | ✅ 0 fail |
| VmBridgeSpikeTest | 1 | ✅ 0 fail |

---

## 一、闪电基准测试（Thunderbolt-Core 参考能力，33 例）

> "闪电" = Thunderbolt（雷/闪电）：跑 Thunderbolt-Core 规划器能力参考套件，
> 测 AE2VM 计算速度（elapsedMs + VM 自身 calc time）+ 能力表面。11 图族 × 3 材料模式。

- **入口**: `com.ae2vm.addon.bench.Ae2VmReferenceCapabilitySuiteTest`
- **planner**: `Ae2VmReferencePlanner`（String network key —— `realStockOf` 恒 0，不走 stock-aware）
- **时限**: 每例 1s 硬时限 + 100ms 取消宽限（无一例超时）
- **本次 18:58 全量运行**:
  ```
  [reference-capability] SUMMARY cases=33 supported=28 falsePositive=5 engineError=0 timeout=0 totalElapsedMs=80.3
  ```

> ⚠️ **既有非确定性波动（multi-dag/fibonacci min ↔ unbounded）**：闪电基准连跑 3 次得
> **29/4, 28/5, 29/4** —— 稳定基线 **29 SUPPORTED / 4 FALSE_POSITIVE**。波动仅在
> `multi-dag/fibonacci/minimum` 与 `unbounded`（多样板最优选择，自 v1.9.6 起记录）；
> 下表为 18:58 本次运行数据（该次恰好命中 28/5 一侧）。
> **催化剂（9/9）与耐久（3/3）在所有运行中均稳定 SUPPORTED。**

### 1. 单配方 DAG

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs | VM calc |
|---|---|---|---|---|---|---|
| single-dag/dispersed | MISSING | 3 | ✅ SUPPORTED | {D=2, G=1} | 4.793 | 1.30 ms |
| single-dag/dispersed | MINIMUM | 3 | ✅ SUPPORTED | {} | 1.754 | 0.39 ms |
| single-dag/dispersed | UNBOUNDED | 3 | ✅ SUPPORTED | {} | 1.658 | 0.42 ms |
| single-dag/fibonacci | MISSING | 32 | ✅ SUPPORTED | {X0=3, X1=5} | 6.320 | 4.15 ms |
| single-dag/fibonacci | MINIMUM | 32 | ✅ SUPPORTED | {} | 6.275 | 3.00 ms |
| single-dag/fibonacci | UNBOUNDED | 32 | ✅ SUPPORTED | {} | 4.856 | 2.97 ms |

### 2. 多配方 DAG

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs | VM calc |
|---|---|---|---|---|---|---|
| multi-dag/greedy-trap | MISSING | 64 | ✅ SUPPORTED | {S=64} | 2.541 | 1.16 ms |
| multi-dag/greedy-trap | MINIMUM | 64 | ✅ SUPPORTED | {} | 2.997 | 1.22 ms |
| multi-dag/greedy-trap | UNBOUNDED | 64 | ✅ SUPPORTED | {} | 1.761 | 0.31 ms |
| multi-dag/fibonacci | MISSING | 12 | ✅ SUPPORTED | {X0=5, X1=9, X2=7} | 3.041 | 1.48 ms |
| multi-dag/fibonacci | MINIMUM | 12 | ⚠️ 本次 FP（波动） | {X0=5} | 2.640 | 1.26 ms |
| multi-dag/fibonacci | UNBOUNDED | 12 | ⚠️ 本次 FP（波动） | {X0=5} | 4.400 | 2.86 ms |

### 3. 环裁切

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs | VM calc |
|---|---|---|---|---|---|---|
| cycle/conversion-ring | MISSING | 3 | ❌ FALSE_POSITIVE | {} | 1.796 | 0.32 ms |
| cycle/conversion-ring | MINIMUM | 3 | ✅ SUPPORTED | {} | 1.685 | 0.28 ms |
| cycle/conversion-ring | UNBOUNDED | 3 | ✅ SUPPORTED | {} | 2.046 | 0.37 ms |
| cycle/self-growth-cut | MISSING | 2 | ✅ SUPPORTED | {A=2} | 1.822 | 0.61 ms |
| cycle/self-growth-cut | MINIMUM | 2 | ✅ SUPPORTED | {A=1} | 2.551 | 1.09 ms |
| cycle/self-growth-cut | UNBOUNDED | 2 | ✅ SUPPORTED | {} | 1.843 | 0.06 ms |

### 4. 催化剂 / 反馈环（v1.10.x 全修复 → 9/9 SUPPORTED，稳定）

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs | VM calc |
|---|---|---|---|---|---|---|
| catalyst/returned-seed | MISSING | 1000 | ✅ SUPPORTED | {A=1} | 2.588 | 1.41 ms |
| catalyst/returned-seed | MINIMUM | 1000 | ✅ SUPPORTED | {} | 1.533 | 0.31 ms |
| catalyst/returned-seed | UNBOUNDED | 1000 | ✅ SUPPORTED | {} | 1.846 | 0.31 ms |
| catalyst/raw-feedback-loop | MISSING | 8 | ✅ SUPPORTED | {A=1} | 2.771 | 1.86 ms |
| catalyst/raw-feedback-loop | MINIMUM | 8 | ✅ SUPPORTED | {} | 1.490 | 0.41 ms |
| catalyst/raw-feedback-loop | UNBOUNDED | 8 | ✅ SUPPORTED | {} | 1.120 | 0.29 ms |
| catalyst/lossy-feedback-loop | MISSING | 8 | ✅ SUPPORTED | {A=2} | 1.408 | 0.59 ms |
| catalyst/lossy-feedback-loop | MINIMUM | 8 | ✅ SUPPORTED | {} | 1.222 | 0.26 ms |
| catalyst/lossy-feedback-loop | UNBOUNDED | 8 | ✅ SUPPORTED | {} | 1.300 | 0.30 ms |

### 5. 耐久链（v1.10.x 新增支持 → 3/3 SUPPORTED，稳定）

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs | VM calc |
|---|---|---|---|---|---|---|
| durability/finite-use-chain | MISSING | 100 | ✅ SUPPORTED | {tool=1} | 2.226 | 1.27 ms |
| durability/finite-use-chain | MINIMUM | 100 | ✅ SUPPORTED | {} | 2.562 | 0.24 ms |
| durability/finite-use-chain | UNBOUNDED | 100 | ✅ SUPPORTED | {} | 1.189 | 0.14 ms |

### 6. 模糊 / 可复用库存

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs | VM calc |
|---|---|---|---|---|---|---|
| fuzzy/variant-route | MISSING | 1000 | ✅ SUPPORTED | {logical_tool=1} | 1.442 | 0.57 ms |
| fuzzy/variant-route | MINIMUM | 1000 | ❌ FALSE_POSITIVE | {logical_tool=1} | 1.557 | 0.62 ms |
| fuzzy/variant-route | UNBOUNDED | 1000 | ❌ FALSE_POSITIVE | {logical_tool=1} | 1.290 | 0.50 ms |

**性能基准**: 全部 33 例 elapsedMs 1.120 – 6.320 ms；VM 自身 calc time 0.06 – 4.15 ms；
无一例触碰 1s 时限。FALSE_POSITIVE 均为预期能力边界（多样板最优选择 / 换算环差分 /
可复用库存），非引擎错误。

---

## 二、边界能力基准（37 例场景 / 38 用例，本次实际运行）

- **入口**: `com.ae2vm.addon.bench.Ae2VmBoundaryCapabilitySuiteTest`
- **驱动**: 真实 `FakeBenchGrid`（IGrid）→ 激活 `realStockOf` 的 stock-aware 聚合 + 模糊组替换变体库存路径
- **守护**: 玩家 NAST 报告「合成 1 个/1b 缺失，但 2 个/100b 正常」+「有样板却报缺失」
- **本次运行**:
  ```
  [reference-boundary] SUMMARY cases=37 ok=37 feasible=36 totalElapsedMs=117
  ```

37/37 全部与期望可行性一致（36 可行 + 1 不可行 sanity）。逐例全 OK，缺失全部符合期望。

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

---

## 三、v1.10.x 新增场景测试

| 测试类 | 用例 | 覆盖点 |
|---|---|---|
| CatalystFeedbackLoopTest | 6 | 催化剂反馈环：raw/lossy 三模式（MINIMUM/UNBOUNDED 可行无缺失、MISSING 报正确种子缺失 {A=1}/{A=2}） |
| DurabilityToolTest | 3 | 耐久工具：100 uses × 10000 次 → 100 工具可行、99 工具缺 1、无限库存可行 |
| ProcessingDefaultFuzzyTest | 2 | 处理配方默认模糊：不同 NBT 变体满足槽位（GTL 温室/MA 精华）、无变体真缺失 |

---

## 四、回归测试（119 例全过）

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

## 五、修复明细

| 修复 | 实现 |
|---|---|
| 处理配方默认模糊 | `PatternCompiler.PROCESSING_INPUT_KEYS` 登记处理配方输入；`CraftingVM.fuzzyFamilyOf()` 把处理输入匹配到物品完整模糊族（`findFuzzy(IGNORE_ALL)`），覆盖 CALL_BY_KEY 缺料预标、聚合 stock-aware、EXTRACT 变体实际消耗 |
| 催化剂反馈环 | `CraftingVM.computeFeedbackLoopMissing()`：byproduct-fed SCC 检测 + forward 模拟注入 working capital；`snapshotExecuteStartStock()` 在 execute() 开头快照初始库存（capture 不还原叶子库存） |
| 耐久工具 | `Opcode.DURABILITY_TOOL` + `IFiniteUseInput` 接口；`Bundle.durability` 记录 rate，聚合按 `amount × ceil(total/uses)` 从库存扣、缺口报缺失 |

---

## 六、剩余 FALSE_POSITIVE（非催化剂/耐久，VM 缺整套规划概念）

- `multi-dag/fibonacci/minimum` + `unbounded`（多样板最优选择；**既有非确定性波动**，28/5 ↔ 29/4）
- `cycle/conversion-ring/missing`（换算环差分）
- `fuzzy/variant-route/minimum` + `unbounded`（可复用库存/模糊）

这些需 VM 新增整套规划概念（AE2 原生无耐久/可复用库存/最优多样板概念），实现风险高，
建议按需评估（见 /memories/repo/ae2vm-thunderbolt-reference-bench.md）。

---

## 结论

**稳定基线 29 / 33 SUPPORTED（88%）**（本次运行 28/5，波动仅在 multi-dag/fibonacci）。
催化剂（9/9）与耐久（3/3）能力族本轮全部修复且**稳定**；处理配方默认模糊修复落地。
全部 119 个 VM/闪电/边界/回归用例 0 失败，VM 计算全部 < 4.2 ms/例。

# AE2VM 基准检测报告 — MC 1.20.1（Forge 分支）2026-08-18

- **日期**: 2026-08-18（基准测试已从 1.21.1 **完整迁移到 1.20.1**，本次为 1.20.1 本地真实运行）
- **版本**: AE2VMAddon **1.11.2**（MC 1.20.1 / Forge 47.4.22，JAVA_HOME=D:\Java21）
- **引擎**: AE2VM `CraftingVM`
- **核心修复（v1.10.x，已同步到 1.20.1，与 1.21.1 主版本逻辑一致）**:
  1. **处理配方默认模糊匹配**：GTL 温室假合成 / 神秘农业精华的"材料缺失但不知道哪里缺失"——处理配方输入按物品完整模糊族（同物品任意 NBT）匹配网络库存。
  2. **催化剂反馈环 working-capital**：`raw-feedback-loop` / `lossy-feedback-loop` 的副产物闭环 + 种子（working capital）计算 → `catalyst/*` 全 9 例 SUPPORTED。
  3. **耐久工具支持**：有限次使用工具（`amount × ceil(times/uses)` 闭式，"成环差分"）→ `durability/*` 全 3 例 SUPPORTED。
  4. **递归 / 自引用配方（v1.10.3）**：输出键同时也是自身消耗输入的自引用样板——`A+B→2A` 放大器（每合成净增 +1 A）与 `A+B→A+C` A-A 催化剂/精华（A 既是输入又是副产物输出）。自产出抵消自消耗：自键收敛为一次性种子（seed），主输出自键按净增 `net=out−in` 修正合次数 `ceil((请求−种子库存)/net)`；种子未库存时恰报缺 1 种子、样板不触发。→ `recursion/*` 全 6 例 SUPPORTED。
  5. **换算环守恒（v1.10.3）**：无副产物纯换算环（`9B→A, 1A→9B, 9C→B, 1B→9C`，1A=9B=81C）价值守恒——可换算库存但无法凭空创造。`computeConversionRingMissing()` 用 BigInteger 分数精确求环值并与外部需求比；环值不足时恰报最小价值键缺失。→ `cycle/conversion-ring` 全 3 例 SUPPORTED。
  6. **可复用库存种子模糊（v1.10.3）**：宿主私有可复用库存路由（`returnedFrom`）种子抽取按模糊族匹配变体库存。→ `fuzzy/variant-route` 全 3 例 SUPPORTED。
  7. **翻倍样板兼容（v1.10.8/9）**：UselessMod `ScaledProcessingPattern` 运行时虚拟包装器——`PatternCompiler` 统一经 `unwrapScaled()` 递归解包到原始样板编译，`patternTimes` key 恒为原始 `IPatternDetails`，CPU/provider/furnace 全认识（提交时 UselessMod 重新应用翻倍）。→ `ScaledPatternReproTest` 全 6 例通过。
  8. **蜜脾万象样板 NBT 变体精确匹配（v1.10.10）**：AE2 原生 `AEProcessingPattern` 输入是精确变体（`isValid` = 精确 equals）——`EXACT_PROCESSING_KEYS` 使不同 `bee_type` 蜜脾不串料；第三方处理配方（GTL/MA/omniversal）保持默认模糊。→ `ProductiveBeesReferenceTest` 全 6 例通过。
  9. **新样板刷新识别（v1.11.x PATTERN-REFRESH）**：样板更新（`updatePatterns`）时 `PatternCompiler.bumpPatternVersion()`；复用的 `CraftingVM` 在下次 `execute()` 检测到版本变化即丢弃过期的 JIT `bundleCache`——新写入的中间产物样板立即被识别（"新样板作为中间产物识别不到" bug）。→ `PatternRefreshReuseTest` 通过。
- **运行**: `gradlew.bat cleanTest test --no-daemon`（未编译 mod，纯场景/回归测试；build.gradle 已把 main 的 MC/Forge/AE2 类路径接给 test sourceSet）
- **测试数据说明**: 1.20.1 Forge 分支本次已从 1.21.1 **完整迁移基准测试基础设施**（`com/moakiee/thunderbolt/core/planner` 参考框架 + `com/ae2vm/addon/bench` 全套 21 个测试类），并在 1.20.1 本地跑通完整基准。下方数据全部为 **1.20.1 本地真实运行输出**（`build/test-results/test/*.xml` + 控制台 `[reference-capability]` / `[reference-boundary]` 行）。迁移时仅适配 AE2 1.20.1 API 差异：`IPatternDetails.getOutputs()` 返回数组、`AEKey.toTag()` 无参、`writeToPacket(FriendlyByteBuf)`、`AEKeyType.loadKeyFromTag()`（无 `codec()`）、`IGrid` 无 `export()`、Java 17（无 `Thread.ofPlatform`/`List.getFirst`）。未编造。

---

## 总览（准确计数，来自 JUnit XML 报告 + 本次全量运行）

```
测试类: 21    用例: 150    失败: 0    错误: 0    跳过: 0
构建: BUILD SUCCESSFUL
闪电基准（本次全量运行）: cases=39 supported=38 falsePositive=1 engineError=0 timeout=0 totalElapsedMs=73.6
边界基准: cases=37 ok=37 feasible=36 totalElapsedMs=427
```

| 测试类 | 用例 | 结果 |
|---|---|---|
| Ae2VmReferenceCapabilitySuiteTest（闪电基准） | 40 | ✅ 0 fail |
| Ae2VmBoundaryCapabilitySuiteTest（边界基准） | 38 | ✅ 0 fail |
| CatalystFeedbackLoopTest（催化剂场景） | 6 | ✅ 0 fail |
| RecursionReferenceTest（递归场景） | 6 | ✅ 0 fail |
| CrossRequestCacheTest | 11 | ✅ 0 fail |
| DurabilityToolTest（耐久场景） | 3 | ✅ 0 fail |
| VMTest | 6 | ✅ 0 fail |
| JitReuseTest | 4 | ✅ 0 fail |
| FuzzyGroupRegistrationTest | 3 | ✅ 0 fail |
| FuzzyDiagTest | 3 | ✅ 0 fail |
| FluidBucketBoundaryTest | 2 | ✅ 0 fail |
| QuantityOneBoundaryTest | 2 | ✅ 0 fail |
| StockAwareSubCraftReproTest | 2 | ✅ 0 fail |
| ProcessingDefaultFuzzyTest（处理配方模糊） | 3 | ✅ 0 fail |
| CraftableFluidStockReproTest | 1 | ✅ 0 fail |
| FalsePositiveDiagnosticTest | 1 | ✅ 0 fail |
| VmBridgeSpikeTest | 1 | ✅ 0 fail |
| **ScaledPatternReproTest（v1.10.9 翻倍样板回归）** | **6** | ✅ 0 fail |
| **VideoFuzzyReplacementReproTest（v1.10.5 视频 bug 回归）** | **5** | ✅ 0 fail |
| **ProductiveBeesReferenceTest（v1.10.10 蜜脾精确匹配）** | **6** | ✅ 0 fail |
| **PatternRefreshReuseTest（v1.11.x 新样板刷新）** | **1** | ✅ 0 fail |

---

## 一、闪电基准测试（Thunderbolt-Core 参考能力，39 例）

> "闪电" = Thunderbolt（雷/闪电）：跑 Thunderbolt-Core 规划器能力参考套件，
> 测 AE2VM 计算速度（elapsedMs + VM 自身 calc time）+ 能力表面。13 图族 × 3 材料模式。

- **入口**: `com.ae2vm.addon.bench.Ae2VmReferenceCapabilitySuiteTest`
- **planner**: `Ae2VmReferencePlanner`（String network key —— `realStockOf` 恒 0，不走 stock-aware）
- **时限**: 每例 1s 硬时限 + 100ms 取消宽限（无一例超时）
- **本次全量运行**：
  ```
  [reference-capability] SUMMARY cases=39 supported=38 falsePositive=1 engineError=0 timeout=0 totalElapsedMs=73.6
  ```

> ⚠️ **唯一剩余 FALSE_POSITIVE（multi-dag/fibonacci/minimum）**：多样板最优选择（自 v1.9.6 起记录），
> 需 VM 新增全局优化选择（Thunderbolt 的 BoundedCombinations / 线性求解器），实现风险高，
> 本轮未实现（曾试局部最小叶子代价启发，会回退 greedy-trap，已还原）。
> **催化剂（9/9）、耐久（3/3）、递归（6/6）、换算环（3/3）、模糊路由（3/3）均稳定 SUPPORTED。**

### 1. 单配方 DAG

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs |
|---|---|---|---|---|---|
| single-dag/dispersed | MISSING | 3 | ✅ SUPPORTED | {D=2, G=1} | 3.195 |
| single-dag/dispersed | MINIMUM | 3 | ✅ SUPPORTED | {} | 2.128 |
| single-dag/dispersed | UNBOUNDED | 3 | ✅ SUPPORTED | {} | 1.754 |
| single-dag/fibonacci | MISSING | 32 | ✅ SUPPORTED | {X0=3, X1=5} | 4.816 |
| single-dag/fibonacci | MINIMUM | 32 | ✅ SUPPORTED | {} | 3.709 |
| single-dag/fibonacci | UNBOUNDED | 32 | ✅ SUPPORTED | {} | 3.688 |

### 2. 多配方 DAG

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs |
|---|---|---|---|---|---|
| multi-dag/greedy-trap | MISSING | 64 | ✅ SUPPORTED | {S=64} | 2.218 |
| multi-dag/greedy-trap | MINIMUM | 64 | ✅ SUPPORTED | {} | 2.051 |
| multi-dag/greedy-trap | UNBOUNDED | 64 | ✅ SUPPORTED | {} | 1.972 |
| multi-dag/fibonacci | MISSING | 12 | ✅ SUPPORTED | {X0=5, X1=9, X2=7} | 2.111 |
| multi-dag/fibonacci | MINIMUM | 12 | ❌ FALSE_POSITIVE | {X0=4} | 3.184 |
| multi-dag/fibonacci | UNBOUNDED | 12 | ✅ SUPPORTED | {} | 1.704 |

### 3. 环裁切（v1.10.3 换算环守恒 → 全 3 例 SUPPORTED）

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs |
|---|---|---|---|---|---|
| cycle/conversion-ring | MISSING | 3 | ✅ SUPPORTED | {C=1} | 2.510 |
| cycle/conversion-ring | MINIMUM | 3 | ✅ SUPPORTED | {} | 1.435 |
| cycle/conversion-ring | UNBOUNDED | 3 | ✅ SUPPORTED | {} | 1.304 |
| cycle/self-growth-cut | MISSING | 2 | ✅ SUPPORTED | {A=2} | 1.470 |
| cycle/self-growth-cut | MINIMUM | 2 | ✅ SUPPORTED | {A=1} | 1.342 |
| cycle/self-growth-cut | UNBOUNDED | 2 | ✅ SUPPORTED | {} | 0.897 |

### 4. 催化剂 / 反馈环（v1.10.x 全修复 → 9/9 SUPPORTED，稳定）

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs |
|---|---|---|---|---|---|
| catalyst/returned-seed | MISSING | 1000 | ✅ SUPPORTED | {A=1} | 3.109 |
| catalyst/returned-seed | MINIMUM | 1000 | ✅ SUPPORTED | {} | 1.311 |
| catalyst/returned-seed | UNBOUNDED | 1000 | ✅ SUPPORTED | {} | 0.950 |
| catalyst/raw-feedback-loop | MISSING | 8 | ✅ SUPPORTED | {A=1} | 2.431 |
| catalyst/raw-feedback-loop | MINIMUM | 8 | ✅ SUPPORTED | {} | 1.289 |
| catalyst/raw-feedback-loop | UNBOUNDED | 8 | ✅ SUPPORTED | {} | 2.087 |
| catalyst/lossy-feedback-loop | MISSING | 8 | ✅ SUPPORTED | {A=2} | 1.515 |
| catalyst/lossy-feedback-loop | MINIMUM | 8 | ✅ SUPPORTED | {} | 1.121 |
| catalyst/lossy-feedback-loop | UNBOUNDED | 8 | ✅ SUPPORTED | {} | 1.443 |

### 5. 耐久链（v1.10.x 新增支持 → 3/3 SUPPORTED，稳定）

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs |
|---|---|---|---|---|---|
| durability/finite-use-chain | MISSING | 100 | ✅ SUPPORTED | {tool=1} | 1.997 |
| durability/finite-use-chain | MINIMUM | 100 | ✅ SUPPORTED | {} | 0.883 |
| durability/finite-use-chain | UNBOUNDED | 100 | ✅ SUPPORTED | {} | 0.955 |

### 6. 模糊 / 可复用库存（v1.10.3 种子模糊 → 全 3 例 SUPPORTED）

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs |
|---|---|---|---|---|---|
| fuzzy/variant-route | MISSING | 1000 | ✅ SUPPORTED | {logical_tool=1} | 3.838 |
| fuzzy/variant-route | MINIMUM | 1000 | ✅ SUPPORTED | {} | 1.746 |
| fuzzy/variant-route | UNBOUNDED | 1000 | ✅ SUPPORTED | {} | 1.321 |

### 7. 递归 / 自引用配方（v1.10.3 新增支持 → 6/6 SUPPORTED，稳定）

> 自引用样板 = 输出键同时也是自身非返还消耗输入。自产出抵消自消耗，自键变为
> 一次性种子 + 放大器（net = out − in）。种子未库存时样板无法点火 → 恰报缺 1 种子。

| 用例 | 模式 | scale | 状态 | 缺失 | elapsedMs |
|---|---|---|---|---|---|
| recursion/amplifier | MISSING | 8 | ✅ SUPPORTED | {A=1} | 0.962 |
| recursion/amplifier | MINIMUM | 8 | ✅ SUPPORTED | {} | 0.905 |
| recursion/amplifier | UNBOUNDED | 8 | ✅ SUPPORTED | {} | 1.369 |
| recursion/essence-catalyst | MISSING | 8 | ✅ SUPPORTED | {A=1} | 1.312 |
| recursion/essence-catalyst | MINIMUM | 8 | ✅ SUPPORTED | {} | 0.862 |
| recursion/essence-catalyst | UNBOUNDED | 8 | ✅ SUPPORTED | {} | 0.697 |

- `recursion/amplifier`（A+B→2A）：每合成净增 +1 A，`n` 个 A 需 1 种子 A + `n−1` B；
  缺 A 种子 → 缺 {A=1}，有 {A:1, B:n−1} → 可行。
- `recursion/essence-catalyst`（A+B→A+C）：A 既是输入又是副产物输出（精华），循环流转；
  `n` 个 C 需 1 种子 A + `n` B；缺 A 种子 → 缺 {A=1}，有 {A:1, B:n} → 可行。

**性能基准（1.20.1 本次全量运行内）**: 39 例 elapsedMs 0.697 – 4.816 ms，全量共 73.6 ms；
无一例触碰 1s 时限。唯一 FALSE_POSITIVE 为 `multi-dag/fibonacci/minimum`（多样板最优选择，
与 1.21.1 主版本一致，已确认非本次迁移引入），非引擎错误。

---

## 二、边界能力基准（37 例场景 / 38 用例，本次实际运行）

- **入口**: `com.ae2vm.addon.bench.Ae2VmBoundaryCapabilitySuiteTest`
- **驱动**: 真实 `FakeBenchGrid`（IGrid）→ 激活 `realStockOf` 的 stock-aware 聚合 + 模糊组替换变体库存路径
- **守护**: 玩家 NAST 报告「合成 1 个/1b 缺失，但 2 个/100b 正常」+「有样板却报缺失」
- **本次运行**:
  ```
  [reference-boundary] SUMMARY cases=37 ok=37 feasible=36 totalElapsedMs=427
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
| ProcessingDefaultFuzzyTest | 3 | 处理配方默认模糊：不同 NBT 变体满足槽位（GTL 温室/MA 精华）、无变体真缺失 |
| RecursionReferenceTest | 6 | 递归/自引用：amplifier（A+B→2A）与 essence-catalyst（A+B→A+C）三模式（MINIMUM/UNBOUNDED 可行无缺失、MISSING 恰缺种子 {A=1}） |
| ProductiveBeesReferenceTest | 6 | 蜜脾万象样板：bee_A 蜜脾不串料（报缺 bee_A 不消耗 bee_B）、各自变体用各自蜜脾、递归种子保留/放大器精确输出 |
| ScaledPatternReproTest | 6 | 翻倍样板：解包编译原始样板（per-craft=1）、嵌套 ×3×2 递归解包、8 个橙总消耗 8 砂+8 染料 |
| VideoFuzzyReplacementReproTest | 5 | 模糊替换精确槽：可合成主变体/模糊槽替代库存/无替代真缺失（2026-08-09 视频 bug） |
| PatternRefreshReuseTest | 1 | 复用 VM 新样板识别：中间产物 X 加样板后不再报缺失（v1.11.x PATTERN-REFRESH） |

---

## 四、回归测试（150 例全过）

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
| 递归 / 自引用 | `CraftingVM.computeSelfKeys()`（自键检测：输入∩输出，排除 returned 输入与未播种自增环）+ `correctRecursion()`（种子校验：缺种子→报缺 `in−s` 并置 0 次合成；主输出自键 net>0 → 合次数改 `ceil((请求−种子)/net)`）+ `applyOrdered()` 自键 used 收敛为一次性种子（消除自产出掩盖自缺失） |
| 换算环守恒 | `CraftingVM.computeConversionRingMissing()`：`allPatternsResolver` 提供全图案（含 B 的双向换算）→ 无副产物纯换算 SCC 检测 → BigInteger 分数交换值 BFS → 环值（库存）vs 外部需求值精确比较，不足时在最小价值外部需求键上报缺（只增不减 missing） |
| 可复用库存种子模糊 | `Ae2VmReferencePlanner` 把 `returnedFrom` 映射为带路由变体的 returned 种子 + 喂入宿主可复用库存；`CraftingVM.applyBundleDirect` 种子抽取按 `fuzzyFamilyOf` 匹配变体 |

---

## 六、剩余 FALSE_POSITIVE（唯一：多样板最优选择，VM 缺全局优化概念）

- `multi-dag/fibonacci/minimum`（最优多样板选择：每个输出在多条样板里选使叶子总消耗最小的组合，
  需考虑共享资源冲突；Thunderbolt 用 BoundedCombinations / 线性求解器）。
  本轮曾试局部最小叶子代价启发——会回退 `greedy-trap`（A 也消耗 R 时 B 仍选 R），已还原。

实现需 VM 新增整套全局优化选择概念，实现风险高，建议按需评估（见 /memories/repo/ae2vm-thunderbolt-reference-bench.md）。

---

## 结论

**稳定基线 38 / 39 SUPPORTED（97%）**——唯一剩余 FP 为 `multi-dag/fibonacci/minimum`（最优多样板选择）。
催化剂（9/9）、耐久（3/3）、递归（6/6）、换算环（3/3）、模糊路由（3/3）能力族全部修复且**稳定**；
处理配方默认模糊修复落地。递归修复覆盖 `A+B→2A` 放大器与 `A+B→A+C` 精华 A-A 催化剂场景；
换算环修复消除"无种子报可行"的危险假阳；模糊路由支持宿主可复用库存变体种子。
**1.20.1 基准套件已从 1.21.1 完整迁移并本地跑通**：全部 150 个 VM/闪电/边界/回归用例 0 失败，
闪电 39 例 38 SUPPORTED（唯一 FP 与 1.21.1 主版本一致），边界 37/37 与期望一致，VM 计算全部 < 5 ms/例。

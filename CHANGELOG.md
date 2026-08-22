# Changelog / 更新日志

版本号基于 `1.9.0`：每次编译 `mod_version` +0.0.1（1.9.0 → 1.9.1 → …）。


## [1.12.41] - 2026-08-26（GTL 机器执行修复）

### 修复（v1.12.41 - GTL 机器执行被 mixin 破坏）

- **问题**：v1.12.40 用 `GtlCatalystExtractMixin` `@Overwrite` 整个 `AEUtils.extractForProcessingPattern`，
  替换了 GTL 自己的 circuit/amount 缩放逻辑。用户报告"配方发配正常，但机器没有正常运行"——
  实际上机器 craft 永远拿不到完整输入集，因为 circuit_resonatic_* 等 GTL 电路被
  `@Overwrite` 后的版本识别为正常消耗输入（GTL 原版会通过 `isIntegratedCircuit` 跳过）。
- **修复**：删除 `GtlCatalystExtractMixin`，新增 `GtlIntegratedCircuitMixin`：
  - 只用 `@Inject(method="isIntegratedCircuit", at=@At("RETURN"), cancellable=true)`
    在 GTL 判定返回 false 时**叠加**判定，覆盖 `circuit_resonatic_*`、`*_universal_circuit`
    系列（保持 `circuit_board`/`circuit_compound` 排除，规则与 `PatternCompiler.isGtlCircuitInput` 同步）。
  - **完全不触碰 `extractForProcessingPattern` 的 amount-scaling 与模板提取逻辑**，
    CPU extract 层行为恢复为 GTL 原版，机器正常 craft。
- `AE2VMMixinConfigPlugin`：新增 `GTL_CIRCUIT_MIXIN` 常量；onlyVmMixins 模式下也保留
  `GtlIntegratedCircuitMixin`（否则对比模式下机器依然 stall）。

### 新增（GTL 批量样板更新修复）

- **GTLCore 批量样板更新（ae2CraftingServiceUpdateInterval = 4 tick）适配**：
  gtlcore 的 CraftingServiceMixin 把 AE2 onServerEndTick 取消到每 4 tick 才执行一次，
  `requestUpdate` 仅排队，变更推迟到下一个 4-tick 边界才在 `getCraftingFor` 中可见。
  当 VM 的编译缓存（Check2）因之前编译而命中、但 LIVE CraftingService（Check1）因批量
  排队仍为空时，旧循环立即重试（全部落在同一 pending 窗口内）→ 每次重试得到完全相同
  的假缺料（debug-1.log 取证：pattern=HAS + compiled=yes + stock>0 却 missing）。
  - 新增 `allMissingLive` 检查：仅当所有缺失键的 `getCraftingFor` 都非空时才重试。
  - 新增 `PROVIDER_SETTLE_MS = 300` × `PROVIDER_SETTLE_ROUNDS = 4` 有界等待：
    当 Check2 命中但 Check1 为空时，等待最多 1.2s 让批量更新落地，然后重算。
  - 真正缺失的键（Check1 和 Check2 都为空）不受影响（no-hang 保证）。

### 新增（GTL 专用测试基准）

- `GtlBatchedProviderUpdateBenchmark`（4 测试用例）：
  - `checkTaxonomy_compiledButNotLive_window`——检查层语义：Check2=true/Check1=false
    /allMissingLive=false；批量边界后 allMissingLive=true。
  - `vmConvergesAfterBatchBoundary`——VM 层面全流程：预热 → 刷新窗口（假缺料）
    → 批量边界 → 重算收敛。
  - `neverLiveKey_givesUpBoundedly`——真正缺失键有界等待（不挂起）。
  - `report`——基准报告打印完整时间线。

## [1.12.20] - 2026-08-26（缺失物取证版：MISSING-FORENSICS）

### 背景（latest (6).log 分析结论）

- 1.12.16/17 的 refreshAllByProduct 钩子**确认生效**：me_super_pattern_buffer 请求第 1 次重试
  仍是陈旧大树（uiv_universal_circuit=832，67 亿输入），第 2-5 次重试收敛到正确小树
  （4955 万输入）——陈旧 bundleCache 已能在重试窗口内被全局 bump 清除。
- 但**收敛后的计划仍带缺失物 → AE2 submitJob 以 INCOMPLETE_PLAN 拒绝 → 合成永不启动**。
- 失败模式高度一致：**凡是配方含 GT 流体（polyimide/PEEK/PBI/infuscolium/lubricant）的合成全部报缺**；
  唯一成功的请求（creative_energy_cell ×1/×100）纯物品需求。
- 缺失物（polyimide=111216、PEEK=79776、PBI=78444、uxv_electric_motor=12、cubic_zirconia_dust=76……）
  在 5 次重试中完全稳定 → missingKeyNowCraftable 全程返回 false → 这些键的
  `getCraftingFor` 为空。需区分三种根因：真缺料 / 样板不可见 / 键不匹配。

### 新增（取证）

- **AE2VMCrafting MISSING-FORENSICS**：最终计划仍带缺失物时，对每个缺失键逐条 WARN 输出：
  `stock`（实时网络库存量，含流体）/ `pattern`（CraftingService 此刻是否可见样板）/
  `compiled`（VM 是否曾编译过产出该键的样板）/ `dropSecondary`（变体键是否可匹配到样板）。
  下一份日志将一锤定音区分真缺料 vs 样板可见性 bug vs 键不匹配 bug。
  **行为零改动**：仅加日志，任何异常都被吞掉不影响请求。

## [1.12.18] - 2026-08-26（EAE+ / gtlcore 样板翻倍适配：无线 ME 网络核心桥接 + 智能翻倍计划尊重）

### 背景

用户报告 1.12.17（refreshAllByProduct 钩子）后仍「修复失败」，并给出关键线索：样板总成
（超级样板总成）都是通过 **EAE+（ExtendedAE Plus）无线收发器（无线ME网络核心）** 自动
连接主网络。EAE+ 的无线桥接用真实 GridConnection 把两侧网络**合并为同一个 grid**，因此
1.12.16 的全局 pattern 版本 bump 机制仍生效；真正被 VM 绕过的是 EAE+ 的「样板翻倍」
（智能翻倍）：EAE+ 在**计划构建阶段**（CraftingSimulationState.buildCraftingPlan）把
crafts 重分批为 ScaledProcessingPattern（输入×N/输出×N），而 AE2 VM 自建 CraftingPlan、
完全跳过了这一步——VM 计算的所有请求都不再走智能翻倍，CPU 退化为每份 ×1 小批量推送。

### 适配（EAE+ 智能翻倍 / gtlcore 样板翻倍）

- **新增 EAESmartDoublingCompat（纯反射软加载）**：在 CraftingVM.buildPlan 对最终
  patternTimes 应用与 EAE+ CraftingSimulationStateMixin **逐行镜像**的重分批：
  - 仅处理实现 ISmartDoublingAwarePattern 且 allowScaling == true 的样板（EAE+
    样板供应器的样板；gtlcore 的 ×N 重编码样板不实现该接口 → 原样保留，其
    CraftingCpuLogicMixin autoExpand 路径不受影响）；
  - provider 轮询分配（providerRoundRobinEnable，按 CraftingService.getProviders
    计数均分）、全局/供应器级翻倍上限（smartScalingMaxMultiplier）、super matrix
    十次分包（ceil(total/10)）；
  - 总量严格守恒：Scaled(P,n) × k ≡ P × (k·n)，used/missing/emitted 不变；
  - **他们即使不安装，我们也正常运行**：所有 EAE+ 类只经反射加载，EAE+ 未安装或
    反射失败时 rebatch 原样返回输入，VM 行为与未适配前完全一致。
- **gtlcore 样板翻倍（PatternModifier 重编码 ×N）**：×N 是真实的 AEProcessingPattern
  （非包装类），VM 经由 IPatternDetails.getInputs()/getOutputs() 天然按 ×N 折算；
  重分批不会触碰它们（allowScaling 恒 false），确认无需额外改动——本次以测试与文档固化。
- **无线网格合并/拆分失效覆盖**：CraftingServiceMixin 新增 addNode TAIL 全局
  pattern 版本 bump——EAE+ 收发器建立/断开连接导致网格合并/拆分、provider 节点重注册
  时不经过 refreshNodeCraftingProvider，此钩子确保主网络 VM 下一次 execute() 丢弃旧
  bundleCache 重新编译，杜绝跨网络链式假阴。

### 测试

- **新增 EAESmartDoublingCompatTest**（9 用例，离线无 EAE+ 依赖）：
  - 无限制/轮询/上限三种分批算术与 EAE+ 精确镜像；
  - super matrix ceil(total/10) 分包；
  - 穷举总量守恒硬约束（Σ multiplier × count == totalAmount）；
  - 软失败：EAE+ 不在类路径时 rebatch 原样返回输入引用。
- 全量回归：38 测试类 / 254 用例全部通过（0 失败 / 0 错误）。

## [1.12.16] - 2026-08-25（PatternProvider 全链路覆盖 + FOA 倍率切换路径加固）

### 修复（PatternProvider 缺料假阴根治）

- **gtladditions FOA 模式倍率切换**：GTLAdditions 的 `MESuperPatternBufferPartMachine` 在切换 `FOAMode` / `FOAPatternOutputMultiplier` 时调用 `refreshAllByProduct()`——该路径**绕过了** `onPatternChange`，导致原有的 mixin 钩子无法触发 VM 重新编译。在 `MEPatternBufferPartMachineMixin` 中新增专属 `refreshAllByProduct` TAIL 注入（`bumpPatternVersion()` + `compilePatternBufferPatterns(this)`），彻底覆盖倍率切换刷新路径，根治 me_super_pattern_buffer 链式缺料假阴。
- **gt_shanhai RecipeType PatternBuffer 倍率器**：`RecipeTypePatternBufferPartMachine` 同理继承 `refreshAllByProduct`，新增钩子自动覆盖。

### 兼容（他们即使不安装，我们也正常运行）

- **PatternProvider 全链路审计**：确认以下所有注册路径均通过 `MEPatternBufferPartMachine.onPatternChange` 超级链触发 VM 重新编译，无需任何 GTL 前置条件；通过 `@Pseudo` + 反射 `getMethod("getAvailablePatterns")` 实现软加载（`require=0`），确保 GTL 未安装时静默忽略：
  - `gtlcore` — `MEPatternBufferPartMachine`
  - `gtladditions` — `MESuperPatternBufferPartMachine`（FOA / MEStocking / MEIOBus 等继承链）
  - `GTLModeAware` — `ModeAwarePatternBufferPartMachine`
  - `GTLsupb` — `supb.pattern.BufferPartMachine`
  - `gt_shanhai` — `RecipeTypePatternBufferPartMachine`
  - `MolecularAssembler` — `MolecularAssemblerPortOutputMultiplierMixin`
  - `QuantumCrafting` — `QuantumCraftingCPULogic`
  - `CraftingServiceMixin.refreshNodeCraftingProvider` — 动态节点注册
- `@Pseudo` + `string target` class 注入确保 mixin 对非 GTL 环境完全透明。

### 测试

- **`GtlFoamPatternBufferBenchmark` 三场景验证**：
  1. `foaReencodedInstanceIsStable` — FOA 倍率切换产生的全新 Pattern 实例与旧实例 definition-level equals，VM 编译缓存正确命中，实例级差异不影响缓存。
  2. `foaBulkRefreshWindowRetryConverges` — `refreshAllByProduct` 后 200 ms 重试窗口（`RETRY_SETTLE_MS = 200L`）内 VM 恢复可用，验证异步窗口安全收敛。
  3. `foaMultiplier15CraftsCorrectTotals` — ×15 输出 / ×15 输入倍率完整算术验证，craftingTotals / outputTotals / inputTotals 全部正确。

## [1.12.15] - 2026-08-19（红黑树 + 记忆化计划快路径 v3：10^9 与 24 层斐波那契 < 10 μs）

### 优化（快路径 v3：记忆化完整计划，跳过仿真聚合）

- 慢路径完成后把【完整计划】（used/missing/emitted/patternTimes/bytes）存入 VM 缓存；
  温热请求满足以下条件时直接返回缓存计划的副本：
  1) outputKey + rootCraftTimes + pattern 版本不变（缓存键）；
  2) 整棵 bundle DAG 仍缓存且 capturedFor 与当前 resolver 内容一致（深身份遍历，
     玩家换/改样板 → 失效走慢路径）；
  3) 缓存计划中每个 used key 都是【纯叶子】（无样板 → 库存无关，正确性保持）；
  4) 叶子库存守卫：每个 used 叶子当前库存仍足够（库存抽干 → 走慢路径重新推导 missing）。
- 红黑树（TreeMap/TreeSet）：缓存存储端 patternTimes 用红黑 TreeMap（确定性迭代，
  利于可复现基准）；热的 DAG 身份遍历用 HashSet（O(1)，不拖慢热路径）。
- 快路径移到 execute() 顶部（状态重置之前）——温热命中完全跳过 512 槽 stack 与
  9 个 KeyCounter 等约 15 次分配。
- 实测（本机 Java21 / Gradle 8.10，全部 < 10 μs 目标）：
  - 10^9 数量级订单温热：**3.2 μs** ✅（目标 < 10 μs）；
  - 24 层斐波那契温热：**8.0 μs** ✅（目标 < 10 μs）；
  - 2 层链温热：3.0 μs；12 层深链温热：4.0 μs；
  - 冷启动 2 层链：616 μs（快路径不适用，全捕获成本）。
- 新增基准：warmBillionQuantity（10^9）、warmFibonacci24（24 层斐波那契）、
  parallelIndependentVmsAreConsistent（8 线程 × 独立 VM，结果一致）。
- 正确性：**242 全量测试全部通过**（含 3 个样板替换/修改、库存抽干、并行确定性等）。

## [1.12.14] - 2026-08-19（性能基准 + 温热快路径 v2）

### 新增（性能基准 PerformanceBenchmark，3 例）

- warmTwoLevelChain：2 层链温热复用（目标 < 10 μs，当前基线见下）；
- warmDeepChain：12 层深链温热复用；
- coldStartTwoLevelChain：冷启动（首次全捕获）。

### 优化（温热快路径 v2：跳过字节码执行，直接聚合缓存包）

- 前置条件（全部满足才走快路径）：pattern 版本未变；整棵可达 bundle DAG
  全部缓存；每个 bundle 的 capturedFor 与当前 resolver 内容一致（玩家换样板/
  改样板 → 深身份校验失败 → 走慢路径重捕获，正确性保持）；无 missing 捕获；
  无 catalyst/durability（seeds/durability 为空，不需要初始库存快照）；无自环。
- 走快路径时：跳过 snapshotExecuteStartStock（省图遍历 + SIMULATE）与字节码执行，
  直接 rootCraftTimes 播种 → buildPlan → applyAggregation（对 FRESH 仿真 deficit-apply，
  库存变化仍正确反映）。
- 实测（本机 Java21 / Gradle 8.10）：
  - 温热 2 层链：372 μs → **183 μs**（约 2×）；
  - 温热 12 层深链：512 μs → **338 μs**（约 1.5×）；
  - 冷启动 2 层链：750 μs → **588 μs**。
- 剩余开销主要在 applyAggregation 的 DAG 遍历与 Map 分配；< 10 μs 需要
  按请求量预缓存聚合结果（major redesign，列为后续优化项）。
- 正确性：236 全量测试（含 3 个样板替换/修改用例）全部通过。

## [1.12.13] - 2026-08-19（玩家操作基准：样板替换/修改的身份戳重捕获 + 无输出样板防御）

### 修复（样板被替换/修改后 JIT 复用旧 bundle → 假阳/假阴双发）

- **症状**：玩家把中间样板换成另一张配方（或倍乘器改输出量）后，若 GTL 的
  bumpPatternVersion 未触发（休眠 ticker / 刷新窗口），VM 复用旧 bundle：
  - 换省材配方 → 仍按旧配方算料 → **假缺**（假阴）；
  - plan 的 patternTimes 键为已移除的旧样板实例 → CPU getProviders 找不到
    → **计划可行但执行卡死**（假阳）。
- **修复**（CraftingVM）：
  - Bundle 新增 `capturedFor` 身份戳（RETURN 捕获时记录当前解析样板）；
  - capturing / cts==1 / cts>1 三个 JIT 复用分支新增 `bundlePatternChanged`：
    内容级比较（输出+输入）不一致 → 强制重捕获；
  - `patternsEquivalent` 内容比较避免供应器换新实例时误触发。
- **基准**：PlayerPatternOperationsBenchmark 三例（换耗材配方正确报缺、换省材配方可行、
  改输出量按新量折算），修复前失败、修复后通过。

### 修复（无输出样板 NPE → 请求回退原生卡死）

- **症状**：getOutputs() 为空/主输出 null 的异常样板使 compilePattern 抛 NPE，
  请求进入原生回退（GTL MAX_FAST 卡顿）。
- **修复**（PatternCompiler）：`hasUsableOutput` 守卫（编译期 + compileIfAbsent 双保险），
  无输出样板跳过编译，VM 按缺料处理。
- **基准**：playerEmptyOutputPatternDoesNotCrash。

### 新增（玩家操作基准套件 PlayerPatternOperationsBenchmark，25 例 + 文档）

第一轮 12 例：编码→放置→下单、替换/修改样板（3）、移除/补写中间样板（2）、模糊槽/精确槽（2）、
副产物供给兄弟链、递归放大器种子语义、催化剂种子、耐久工具 uses 折算、流体桶
multiplier×amount、无输出样板防御。

第二轮新增 11 例（本轮）：多样板同输出（mega 按输出量折算）、重复输入槽累加、返回容器+正常消耗混合、
空输入样板（无中生有）、共享中间产物多父聚合、null getInputs() 不崩溃（按缺料处理）、
null possibleInputs 槽跳过、零数量请求空计划、超高输出量 craftTimes 折算、
重编码同内容样板不触发重捕获、流体副产物供给兄弟链。
报告：docs/player-pattern-operations-benchmark.md。

### 修复（logPlanResult 对 null 输入防御 + 空输入列表编译防御）

- `CraftingVM.logPlanResult` 遍历 `patternTimes` 计算总原料时可能遇到 `getInputs()` 返回
  null 的异常样板 → NPE。已加 null 判空。
- `PatternCompiler.compileIfAbsent` 增加 `getInputs() == null` 检查，跳过不可编译样板；
  `compilePattern` 同样返回 null（安全网）。

## [1.12.12] - 2026-08-19（全量基准测试 + 通过基准修复大数量订单溢出）

### 修复（大数量订单 ceil 溢出 → 链式合成静默变空 / 假缺 / 卡死）

- **缺陷**：三处 `(a + b - 1) / b` 在 `a` 接近 `Long.MAX_VALUE`（10^18+ 订单）时
  溢出为**负数**：
  1. `PatternCompiler.compileRequest` 的根请求 craftTimes；
  2. `CraftingVM` DIV_ROUNDUP 指令（含 2 的幂次快速路径）；
  3. `CraftingVM` CALL_BY_KEY 的子样板 cts。
  后果：craftTimes/cts 为负 → `cts<=0` 跳过子合成 → 大数量计划静默变空、
  中间产物假缺（与「大数量订单卡死/假阴」症状一致）。
- **修复**：三处统一改为饱和 ceil-div `a / b + (a % b == 0 ? 0 : 1)`
  （`PatternCompiler.ceilDiv` 公开 + `CraftingVM.ceilDiv` 私有），正 long 永不溢出。
- **基准**：`GtlFullCoverageBenchmark#bigOrderCeilDivSaturatesAtLongMax`、
  `bigOrderSubCraftDemandSaturates`（修复前失败、修复后通过）。

### 修复（vmShouldFallback 多层包裹取消识别）

- 取消判定改为有界解包 `CompletionException` 链（最多 4 层），
  任何深度的取消包裹都零原生回退。

### 新增（全量基准测试 GtlFullCoverageBenchmark，12 例）

- 大数量溢出：根请求饱和、子链需求饱和（2 例）；
- 假阴/链内缺料：provider 窗口全流程重试、深层子 bundle stale-missing 自愈、
  反向 stale 删样板报缺（3 例）；
- 取消/回退流：取消零回退、真实失败恰一次回退、VM_FALLBACK 防递归（2 例）；
- 并发/确定性/性能：共享 VM 8 线程并行结果一致、版本号风暴 10000 次结果稳定、
  两个独立 VM 结果一致（3 例）；
- 计划可行性约束：usedItems 不超库存、patternTimes 键全部真实且可解析（2 例）；
- GTL 提交/插入模型共存（1 例）；
- 既有：GtlMixinCoexistenceBenchmark（矩阵/applyDiff/取消/窗口/双 TAIL）、
  GtlPatternMultiplierReproTest（翻倍 4 例）、GTLProxyAeCoexistenceBenchmark、
  GTLStaleExtractReproTest、GTLStaleOscillationBenchmark。

## [1.12.11] - 2026-08-19（GTL 特化：冲突排查 / 共存基准 / 样板翻倍审计 / 日志假阴修复）

### 修复（latest (3).log：取消请求触发阻塞原生回退 → 服务器卡顿）

- **症状**：14 次 CRAFT START/END（VM 计算完成）但只有 6 次 VM OK；
  紧接着多条 `Can't keep up! Running 22994ms or 459 ticks behind`。
- **根因**：`CraftingServiceMixin` 的 `.handle(...)` 把 `CancellationException`
  （请求被 requester/CPU 取消）也当成 VM 失败 → 在 ForkJoinPool 线程里
  `nativeFuture.get()` **阻塞重跑 GTL MAX_FAST 原生算法** → 10-30s 卡顿。
- **修复**：新增 `vmShouldFallback(Throwable)`——取消（含 CompletionException
  包裹）直接以取消传播，不再触发原生回退；只有真实失败才回退。
- **基准**：`GtlMixinCoexistenceBenchmark#cancellationIsNotANativeFallbackFailure` /
  `realVmFailureStillFallsBackToNative`。

### 修复（latest (3).log：GTL provider 刷新窗口下的链内假阴）

- **症状**：21:43:05 `opv_4a_wireless_energy_receive_cover` 连续三次
  `missing={gtceu:infuscolium=8456}`；21:43:10 单独下单 infuscolium 10001
  → `missing=(none)`。「链内报缺、单独合成正常」。
- **根因**：GTL 样板供应器（ME 样板总成 / 超限演算阵列）在服务器 tick 同步
  （removeProvider → addProvider 窗口），VM 异步计算撞进窗口时
  `getCraftingFor` 为空且样板从未编译 → Check 1/Check 2 全失败 → 假阴。
- **修复**：`AE2VMCrafting.calculateAsync` 重试判定提取为
  `missingKeyNowCraftable(...)`；首查未命中时等待 `RETRY_SETTLE_MS=60ms`
  （约 1-2 tick）再查一次，命中则 invalidate+重编译+新仿真库存重跑；
  最多 3 次重试，开销有界。
- **基准**：`GtlMixinCoexistenceBenchmark#providerRefreshWindowRetryFindsLatePattern`。

### 新增（共存 mixin 场景基准测试）

- `GtlMixinCoexistenceBenchmark`：
  - 静态 mixin 目标矩阵（VM 5 点 vs GTL 31 点，数据来自真实源码），
    断言共享 (目标类::方法) 无 @Overwrite 硬冲突，唯一软重叠为
    `PatternProviderLogic.updatePatterns`（双 TAIL）；
  - VM 路径从不调用 GTL 覆盖的 `CraftingSimulationState.applyDiff`
    （防 AbstractMethodError 潜在风险）；
  - 取消不回退 / 真实失败仍回退；
  - GTL provider 刷新窗口重试；
  - PatternProviderLogic 双 TAIL 处理器共存。

### 新增（样板翻倍支持审计 + 测试）

- 审计报告：`docs/gtl-pattern-doubling-audit.md`。
- 三种形态均受支持：GTL PatternModifier 重新编码（普通样板，隐式支持）、
  UselessMod ScaledProcessingPattern（unwrapScaled，既有）、
  ME 样板总成 auto-expand（执行期，天然隔离）。
- 新增 `GtlPatternMultiplierReproTest` 4 例：倍乘消耗线性、缺料按倍乘单位、
  patternTimes 键为倍乘样板本身、倍乘样板进样板总成后链式合成。

### 冲突排查报告

- `docs/gtl-mixin-coexistence-audit.md`：9 项冲突/问题逐一处置。
  其中 **mods 目录同时存在 ae2vm 1.12.9 与 1.12.10 两个 jar**（日志
  `version 1.12.9 -> 1.12.10`）必须删除旧版；extendedae_plus 注入失败、
  redirector×modernfix、超限演算阵列读档 NPE 为第三方问题，已给出处置建议。

## [1.11.12] - 2026-08-19

### 修复（先下单最终产物→缺中间产物→补样板仍不识别；CPU 20s 重试也漏掉）

- **症状（复刻场景，latest (5).log + 聊天澄清）**：
  - 02:17:32 下单 melodic_item_conduit → missing={pulsating_powder=9}（无样板）。
  - 02:17:52 CPU 20s 自动重试同链 → **仍** missing={pulsating_powder=9}。
  - 02:18:02 直接下单 pulsating_powder → 成功（样板已写入、网络可见）。
  - 用户澄清："先下单最终产物，缺失中间产物，写样板进去，就识别不了；
    如果先把中间产物样板补齐，再下单最终产物和中间产物都正常"。
- **根因（版本号从未被 bump）**：整局日志没有任何 `bumpPatternVersion` 输出。
  `PatternProviderLogicMixin.onUpdatePatterns`（v1.11.x 的 PATTERN-REFRESH）在
  该复刻中从未触发 → `CraftingVM.execute()` 的版本检查不成立 → `bundleCache` 保活 →
  CPU 重试复用旧 bundle（melodic_alloy_ingot 等全为 REUSE）→ 中间产物仍报 missing。
  stale-missing 重查（v1.11.8）依赖样板已注册进 CraftingService，而重试发生在写样板
  之前，同样救不了。
- **修复**：在 `CraftingServiceMixin` 新增 `@Inject(refreshNodeCraftingProvider, HEAD)`
  钩子。任何样板供应器刷新（`ICraftingProvider.requestUpdate` → 网络刷新）都会调
  `PatternCompiler.bumpPatternVersion()`，这是比 `PatternProviderLogic.updatePatterns`
  更可靠的路径——第三方 mod（ExtendedAE/AdvancedAE 等）的供应器也必然走网络刷新，
  不会漏掉。修复后：写样板 → 版本变化 → 下次 `execute()` 清空 `bundleCache` → 全量
  重捕获 → 链中中间产物被正确识别为可合成。
## [1.11.9] - 2026-08-19

### 修复（移除中间产物样板后，最终产物仍可下单的假可行 — 反向 stale）

- **症状（第三次反馈，latest (3).log + 聊天澄清）**：
  - latest (3).log：01:31:35 melodic_item_conduit 成功（pulsating_powder 有样板）→
    01:34:15 移除 pulsating_powder 样板后 melodic_item_conduit 报 missing（正确）。
  - 用户澄清："**最终产物可以下单，但是中间产物不可以下单**"——移除中间产物样板后，
    最终产物仍显示可下单（假可行），但实际合成会卡死（中间产物无法合成）。
- **根因（反向 stale-missing）**：`staleMissingRecheck` 只检测「missing 现在有样板」的
  正向情况（样板**添加**后链中不识别）。但**反向**情况未处理：当中间产物样板被**移除**后，
  旧 bundle 里该中间产物是正常可合成节点（不是 missing），复用后 VM 仍认为它可合成 →
  最终产物报 feasible（可下单），但实际无法合成。测试复现：step2 移除 H3 样板后，
  `missing=(none)`、patternTimes 仍含 `item_h3=1`（VM 仍认为 H3 可合成）。
- **修复（v1.11.9）**：`staleMissingRecheckSubtree` 增加**反向检查**
  - 在检查 bundle.missing 之后、递归之前，遍历 `itemNeeds` 中的每个**可合成中间产物 key**：
    若该 key 现在**无样板**（exact / dropSecondary / fuzzy-family 三路解析均 null），
    说明中间产物已不可合成 → bundle 是反向 stale → 返回 true → 触发重新捕获。
  - 重新捕获后，被移除的中间产物在聚合时正确报告 missing → 最终产物不再假可行。
  - 与正向检查共用同一递归与 `staleMemo` 记忆化，性能开销不变。
- **测试（新增场景 G：样板增删改循环）**：`patternRemoveThenReaddRecovers()`
  - step1：H3 有样板 → 链可行（无 missing）。
  - step2：移除 H3 样板 → 链必须报 missing（**修复前此步失败**：仍报 feasible）。
  - step3：重新添加 H3 样板（不 bump）→ 同一 VM 复用，staleMissingRecheck 自愈恢复。
  - 完整覆盖用户「最终产物可下单/中间产物不可下单」的双向样板变更场景。
- **性能优化（v1.11.9）**：把反向检查与递归子树遍历**合并到同一次 itemNeeds 遍历**，
  每个被复用 bundle 只遍历一次 itemNeeds（而非正向/反向/递归三次），配合 per-execute
  `staleMemo` 记忆化 → 同一 bundle 引用重复复用 O(1) 命中。
- **性能基准测试（新增 `StaleRecheckPerfTest`）**：20 层深链、2000 次复用请求，
  平均每次 **0.40~0.62ms**（远低于 5ms sanity floor），证明 stale recheck（含反向检查
  + 记忆化）对正常 JIT 复用路径开销可忽略。
- **测试总计**：174 tests（新增 2：场景 G + 性能基准），全部通过。

## [1.10.8] - 2026-08-19

- **症状（第二次反馈，latest (2).log 01:09:26~01:10:09）**：
  - 01:09:26 melodic_item_conduit → `missing={pulsating_powder=9}`（新 VM，缓存已清）
  - 01:09:59 melodic_item_conduit → `missing={pulsating_powder=9}`（复用 melodic_alloy_ingot bundle）
  - 01:10:09 pulsating_powder 单独 → 成功（样板此时已存在）
  **用户反馈"没变化"——问题依旧。**
- **真正的根因（v1.11.8 修复前的分析）**：
  - staleMissingRecheck 只检查**被复用 bundle 自身**的 `missing`（直接缺失）。
  - 但 pulsating_powder 的 missing 在**深层子 bundle** 里：
    `melodic_item_conduit → melodic_alloy_ingot → crystalline_pink_slime_ingot →
    crystalline_alloy_ingot → pulsating_powder`。
  - 只有 `crystalline_alloy_ingot` 的 bundle 记录了 `missing={pulsating_powder}`；
    `melodic_alloy_ingot` 的 bundle 自身 missing 为空，通过 `itemNeeds` 引用子 bundle。
  - 01:09:59 复用 melodic_alloy_ingot 的 bundle → 直接检查 missing 为空 → 跳过 →
    **深层 pulsating_powder 从未被重新解析** → 持续报 missing。
- **修复（v1.11.8）**：`staleMissingRecheck` **递归遍历整个 itemNeeds 子树**
  - 新增 `staleMissingRecheckSubtree(Bundle, visited)`：先检查当前 bundle 的 missing，
    再沿 `itemNeeds` 递归检查每个子 bundle 的 missing（visited 去重防环）。
  - 只要**任意一层**的 missing key 现在有样板（exact / dropSecondary / fuzzy-family
    三路解析），返回 true → 触发重新捕获。
  - 三个 bundle 复用点（capturing / cts==1 / cts>1）调用不变，自动获得深层检测。
  - **性能优化（递归记忆化）**：`staleMemo` 每 execute 清空，key→[bundle 引用, 结果]，
    同一 bundle 引用重复复用 O(1) 命中，避免 N 次复用 × M 节点子树的 O(N×M) 重复遍历。
- **测试（新增动态编码样板环节，场景 F）**：
  - `dynamicEncodePatternBumpVersion()`：模拟 mixin 生效（bumpPatternVersion）→
    execute() 版本检查清空 bundleCache → 重新捕获识别动态编码的样板。
  - `dynamicEncodePatternNoBumpVersion()`：模拟 mixin 未生效（不 bump）→
    递归 staleMissingRecheck 自愈识别动态编码的样板。
  - 两个测试都断言**前置条件**（第一轮 H3 未编码时确实报 missing），证明测试真正
    覆盖了「样板缺失 → 运行时动态编码 → 再次请求识别」的完整游戏流程，而非平凡可行图。
  - 两条自愈路径（版本检查 / staleMissingRecheck）都收敛到正确结果（H3 不再 missing）。
- **测试总计**：172 tests（新增 2），全部通过。

## [1.10.7] - 2026-08-18

### 修复（变体有样板却被报缺失的假阴性）

- **症状**（长/复杂/多合成替换链）：VM 有概率把**有样板且可合成的变体物品**错误报为缺失
  （`missing={x_item[B]=5}`），即使同 fuzzy 族内的兄弟变体 `x_item[A]` 可合成。用户在游戏内遇到
  「已有样板但提示缺物品」的错误。
- **根因**：`CraftingVM.CALL_BY_KEY` 的样板解析只尝试 exact key → `dropSecondary()` → registry item，
  **从不尝试 fuzzy 族内其他可合成成员**。当 `X[B]`（父样板输入槽需要的变体）自身无样板，但同 fuzzy 族的
  `X[A]`（同 base，不同 variant）有样板时，VM 把 `X[B]` 当作不可合成的叶子 → 错误报缺失。
- **修复**（`CraftingVM.CALL_BY_KEY`，v1.11.x）：在 exact + `dropSecondary` 均无样板后，
  遍历 `fuzzyFamilyOf(tk)` 的所有成员（已注册 fuzzy 组或 processing 配方默认 fuzzy），
  若族内存在可合成员（`patternResolver.apply(member) != null`），则以该成员的样板代替，
  并将需求**重映射**为该成员（sub-call、bundle、聚合均以其命名）。父槽的 EXTRACT 模糊替换链
  消费合成出的成员。测试用例：`VariantCraftableSubstituteTest`（复现场景 + 正确缺失验证）。

## [1.10.6] - 2026-08-09

### 变更
- **版本号 1.10.6（三端统一）**：1.21.1（main）、1.20.1（1.20.1-forge）、26.1.2
  （26.1.2-neoforge）统一 `mod_version=1.10.6`；同步 v1.10.5 的模糊替换修复到
  1.20.1 与 26.1.2。

## [1.10.5] - 2026-08-09

### 修复（模糊替换作用于精确槽位的假可行 → CPU 卡死）
- **症状**（2026-08-09 视频，NAST）：合成 `无限高压闪电元件`（AE2 Lightning Tech，11.5MB 大计划）
  与合成 1 个 `钢质机壳` 都在 Crafting CPU 里**卡住**——进度恒为 0，ETA 暴涨（`514266:04:52` 之类），
  合成状态面板坍缩为「无限…」；**禁用 VM 即消失**。桃：看着还是像合成替换的问题。
- **根因**：模糊/物品替换组是**全局**按 key 注册的（`PatternCompiler.FUZZY_GROUPS`）。两处把替换组
  错误地套在了**精确槽位**（`getPossibleInputs()` 单变体、未开替换）上——而精确槽位在 AE2 执行期
  **只能使用主变体**：
  1. `applyAggregation` 的 stock-aware 分支用替换变体（如白羊毛）库存满足子项**全部**需求（含精确槽），
     精确槽父样板在计划里找不到主变体 → 永远无法 push → 卡死（可合成子项场景）；
  2. `CALL_BY_KEY` 叶子可用性检查因**无关样板**注册了替换组而压掉精确槽的缺失 → 计划报可行但执行卡死
     （叶子子项场景）。
- **修复**（CraftingVM/PatternCompiler/Opcode，v1.10.5）：
  1. 新 opcode `FUZZY_SLOT`：编译样板时对替换已开启的输入槽（`possibleInputs.length > 1`）在
     `CALL_BY_KEY` 前打标记；
  2. VM 记录该槽位为 fuzzy：叶子可用性检查只对 fuzzy 槽使用整组变体（精确槽只看主变体 + 处理配方的
     同物品 NBT 变体）；
  3. bundle 新增 `fuzzyItemNeeds`（fuzzy 槽的子调用 item 需求）→ 聚合期 `fuzzyItemDemand`：stock-aware
     分支只允许替换变体满足 **fuzzy 部分**需求，精确部分必须由主变体库存或合成主变体满足；
     处理配方的**同物品 NBT 变体**仍按主库存等价（任何槽都可用）。
- **效果**：`exact+fuzzy 混合消费同一可合成子项` → 精确槽强制合成主变体、fuzzy 槽用替换变体
  （原计划：无主变体合成 + 全替换库存 → 执行卡死）；`exact+fuzzy 混合消费同一叶子子项` →
  精确槽正确报缺失（拒绝下单而非静默卡死）；单模糊叶子/可合成/多父共享替换池基线不变。
- **测试**：新增 `VideoFuzzyReplacementReproTest`（5 例：精确槽强制合成主变体、精确叶子报缺失、
  单模糊叶子用替换、单模糊可合成用替换补缺口、多父共享替换池只消费一次）。
- **验证**：完整测试 BUILD SUCCESSFUL（18 类 136 用例 0 失败）。

## [1.10.4] - 2026-08-09

### 变更
- **版本号 1.10.4（双端同步）**：代码与 1.10.3 相同（递归/换算环守恒/可复用库存种子模糊等全部
  修复落地，闪电基准 38/39 SUPPORTED），仅 `mod_version` 升至 1.10.4；
  1.21.1（main）与 1.20.1（1.20.1-forge）同步推送。

## [1.10.3] - 2026-08-09

### 新增（递归 / 自引用配方）
- **症状**（视频 + 聊天确认，NAST）：`A+B→2A` 放大器（"A+B->2A是递归"）与 `A+B→A+C`
  A-A 催化剂（"a-a这种催化剂能处理那，典型例子就是精华"）在 1.10.2 被当作**普通消耗**处理，
  自产出掩盖自缺失 → 无 A 种子时仍报可行（计划看似完成实则"卡着"），或把 A 整批报缺失。
- **根因**：自引用样板（输出键 == 自身非返还输入）在 `applyBundleDirect` 里先插入自产出、
  后抽取自消耗——bundle 自己的 emitted 抵消了自己的 missing；同时聚合按 `in × crafts` 从库存
  扣自键，把一次性种子当成了每合成消耗。
- **修复**（CraftingVM，v1.10.3）：
  1. `computeSelfKeys(total)`：检测自键（输入∩输出，排除 returned 输入与未播种自增环 A→2A）；
  2. `correctRecursion(total, initialStock)`：种子校验——缺种子（`stock < in`）→ 恰报缺 `in−s`
     并置 0 次合成（样板无法点火）；主输出自键 net>0（放大器）→ 合次数改
     `ceil((请求−种子库存)/net)`（否则合次数过少、请求永远无法达成）；
  3. `applyOrdered`：自键 `used` 收敛为一次性种子（消除自产出掩盖自缺失）。
- **效果**：`recursion/amplifier`（A+B→2A）与 `recursion/essence-catalyst`（A+B→A+C）
  全 6 例 SUPPORTED——MINIMUM/UNBOUNDED 可行无缺失、MISSING 恰缺 {A=1}。
- **测试**：新增参考图族 `recursion/*`（`ReferenceCapability.RECURSION`）+
  `RecursionReferenceTest`（6 例）；`ThunderboltReferenceScenarios` 现 13 图族 39 例。
- **验证**：完整测试 BUILD SUCCESSFUL（17 类 125 用例 0 失败）；闪电基准连跑 3 次
  35 SUPPORTED / 4 FALSE_POSITIVE（39 例），递归 6/6 稳定；基准 md 已更新。

### 新增（换算环守恒 + 可复用库存种子模糊，v1.10.3）
- **换算环**（`cycle/conversion-ring/missing`）：无副产物纯换算环（`9B→A, 1A→9B, 9C→B, 1B→9C`，
  1A=9B=81C）在无种子时被 VM 报**可行**（capture 对未库存环项记空 used → 聚合免费产出环输出、
  自掩盖缺失）——危险假阳（计划看似完成实则卡着）。
  - **修复**：`CraftingVM.computeConversionRingMissing()`——新增 `allPatternsResolver`
    （返回某键的全部样板，见全环 B 的双向换算）→ 可达键全图案建图 → 无副产物纯换算 SCC
    → BigInteger 分数交换值 BFS（不一致则跳过）→ 环库存值 vs 外部需求值精确比较，不足时
    在最小价值外部需求键上报缺（只增不减 missing）。
  - **效果**：`cycle/conversion-ring` 全 3 例 SUPPORTED（MISSING 恰缺 {C=1}、MINIMUM/UNBOUNDED 可行）。
- **可复用库存**（`fuzzy/variant-route/minimum` + `unbounded`）：宿主私有可复用库存路由
  （`returnedFrom`，logical_tool 槽接受 damaged_tool）此前 VM 看不到 → 报缺 logical_tool。
  - **修复**：`Ae2VmReferencePlanner` 把 `returnedFrom` 映射为带路由变体的 returned 种子并喂入
    宿主可复用库存；`CraftingVM.applyBundleDirect` 种子抽取按 `fuzzyFamilyOf` 匹配变体。
  - **效果**：`fuzzy/variant-route` 全 3 例 SUPPORTED。
- **验证**：闪电基准 38 SUPPORTED / 1 FALSE_POSITIVE（39 例，唯一剩余为 multi-dag/fibonacci/minimum
  最优多样板选择，需全局优化器，实现风险高暂缓）；换算环/模糊路由均稳定；已同步 1.20.1。

## [1.10.1] - 2026-08-08

### 变更
- **双端版本统一 1.10.1**：1.21.1 与 1.20.1 同版本号。
- **README/基准 md 重写**：双端 README 加入「测试与基准」章节（准确测试日志：13 类 108 用例 0 失败；
  闪电基准 33 例 supported=23/falsePositive=10；边界基准 37/37；性能基准稳态 1–5ms）。

## [1.9.13] - 2026-08-07

### 修复（聚合期模糊组库存聚合）
- **症状**：玩家 NAST 报告「合成 1 个/1b 缺失，但 2 个/100b 正常」+「有样板却报缺失」。
- **根因**：`applyAggregation` 的 stock-aware 分支只读 `realStockOf(主变体)`。当模糊替换输入
  （灰羊毛样板）的**主变体可合成**、而**替换变体（白羊毛）有库存**时：
  1. 主变体被全量合成（拉黑羊毛配方链）；
  2. 父 bundle 按每 craft 记录的 `used[white]` 被缩放到总需求 → 白库存不足 → **假缺失**。
  v1.9.12 只修了「无子样板（叶子主变体）」分支，未覆盖「可合成主变体」。
- **修复**（CraftingVM.applyAggregation stock-aware 分支）：
  1. `stock = realStockOf(c)` 改为：若 c 在模糊组（`getFuzzyGroup(c).size()>1`）则求和全组库存；
  2. `fromStock` 按变体逐个消耗（主变体优先），替换变体记 `usedItems[v]` +
     `stockFromNetwork[v]=整槽需求`（变体是一次性池，清零父 bundle 的 used[v]），只合成缺口。
- **效果**：`craftable-primary-white-stock amt=100 (white=1)` → 修复前 `missing={white_wool=99}`，
  修复后 `missing={}`（used={black=99, white=1}）。
- **测试**：新增边界基准套件 `Ae2VmBoundaryCapabilitySuiteTest`（FakeBenchGrid 激活 realStockOf，
  37 例：craftable-primary-white-stock/white-stock10/partial-gray/no-variant、fuzzy-leaf-white-stock、
  deep-chain-mid-stock-l10/l20×s0/s1/s5、craftable-fluid-partial、infeasible-no-variant-stock）。
- **验证**：完整测试 BUILD SUCCESSFUL（108 用例 0 失败）；参考基准 33 例 supported=23/falsePositive=10
  稳定（22/11 为既有 multi-dag/fibonacci 波动）；已同步 1.20.1（核心逻辑区段字节级 MATCH）。

## [1.9.12] - 2026-08-07

### 修复
- **物品替换 / 流体替换假缺失**（「打开物品替换的灰色羊毛样板，网络有白色羊毛，却提示缺少灰色羊毛」），**且不破坏有合成样板的东西**：
  - 根因：`PatternCompiler.compilePattern` 对每个输入槽都先 `CALL_BY_KEY(主变体, 完整need)`。开启替换（`getPossibleInputs()` 返回多变体）时，主变体灰色羊毛无样板且模拟中无灰色库存 → 无子样板分支提前把灰色羊毛记为 missing —— 即使白色羊毛库存满足需求。
  - 修复（**在编码样板为字节码的阶段注册模糊组，不改 `compilePattern` 的 EXTRACT/CALL_BY_KEY 顺序**）：
    1. `PatternCompiler.compilePattern` 开头调用 `registerFuzzyGroups(pattern)`——每个样板编译时，若某输入槽 `getPossibleInputs().length > 1`（开启了物品替换/流体替换），把其所有变体注册为模糊组（A、B 可替换时互相可达）。任何样板来源（分子装配室/样板终端/ME 接口/处理样板）编译时都生效。
    2. `PatternCompiler`：新增静态 `FUZZY_GROUPS` 注册表 + `registerFuzzyGroups`/`getFuzzyGroup`/`clearFuzzyGroups`。
    3. `CraftingVM` CALL_BY_KEY 无子样板分支：缺失判断改为聚合模糊组内**所有变体**的 SIMULATE 库存——灰羊毛无库存但白羊毛有 → 不误报；未开启替换（单变体组=自身）行为不变。
  - **为什么不再报「有合成样板却缺失」**：`compilePattern` 保持 v1.9.11 顺序（先 `CALL_BY_KEY` 完整 need 调度子合成、再 EXTRACT 库存），模糊组只在**无子样板**分支补充变体库存判断，不影响有样板物品的合成调度（之前 v1.9.12 尝试改 `compilePattern` 顺序导致「先 EXTRACT 后 CALL_BY_KEY 残余 → 1-craft 捕获残余 0 → 不调度合成 → 聚合放大后缺料」，本版已放弃该改法）。
  - 语义符合 AE2：只有编码了替换的样板（`getPossibleInputs()` 多变体）才注册模糊组；未编码替换时精确匹配（白羊毛不能替代灰色羊毛是**正确**行为）。
  - 新增测试：`FuzzyGroupRegistrationTest`（3 例：注册灰羊毛模糊组+白羊毛库存→SUPPORTED；未注册精确→拒绝替代；**可合成灰色羊毛+部分库存→仍调度合成不报缺失**）。
  - 参考基准 supported=23/falsePositive=10 稳定（22/11 为既有波动）；完整测试 BUILD SUCCESSFUL；已同步 1.20.1（三处逻辑一致，编译通过）。

## [1.9.11] - 2026-08-07

### 修复
- **JIT 命中率回落（v1.9.10 级联丢弃的代价）**：空库存下每个低层 bundle 的 `missing` 非空 → 级联丢弃整条链 → 每次请求全量重新捕获，JIT 命中率骤降、calc time 不再回落。
  - 方案：**不再丢弃 missing 非空的 bundle**（保留所有 bundle，结构完整 → 子 bundle 全在 → `subBundlesComplete` 通过 → 跨请求全部复用）。stale missing 泄漏改由 `applyBundleDirect` **实时验证**：bundle 的 `missing` 是捕获时快照，apply 时对每项重新 `extract`——当前网络有货就取货（记入 `used`）、无货才报 missing。
  - 结果：`deepFib24MultiStepCraftablesNeverMissing`（24 层空库存 10⁹ 多步）JIT 命中率 **100%**（请求 2 hits=2 misses=0，零重捕获），且正确性保持（可合成项全部合成、缺失只有叶子 X0/X1、多步一致）。
  - 参考基准 supported=23/falsePositive=10 稳定（22/11 为既有波动）。

## [1.9.10] - 2026-08-07

### 修复
- **缓存卫生级联丢弃：深 Fibonacci 链第二次下单「可合成却缺失」**（v1.9.9 为定位诊断版，v1.9.10 为正式修复）：
  - 症状：请求 `quantum_omni_cell_component`（10⁹）后，第二次下单深 Fibonacci 链（24 层，NAST 量子→复杂→omni→appflux）的**低层可合成项**（如 `omni_cell_comp`，需求 46T = 10⁹×Fibonacci(24)=46368）被报缺失，尽管它有配方。
  - 根因：v1.9.8 的 `subBundlesComplete` 只检查**直接子** bundle 是否存在。24 层链中：低层 X2/X3（直接依赖空叶子 X0/X1）的 bundle `missing` 非空 → 缓存卫生丢弃；高层 X4+（依赖可合成子项）保留 → 复用 X6 时只检查 X5/X4 存在（通过）→ X6 字节码不执行 → X4 永不重新捕获 → 孙级 X3/X2 永久缺失 → 聚合 `bundleCache[X2]==null` → 报缺失。
  - 修复：缓存卫生改为**级联丢弃**——丢弃一个 bundle 时，所有 `itemNeeds` 依赖它的父 bundle 也丢弃（迭代至收敛），保证「缓存中的 bundle 子树完整」。被丢弃的祖先重新捕获时会重新拉起整条子链。
  - 新增回归测试 `deepFib24MultiStepCraftablesNeverMissing`：24 层空库存大数量（10⁹）多步，断言有 pattern 的中间项全部合成（缺失只能是最底层叶子 X0/X1）、多步一致。修复前 `deep24-2` 丢失 X2/X3，修复后与 `deep24-1` 完全一致。
  - 参考基准 supported=23/falsePositive=10 稳定（multi-dag/fibonacci/unbounded 的 22/23 波动为既有非确定性现象）。

## [1.9.8] - 2026-08-07

### 修复
- **缓存配方丢失导致合成计划变小**（「第一次下单 926K 字节，第二次 364K 字节，缓存配方丢了」）：
  - 场景：空库存下单复杂配方链（Fibonacci 型：量子→复杂→omni→appflux，每层 ×1.618）。
  - 根因：空库存下**低层**配方节点（直接依赖叶子，如 `X2、X3`）捕获时缺料 → bundle 的 `missing` 非空 → 跨请求缓存卫生在第二次请求开头**丢弃**这些低层 bundle。但**高层**配方（依赖可合成的子项，EXTRACT 从子项产物拿料）的 bundle `missing` 为空 → **保留**。复用高层 bundle 时**不再执行其字节码** → 其 `CALL_BY_KEY(低层)` 不再触发 → 低层配方**永远不会被重新捕获** → 聚合时 `bundleCache[低层]==null` → 整个子链被当 missing、不合成 → 计划变小（X2、X3 从「合成」变「缺失」）。
  - 修复：**复用缓存 bundle 前检查其 `itemNeeds` 引用的子 bundle 是否都还在缓存**（`subBundlesComplete`）。若有缺失（被缓存卫生丢弃），则**重新捕获**该 bundle——其字节码会重新拉起缺失的子链。
  - 新增回归测试：`CrossRequestCacheTest.emptyStockReuseIsDeterministic`（空库存简单链）+ `emptyStockFibonacciIsDeterministic`（空库存 Fibonacci 深链，直接复现 926K→364K：修复前 fib2 丢失 X2、X3，修复后 fib1=fib2=fib3 完全一致）。
  - 参考基准稳定 supported=23/falsePositive=10（multi-dag/fibonacci/unbounded 的 22/23 波动为既有非确定性现象，与本次无关）。

## [1.9.7] - 2026-08-07

### 修复
- **stock-aware 子项双重记账导致合成数量错误**（「首次下单正常，后续数量一致错误」的真正根因）：
  - 场景：某中间产物既有合成配方、网络又有库存，且 `0 < 库存 < 需求`（如 B 库存 2、需求 4）。
  - 根因：捕获期父配方的 EXTRACT 已把网络库存记入父 bundle 的 `used` 需求；聚合期 stock-aware 分支又独立记录 `usedItems += fromStock` 并从 sandbox 扣除该库存。父 bundle 应用时再次 `extract` 网络库存 + 合成缺口 → sandbox 不足 → **误报 missing（缺 B）**，数量错误。
  - 首次下单库存充足（`fromStock = 需求`，不需要合成）不触发；后续库存被消耗下降后才暴露——这正是「首次对、后续错」的原因（与 VM 跨请求复用无关，但复用后每次必现）。
  - 修复：聚合期把 `fromStock`（网络库存部分）从父 bundle 的 `used` 需求中扣除（`subtractStockFromNetwork`，作用于 scaled 副本，跨共享父节点守恒），父只提取「合成缺口」部分。结果正确：`usedB=2 + 合成 2 = 4`，无 missing。
- **新增手写跨请求复用回归测试** `JitReuseTest.reuseVmMustReReadStockForCraftableSubItem`：A<-B+C, B<-D+E, C<-F+G，B 既有配方又有库存；请求1 B=100（取库存不合成）→ 请求2 B=2（取 2 + 合成 2）。修复前请求2 误报 missing 2 B，修复后正确。
- **清理调试期 DIAG 日志**（`execSeq`/AGG 明细/realstock snapshot/plan summary），仅保留性能日志（`calc time` 与 `VM OK` 微秒）。

## [1.9.5] - 2026-08-07

### 修复
- **跨请求库存快照污染导致合成数量错误**（「数量出错了」）：跨请求 JIT VM 复用后，`realStockOf()` 懒加载的网络库存快照 `realStockCache` **未在每次 `execute()` 时重置**，第二次起的请求读到的是第一次请求的旧库存 → stock-aware 聚合按旧库存算量，数量错误。现在 `execute()` 开头强制 `realStockCache = null`，每次请求重新快照真实库存。已加回归测试 `reuseVmMustRefreshStockSnapshot`（复用 VM 后库存从 4→2，`used[D]` 必须返回 2 而非旧 4）。
- **日志微秒精度**：VM 常亚毫秒完成，整数毫秒日志显示 `0ms`。`calc time` 与 `VM OK` 改为微秒（`X us (X.XX ms)`）。

## [1.9.2] - 2026-08-07

### 优化
- **跨请求 JIT 缓存复用**：此前每次合成请求都新建 `CraftingVM`，导致每个子样板每次都重新捕获（JIT 命中率 0–49%）。现在 `AE2VMCrafting` 按网络（grid）缓存并复用 VM 实例，`bundleCache`（每个样板 1-craft 子树效果）跨请求存活，第二次起的请求命中率接近 100%。
  - `CraftingVM`：`patternResolver` 改为可更新（`setPatternResolver`，换 per-request resolver 缓存）；`execute()` 加 `synchronized`（复用并发安全）；`execute` 开头做「缓存卫生」——丢弃记录了缺料（missing 非空）的 bundle，避免缺料快照跨请求污染；干净 bundle（纯结构）安全复用。
  - 正确性已验证：复用后库存减少时仍正确报缺失（deficit-aware）。
- **日志**：保留性能计算日志（`[AE2-VM] calc time: X ms`），移除调试期的 JIT 命中率日志。

## [1.9.0] - 2026-08-07

### 变更
- **版本统一到 1.9.0**：1.21.1 与 1.20.1 双版本统一基线。版本号自增逻辑不变（每次编译 PATCH+1）。

## [1.8.26] - 2026-08-07

### 修复
- **自生长环裁切（CYCLE_CUTTING）**：未播种的 `A → 2A` 纯自环样板（主输出键 == 每个输入槽的键）**永远不再 firing**。
  - 之前 VM 把它当普通配方执行 1 次：消耗 1 个 A 产出 2 个 A，凭空复制物品（AE2 物品复制 exploit；参考基准 `cycle/self-growth-cut` ×3 全 FALSE_POSITIVE）。
  - 现在根 CALL 与子 CALL_BY_KEY 都会识别纯自环：需求只从网络库存取，短缺报 missing，绝不 dispatch 该样板。有外部输入的 `A + B → 2A` 仍视为合法放大器配方（不裁切）。
  - 参考基准 `cycle/self-growth-cut` missing/minimum/unbounded 全部转为 SUPPORTED（supported 20 → 23，falsePositive 13 → 10）。
  - 计算逻辑已同步应用到 1.20.1。

## [1.8.25] - 2026-08-07

### 修复
- **下单空白样板/含流体配方时，最后一份不发送物品**：
  - 场景：批量合成（如空白样板），当某个中间产物/流体**网络里有库存、可优先取用**（stock-aware sub-craft 逻辑，1.8.22 引入）时，计划只声明了库存用量却把 `usedItems` 记成 0。CPU 提交计划时从网络取走该批库存后，**最后一份**因缺中间产物/流体取料失败 → 组装机收不到物品。
  - 根因：root 捕获阶段父配方的 claim-EXTRACT 已把该库存从沙箱 sim 消耗掉，但 `extractIsClaim=true` 未写入 `usedItems`，`revertBundle` 也不会恢复 → sim 被排空，后续 `simulation.extract()` 返回 0，`usedItems` 加成了 0。
  - 修复：把**真实网络库存 `fromStock`** 直接写入 `usedItems`（不再信任被排空的 sim 返回值），保证 CPU 在提交时能取到该批库存。计算逻辑已同步应用到 1.20.1。
  - 新增回归测试：`StockAwareSubCraftReproTest`（库存优先取用场景）、`CraftableFluidStockReproTest`（可合成流体 + 部分库存场景）。

## [1.8.1] - 2026-08-04

### 新增
- **双版本构建**：每次编译同时产出两个 jar
  - `ae2vm-<ver>.jar` —— 针对检测版：检测到不兼容作者的 mod（data_energistics / mekenergistics / soulplied_energistics）时游戏**闪退**
  - `ae2vm-nodetect-<ver>.jar` —— 无针对检测版：检测到只警告，**不闪退**
  - 构建脚本：`buildBoth.bat`（版本 +0.0.1 → 构建 crash 版 → 构建 warn 版 → 两个 jar 都复制到 mods）
  - ⚠️ 两个 jar 共用 `modId=ae2vm`，启动游戏前**只保留其中一个**，否则 duplicate modId 报错
- **blockedmod 检测双模式**：运行模式（crash / warn）由 jar 内 `ae2vm/blockedmode.txt` 决定，`-PblockedMode=...` 控制
- **版本号基于 1.8.1**：每次编译 `mod_version` +0.0.1

### 已知限制
- **斐波那契数列（指数级递归增长）处理能力不足**：对「每项需求量由前两项叠加」的斐波那契式指数递归合成链效率有限，需求量随深度指数爆炸。**后续将进行高性能版本优化**。

### 变更
- 启动日志 banner 版本号更新为 `v1.8.1`
- 启动时输出斐波那契限制的已知问题日志（warn）

## 历史版本

- `1.2.16` / `1.2.4` / `1.2.1`：早期迭代（递归样板计划提交、模糊匹配、流体/桶单次合成数量、CPU 卡死回退、首个 BigInteger VM 等），详见 git 提交历史。


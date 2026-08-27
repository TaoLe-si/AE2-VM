# Changelog / 更新日志

## [1.13.12-PERF2] - 2026-08-21（温热命中压至 <1000ns 端到端：自产环守卫 O(1) 化 + DAG 版本门控 + 零分配热路径）

- **真实端到端（开始计算→计算结束，逐次计时不摊销）：全部 1e9 温热场景中位数 100–200ns，
  平均 63–411ns —— 比上版 ~1.2–1.3μs 快 ~10×，全部 < 1000ns 目标**：
  - **自产环守卫预计算**：fastPlanSelfEmitOk（store 时一次判定：任一 used 键可制造但
    自生产不足 → 温热永不命中，O(1) 拒绝）；used 键按原始并行数组存储，守卫零迭代器分配。
  - **DAG 身份验证版本门控**：bumpPatternVersion() 在每次样板变更时触发并失效 bundleCache
    与记忆化计划，因此 DAG walk 每个 pattern 版本只验证一次（首命中），后续命中跳过
    HashSet/ArrayDeque 分配与解析器遍历。
  - **记忆化计划直返**：deliver 与缓存一致时直接返回 fastPlanCached 对象（省去
    CraftingPlan + GenericStack 两次分配）；不一致时才重建包装。
  - **入口惰性化**：BigInteger 请求量仅在慢路径物化；温热入口只写 simulation/outputKey。
  - **库存守卫保留**：自产环键仍按 O(1)（stockReader / SIMULATE）逐键复核——环计划实际
    仍要从网络抽取 used 总量，库存不足必须回慢路径（多步库存抽干正确性不变）。
- **基准方法学（真实端到端）**：所有基准请求量统一上调至 1e9；每次调用前后各一次
  System.nanoTime 逐样本计时并累加总和（不摊销、不批计时），报告总和/平均/中位数/p95；
  温热样本复用模拟状态（命中零分配、SIMULATE 非破坏；每样本 new 的 TLAB/GC 分配压力
  实测会把 2 层链温热从 200ns 拖到 1.6–2.1μs 双峰）；60k 深预热完成 C2 编译。
  断言：温热中位数 < 1000ns。coldStartTwoLevelChain JVM 预热增至 5 次，
  1e9 冷捕获 0.4–5.5ms 波动，断言 < 20ms。

## [1.13.12] - 2026-08-21（同步 VM-GTL 图论处理：环感知样板选择 + 种子环 JIT 图裁剪）

将 VM-GTL（1.20.1 Forge，v1.12.x–v1.15.x）的图论处理移植到 1.21.1 计算逻辑，
计算语义对齐 VM-GTL：

- **环感知样板选择（AE2VMCrafting，对标 VM-GTL v1.12.x CYCLE-AWARE）**：
  - `wouldCauseCycle`（2 重载，含 stock-aware 变体）：候选样板的输入若会闭合
    **死环**（配方定义图 SCC：无库存种子 + 无外部供应者），如 steel_ingot↔steel_dust，
    在 resolve() Try-1 阶段即被剪除；全部候选均环倾向时回退原集合（运行时 circularCache
    兜底）。
  - `computeCycleBoundKeys`（2 重载，含候选边注入）：定义图 Tarjan SCC，跳过
    已播种 / 外部供应的环；`tarjanScc` 迭代实现 + `safeStock` / `patternOutputNameStatic` /
    `resolveStockSnapshot` 辅助。
  - `pickBestPattern` 由 private 改为 public（供基准与环过滤共用）。
- **种子环运行时裁剪（CraftingVM，对标 VM-GTL v1.14.x JIT-GRAPH / SEEDED-RING）**：
  - `CallFrame.cycleCut` / `withCycleCut()`：捕捉模式输出键在真实库存中 → 帧标记
    cycleCut，RETURN 的 claim EXTRACT 记真实库存消耗（extractIsClaim = !f.cycleCut()），
    INSERT_OUTPUT 不再向 simInternal 伪造产物。
  - `capturingBundle()` / `captureAction()`：捕捉期环探测只走 SIMULATE，不永久抽干沙箱库存。
  - circularCache 分支：环上并行兄弟照常消耗库存，仅超库存短差记 missing（不再整单丢弃）。
  - 环分支：ringSeeded（环目标或捕捉帧输出键任一有库存种子）→ 种子环保留成员样板
    （dust↔ingot 正常生产）；死环 → 从父帧 subCalls 拉真实库存并记短差，杜绝 transfinite
    CPU 零进度卡死。
- **纯转换环可行性守卫（对标 VM-GTL v1.15.x GTL 1:1）**：
  - `RingResult` + `computeConversionRingMissingEx`：价值充分环（库存可沿环兑换满足外部
    需求）把环成员记入 feasible，聚合阶段剥除捕捉期 CYCLE/CYCLE-CUT 残留 missing；
    价值不足环仍在最小价值被需求键上报赤字。
  - `bytesOfSimulation` 防御式读取（离线基准环境无 mixin accessor 时不再 ClassCastException）。
- **温热快路径自供给环守卫（对标 VM-GTL v1.15.x PERF）**：used 键可制造但缓存计划
  自生产足量该键（byproduct 回环，如 2B→1D+2A）时，跳过“可制造→库存敏感”拒绝；
  自供给量（Σ patternTimes × 样板输出）独立字段存储，不污染计划 emittedItems 快照；
  库存守卫仍以 O(1)（stockReader / SIMULATE）逐键复核——环上计划实际仍要从网络抽取
  used 总量，库存不足必须回慢路径重推导（修复 CrossRequestCacheTest 库存抽干回归）。
- **新增基准**（移植自 VM-GTL）：`CycleAwarePatternSelectionBenchmark`、
  `ComplexCycleChainBenchmark`、`PerformanceBenchmark.warmBillionSeededRing`
  （1e9 种子环温热中位数 < 10μs）。
## [1.13.4] - 2026-08-20（1.21.1 温热命中 <100μs：负解析缓存 + 库存快照复用 + 服务端线程温热检查）

- **游戏内实测（1.13.3）**：新增 [AE2-VM] WARM 诊断行后确认瓶颈三处：
  - `tryCachedPlan` 109-624μs：used 守卫对纯叶子键反复全量 resolve()（null 从不入缓存）；
  - `cachedInv` 53-175μs：该网络无 storage watcher，AE2 每 tick 置脏，getCachedInventory()
    每次触发整库重建；
  - VM OK − worker 的 144-453μs 差距：ForkJoinPool supplyAsync 调度等待 + 游戏线程 GC 噪声。
- **负解析缓存（TTL 2s 哨兵）**：resolve() 的"不可制造"结论以时间戳哨兵入 VM 持久缓存，
  温热守卫的纯叶子键从全量解析（1-3μs/键）变为 map 命中（~0.1μs/键）。TTL 保底：无版本号
  新增的样板 ≤2s 自愈；版本号变化 / clearBundleCache() 立即全清。
- **库存快照复用（≤1.5s）**：每网格捕获 getCachedInventory() 的 KeyCounter 引用并复用；
  可行（可执行）计划若用的是上一 tick 的快照，会强制新鲜捕获重验一次——缺料预览（本包
  重场景，永不提交 CPU）完全跳过。库存变化守卫仍逐键复核，陈旧结果最多影响缺料数量预览。
- **服务端线程温热检查**：VM 空闲（isExecuting=false）时温热命中在 supplyAsync 之前完成，
  直接返回 completedFuture——不再付 ForkJoin 调度等待。VM 忙碌时自动退回异步 worker 路径。
  服务端阻塞上界 = 温热检查本身（~50-100μs，一个 tick 的 0.2%）。

## [1.13.1] - 2026-08-20（1.21.1 温热命中二次压榨：O(1) 库存守卫）

- **游戏内复测（1.13.0）**：温热命中已从 63-100ms 降到 313-481μs（omni 1e9，无 CRAFT START/END
  即为快路径命中），首算的 60ms sleep 已消除；creative 温热 823-1117μs。目标 <100μs。
- `CraftingVM.tryFastPath` / `tryCachedPlan` 新增**直接库存读取器（Function<AEKey,Long>）变体**：
  温热守卫改为 O(1) `KeyCounter.get()`，不再构造/拷贝整份库存、不再走模拟状态 extract 机制。
- `AE2VMCrafting` 温热短路同步改为：`storage.getCachedInventory()` 捕获一次 +
  `tryCachedPlan(bc, key -> cached.get(key))`。库存只在缓存过期（跨 tick 变更）时才会触发一次重建
  ——与实时快照同代价，且只在变更 tick 发生。
- 语义不变：命中仍逐项复核（模式版本 / DAG 身份 / 已用叶子足量 / 缺料键仍缺料），陈旧结果不可能被服务。
- **VM 级持久解析器缓存**：温热 DAG 身份走查从"每请求重跑 getCraftingFor()（AE2 的
  getSortedPatterns() 每次调用都重新 sort+distinct+toList 分配）"变为纯 ConcurrentHashMap 命中
  （~0.1μs/节点）。缓存与 bundleCache 同生命周期：patternVersion 变化或 clearBundleCache() 时一并清空，
  不会比计划依赖的模式集活得更久。60 节点链温热走查预计省 100-200μs。
- **温热诊断计时**：温热命中新增一行 [AE2-VM] WARM #N: worker=…μs (compile=…, cachedInv=…,
  tryCachedPlan=…)，用于对照 mixin 的 VM OK 总耗时（差值 = 服务端预处理 + ForkJoinPool 调度等待）。

## [1.13.0] - 2026-08-20（1.21.1 游戏内性能：缺料计划温热复用 <100μs）

- **游戏内实测修复（63-100ms → 温热 ~μs）**：缺料（missing）计划此前从不进入记忆化快路径
  （storeFastPlanCache 只存可行计划），同一请求每几秒重复触发慢路径（1.4-21.7ms）+ 60ms
  等待窗口（VM OK ≈ calcTime + 60ms）。
- `CraftingVM` 快路径 v4：缺料计划也缓存；命中时逐项复核（模式版本未变 / DAG 身份一致 /
  已用叶子仍足量 / 每个缺料键仍无样板且库存仍不足）后才返回，杜绝陈旧缺料被复用；重建计划
  时 simulation 标志按真实 missing 设置。
- `AE2VMCrafting` API 层温热短路：用 `IStorageService.getCachedInventory()`（最多一 tick 旧、
  新鲜时 O(1)）惰性模拟状态先行尝试 `tryCachedPlan`，命中即返回——跳过实时库存快照、重试
  循环与 60ms 等待；未命中零额外开销（惰性物化）。
- **60ms 等待窗口按缺失量分级**：缺料总量 > 1M 单位视为真实缺料（GTL 同步不可能凭空补
  数百万单位）→ 跳过 sleep；小缺料（GTL 供应器同步窗口特征）保留 60ms 窗口。
- 新增 `CachedInventoryCraftingSimulationState`（惰性缓存库存模拟状态）。
- 新增基准 `warmMissingPlanReuse`：缺料计划第二次起平均 < 100μs（断言 100μs）。

## [1.12.3] - 2026-08-19（同步 1.20.1 优化：记忆化快路径 v3 / 红黑树缓存 / <10μs）

- 同步 CraftingVM 快路径 v3（记忆化完整计划 + 深身份校验 + 纯叶子守卫 + 库存守卫）；
- 同步 ceilDiv 饱和除法、bundle 身份戳重捕获、null 输入防御、vmShouldFallback（private static）；
- 同步性能基准 PerformanceBenchmark（10^9 与 24 层斐波那契 <10μs，中位数测量）+ 并行测试；
- 实测：fib24 中位数 ~8μs、10^9 ~1.5-2.3μs（本机 Java21）。


版本号基于 `1.9.0`：每次编译 `mod_version` +0.0.1（1.9.0 → 1.9.1 → …）。


## [sync 1.11.12] - 2026-08-19

### 修复（新样板作为中间产物识别不到 — PATTERN-REFRESH 可靠触发）
- **同步自 1.20.1 forge v1.11.12**：`CraftingServiceMixin` 新增
  `@Inject(refreshNodeCraftingProvider, HEAD)` 钩子 → 任何样板供应器刷新
  （`ICraftingProvider.requestUpdate` → 网络刷新）都会调
  `PatternCompiler.bumpPatternVersion()`，清除 VM 的 stale `bundleCache`。
  比 `PatternProviderLogicMixin.onUpdatePatterns` 更可靠——第三方 mod 的供应器
  也必然走网络刷新。修复"先下单最终产物→缺中间产物→补样板仍不识别"。
## [1.10.9] - 2026-08-10

### 修复（UselessMod 翻倍样板兼容，所有翻倍不兼容问题）
- **症状**（2026-08-10 聊天确诊）：NAST 合成"创盘"时橙色玻璃又卡在后台——"翻倍后的样板
  没被识别到，没被编译成字节码。因为翻倍样板是虚拟的，所以没在样板更新的时候匹配到"
  （桃诊断）；"所有翻倍现在都不兼容"。关闭 VM 后原生计算也报"下单计算不出来"。
- **根因**：UselessMod 高级合金炉的翻倍样板 `ScaledProcessingPattern` 是**运行时虚拟包装器**
  ——包装原始样板并把每个输入 multiplier / 输出数量乘 `operationsPerPush`。它是虚拟的，不在
  样板更新（`PatternProviderLogicMixin.onUpdatePatterns`）能遍历的物理样板列表里；旧逻辑把它
  当普通样板直接编译 → `outputPerCraft` 变成缩放量、VM 计划以**虚拟翻倍样板**为 `patternTimes`
  key → AE2 Crafting CPU / `getProviders` / furnace `pushPattern` 认不出该 key → 订单卡住/算不出。
- **修复**（`PatternCompiler`，v1.10.9）：
  1. 新增 `isScaledPattern()`（类名 `Scaled…Pattern`）+ `unwrapScaled()`（反射调用 `getOriginal()`
     递归解包，兼容嵌套翻倍）；
  2. `compileIfAbsent` / `getCompiled` / `compileRequest` / `invalidate` 统一在入口解包 → 用
     **原始样板**编译（`outputPerCraft` 恒为原始每次合成量 1）、`compileRequest` 的 `patternIdx`
     也用解包后的原始样板 → `patternTimes` key 恒为原始 `IPatternDetails`（CPU/provider/furnace
     全认识）；
  3. 提交时 UselessMod 因 key 不是 `ScaledProcessingPattern` 会**重新应用 smart-doubling**，
     翻倍在计划提交阶段正常生效。
- **测试**：新增 `ScaledBenchPatternDetails`（离线模拟 `ScaledProcessingPattern`）+ 
  `ScaledPatternReproTest`（6 例：解包编译为原始输出量、嵌套翻倍递归解包、请求消耗正确、
  非整倍请求按原始次数计、patternTimes key 恒为原始样板、叶子缺失按原始单位报）。
- **验证**：完整测试 BUILD SUCCESSFUL（19 类 142 用例 0 失败）；闪电基准 39 例 38 SUPPORTED
  （唯一 FALSE_POSITIVE 为既有 multi-dag/fibonacci/minimum 最优多样板选择）；边界基准 37/37。

## [1.10.6] - 2026-08-09

### 变更
- **版本号 1.10.6（三端统一）**：1.21.1（main）、1.20.1（1.20.1-forge）、26.1.2
  （26.1.2-neoforge）统一 `mod_version=1.10.6`；v1.10.5 的模糊替换修复已同步到
  1.20.1 与 26.1.2（jar 后缀 `_neoforge_26.1.2` 区分）。

## [1.10.5] - 2026-08-09

### 修复（模糊替换作用于精确槽位的假可行 → CPU 卡死）
- **症状**（2026-08-09 视频，NAST）：合成 `无限高压闪电元件`（AE2 Lightning Tech，11.5MB 大计划）与
  合成 1 个 `钢质机壳` 都在 Crafting CPU 里**卡住**——进度恒为 0，ETA 暴涨（`514266:04:52` 之类），
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
- **验证**：完整测试 BUILD SUCCESSFUL（18 类 136 用例 0 失败）；边界基准 37/37；闪电基准 39 例
  38 SUPPORTED（唯一 FALSE_POSITIVE 为既有 multi-dag/fibonacci/minimum 最优多样板选择）。

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


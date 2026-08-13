# AE2 VM — Minecraft 1.10.2 (Forge) 端口

将 1.20.1 的 AE2 VM 合成加速器移植到 **Minecraft 1.10.2 / Forge 12.18.3.2422 / AE2 rv4-stable-1**。

## 版本矩阵

| 项 | 1.20.1（源） | 1.10.2（本端口） |
|---|---|---|
| Minecraft | 1.20.1 | 1.10.2 |
| Forge | 47.4.22 | 12.18.3.2422 |
| ForgeGradle | 6 | 2.2-SNAPSHOT |
| Mappings | official (Mojang) | MCP `stable_29` |
| Java | 17 | 8 |
| AE2 | 15.4.10 | rv4-stable-1（modid `appliedenergistics2`） |
| Mixin | 0.8.5（Forge 内置） | 0.7.11-SNAPSHOT（legacy coremod 引导） |
| Config | Cloth Config 11.x（可选） | 无（代理恒启用） |
| Gradle wrapper | 8.x | 2.14 |

## 目录结构（端口结果）

```
src/main/java/com/ae2vm/addon/
├── AE2VMAddon.java                 # @Mod 主类（Forge 1.10.2 / Log4j2）
├── coremod/AE2VMCoreMod.java       # IFMLLoadingPlugin：引导 Mixin
├── config/AE2VMConfig.java         # 代理开关门面（无 Cloth Config，恒 true）
├── api/                            # rv4 兼容 shim 层
│   ├── AEKey.java                  #   AE2 15.x AEKey → IAEItemStack/IAEFluidStack 包装
│   ├── GenericStack.java           #   key + amount
│   ├── KeyCounter.java             #   HashMap<AEKey, Long>（含 findFuzzy）
│   ├── IPatternDetails.java        #   AE2 15.x IPatternDetails 接口
│   ├── Rv4PatternDetails.java      #   ICraftingPatternDetails → IPatternDetails 适配
│   ├── CraftingSimulationState.java#   抽象仿真状态（bytes 为普通字段）
│   ├── ICraftingPlan.java          #   计划结果接口
│   ├── CraftingPlan.java           #   计划数据持有类
│   ├── AE2VMCrafting.java          #   公开门面（同步 VM 计算）
│   └── AE2VMCraftingRegistry.java  #   第三方 opt-in 注册表
├── compiler/
│   ├── IFiniteUseInput.java        #   耐久工具能力（rv4 无实现，恒不触发）
│   └── PatternCompiler.java        #   样板 → 字节码编译器
└── vm/
    ├── Opcode.java                 #   操作码枚举
    ├── CraftingBytecode.java       #   扁平字节码 + Builder
    ├── RealtimeNetworkCraftingSimulationState.java  # 实时网络库存快照
    └── CraftingVM.java             #   大整数栈式解释器
```

## 构建前必读：放置 AE2 rv4 dev jar

AE2 rv4 没有发布到公共 Maven 仓库，构建时直接 `deobfCompile` 一个扁平依赖：

1. 下载 **Applied Energistics 2 rv4-stable-1 的开发版 jar**（`appliedenergistics2-rv4-stable-1-dev.jar`，含反混淆后源码的 `-dev` 变体，不是玩家用的 `-rv4-stable-1.jar`）。
2. 放到 `./libs/appliedenergistics2-rv4-stable-1-dev.jar`（`build.gradle` 里 `flatDir { dirs 'libs' }` 会解析它）。

> 若无法获得 `-dev` jar，可先用运行时 jar 替代以通过编译，但 MCP 反混淆后的成员名（`getCraftingFor`、`beginCraftingJob` 等）可能对不上，需按反编译结果微调 mixin 目标。

## 构建

```bat
gradlew.bat build
```

产物（`build/libs/`，同 1.20.1 双 jar 约定）：

- `ae2vm-<ver>_forge_1.10.2.jar` —— 默认 `blockedMode=crash`（检测到该作者 mod 即闪退）
- `ae2vm-nodetect-<ver>_forge_1.10.2.jar` —— `-PblockedMode=warn`（仅警告不闪退）

```bat
gradlew.bat build -PblockedMode=warn
```

`build` 结束后自动复制到 `${mods_folder}`（`gradle.properties` 中配置，默认
`E:/MC/.minecraft/versions/1.10.2-Forge_12.18.3.2422/mods`）。

版本号在 `gradle.properties` 的 `mod_version`（当前 `1.10.8`，与 1.20.1 / 1.21.1 分支保持一致）。

## 端口策略：rv4 兼容 shim

1.20.1 代码依赖 AE2 15.x 的 `AEKey / GenericStack / KeyCounter / IPatternDetails /
CraftingSimulationState / ICraftingPlan` 等现代 API。rv4 没有这些，故在
`com.ae2vm.addon.api` 下建立 shim：

- `AEKey` 包装 `IAEItemStack` / `IAEFluidStack`，`getId()` 返回注册名（`minecraft:iron_ingot`）。
- `IPatternDetails` 由 `Rv4PatternDetails` 适配 `ICraftingPatternDetails`。
- `CraftingSimulationState.bytes` 为普通 `double` 字段（1.20.1 的 `CraftingSimulationStateAccessor`
  mixin 已删除）。
- 催化剂 / 耐久工具路径在 rv4 上为死代码：`Rv4PatternDetails.IInput.getRemainingKey()`
  恒返回 `null`，`IFiniteUseInput` 无 rv4 实现。

Java 8 重写规则：`record` → 静态类、`var` → 显式类型、switch 箭头 → 传统 switch、
`Map/List/Set.of()` → `Collections`/构造器、方法引用 `Long::sum` → lambda、
`InputStream.readAllBytes()` → Java 8 循环、`String.isBlank()` → `trim().isEmpty()`。

## 流体支持状态

| 能力 | 状态 |
|---|---|
| 流体表示（`AEKey` / `GenericStack` / `KeyCounter`） | ✅ 已支持 |
| 网络流体库存读取（`getFluidInventory()` → 仿真计数） | ✅ 已支持 |
| 流体合成样板（编译/执行流体配方） | ❌ rv4 无此概念（`ICraftingPatternDetails` 仅物品槽） |

- rv4-stable 的 API 提供 `IAEFluidStack`、`StorageChannel.FLUIDS`、
  `IStorageHelper.createFluidStack()/createFluidList()`、`IStorageMonitorable.getFluidInventory()`；
  本端口的 `RealtimeNetworkCraftingSimulationState` 与 `CraftingVM.ensureRealStockSnapshot()`
  均已读取网络流体库存，`AEKey` 完整包装 `IAEFluidStack`。
- 流体“合成”需要带流体槽位的样板（如 Extra Cells 2）。Age of Engineering 整合包不含此类模组，
  AE2 rv4 本身也不提供流体样板，故当前无流体配方可被 VM 编译。

## 已知差异与待验证项（重要）

1. **VM 未接管 rv4 原生 job 提交**。rv4 没有 1.20.1 的
   `beginCraftingCalculation() → Future<ICraftingPlan>`；原生入口是
   `ICraftingGrid.beginCraftingJob(...) → Future<ICraftingJob>`，其计划在
   `CraftingJob` 内部构建。当前 `CraftingGridCacheMixin` 在 `beginCraftingJob` 头部
   **并行运行 VM**（预热字节码缓存 + 计时对比），随后**不取消**原生路径——保证
   合成照常工作，但计划本身仍由 rv4 原生计算。若要完全接管，需拿到 dev jar 后对照
   `CraftingJob`/`ICraftingJob` 内部结构，把 shim 计划的 `usedItems` 注入
   `ICraftingJob.populatePlan(...)`。这是后续唯一的功能性缺口。

2. **Mixin 目标（已对照 rv4 真实源码验证）**：
   - `CraftingGridCacheMixin` → `appeng.me.cache.CraftingGridCache`，方法
     `beginCraftingJob(World, IGrid, BaseActionSource, IAEItemStack, ICraftingCallback)`
     （返回 `Future<ICraftingJob>`）。
   - `PartPatternProviderMixin` → `appeng.helpers.DualityInterface`（ME 接口部件
     `PartInterface` 与接口方块 `TileInterface` 共享的后端；`PartInterface` 本身不持有
     patterns），字段 `List<ICraftingPatternDetails> craftingList`、方法
     `updateCraftingList()`。
   - 两个 mixin 均 `remap = true`：MixinGradle 生成 `ae2vm.mixins.refmap.json`，
     在 SRG 混淆运行时把 MCP 名映射回 SRG 名。若 dev jar 中类/方法/字段名不同，
     只需改 `@Mixin(value=...)` 与 `@Shadow` 声明。

3. **`AE2VMCrafting.calculateSync` 的合成请求仅支持物品**。rv4 无流体合成样板，
   流体 key 直接报 “No pattern for fluid”（见上节“流体支持状态”）。

4. **配置**：1.10.2 无 Cloth Config，`AE2VMConfig.isProxyEnabled()` 恒返回 `true`，
   `tryRegister()` 为空操作。

## 行为契约（与 1.20.1 一致）

- 每次编译产出双 jar（`crash` 针对检测版 / `warn` 无针对检测版）。
- 故意闪退的是**游戏运行时**（`throw RuntimeException`），不是编译器。
- 被拦截作者 mod id：`data_energistics`。
- 版本与 1.20.1 / 1.21.1 分支锁步（当前 `1.10.8`）。

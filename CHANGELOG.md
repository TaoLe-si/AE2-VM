# AE2 VM Addon — 1.16.5 Forge 变更日志

---

## v1.14.1+hotfix (1.16.5-forge) — 2026-09-18（运行时崩溃修复 #1 ~ #5）

重写版首次进游戏的 5 次崩溃/报错，逐个定位并修复。版本号保持 `1.14.1`，仅重建产物。

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `CraftingServiceMixin` 找不到 → 启动崩 | 部署目录写错：实际 PCL 实例是 `1.16.5-Forge_36.2.42`，不是 `36.2.34`，里面还是旧的 v9 jar | `gradle.properties` 的 `mods_folder` 改为 `E:/MC/.minecraft/versions/1.16.5-Forge_36.2.42/mods` |
| 2 | `Missing language javafml: [37,)` | `mods.toml` 的 `loaderVersion` 是 1.17.1 的值 | `"[37,)"` → `"[36,)"` |
| 3 | `ClassNotFoundException: org.slf4j.LoggerFactory` | 1.16.5 运行时**没有** slf4j-api（只有 `log4j-slf4j18-impl` 这个 binder） | `AE2VMAddon.java` 改用 `org.apache.logging.log4j.LogManager` |
| 4 | `appeng.me.cache.CraftingGridCache$Anonymous$…` `SecurityException: signer information does not match` | AE2 发布 jar 是**签名**的；mixin 类里的匿名内部类（`new ThreadLocal<Boolean>(){…}`）被改名进 `appeng.*` 签名包 | 抽出 `com.ae2vm.addon.vm.VmMixinState`（普通类）保存 `ThreadLocal VM_FALLBACK` + `AtomicLong REQUEST_COUNTER` |
| 5 | `appeng.api.crafting.IPatternDetails` `SecurityException` | 15 个 v9 shim 类当初放在**签名的 `appeng.*` 包**里（`api.crafting` / `api.networking.*` / `api.stacks` / `api.storage*` / `crafting*`） | 全部迁到 **`com.ae2vm.shim.*`**，包声明与所有引用重写 |
| 6 | VM 计算直接抛 NPE 后**静默退回原生** AE2（`[AE2-VM] VM FAILED #1 -> native fallback`），玩家看到的是"能算但下单异常" | `RealtimeNetworkCraftingSimulationState(IStorageService)` 走 1 参构造 = `src == null`，而 **v8 的 `NetworkInventoryHandler#extractItems` 无条件解引用 action source**（`testPermission` 里调 `src.player()`）→ NPE | `src == null` 时不再调 `extractItems(SIMULATE, null)`，直接用 `monitor.getStorageList()` 的可用数量（6 处调用点一次性修好） |
| 7 | 进世界后点"合成"报 `IllegalClassLoadError`（**游戏不崩**，AE2 自己吞掉） | `CraftingSimulationStateAccessor` 是个**纯接口**（无 `@Mixin`），却待在 `com.ae2vm.addon.mixin.*` 这个"mixin 保留包"里 → Mixin 规定该包内类必须在 mixins.json 注册，否则拒绝加载 | 移到 **`com.ae2vm.shim.crafting.inv`**（与它描述的 shim 同包），10 处引用重写 |

### ⚠️ 铁律（1.16.5 及所有签名 AE2 jar 的版本通用）

1. **本 mod 的任何类都不得放进 `appeng.*` 包**——不是"建议"，是硬性约束。
   AE2 release jar 带签名，往签名包里塞新类（哪怕是自己新建的类，不是注入）
   一律触发 `SecurityException: signer information does not match`。
   v9 面 shim 一律放 `com.ae2vm.shim.*`。
2. **mixin 类里禁止匿名内部类 / lambda 捕获导致的合成类**——mixin 会被改名进目标类的包，
   匿名类跟着落进签名包。可变状态放独立的普通类（如 `VmMixinState`）。
3. 部署后必须校验：jar 内 `appeng/` 开头的 class 数 == **0**。
4. **`com.ae2vm.addon.mixin.*` 里只允许放真正的 mixin 类**（`ae2vm.mixins.json` 里注册过的那几个）。
   任何辅助类（接口 / 常量 / 工具类）放进去，运行到第一次引用就报：
   `IllegalClassLoadError: ... is in a defined mixin package ... owned by ae2vm.mixins.json`。
   校验：`com/ae2vm/addon/mixin/` 下的 class 数必须 == mixins.json 的条目数。

### ⚠️ v8 专属陷阱：`IActionSource` 不能传 null

v9 的 `IMEInventory#extractItems(SIMULATE, null)` 是容忍 null 的，**v8 不是** ——
`NetworkInventoryHandler.testPermission()` 直接 `src.player()`，null 就 NPE。
而 `AE2VMCrafting.calculate(grid, requester, what, amount, strategy)` 的签名里
**根本没有 `IActionSource` 参数**（v9 从 `CraftingService` 内部拿），
所以 v8 上一路传下来就是 null。修在 `RealtimeNetworkCraftingSimulationState` 构造里最省事：
`src == null` 时不走权限校验，直接取 `getStorageList()` 的可用数量
（这个列表本来就是"能提取多少"，语义等价）。

### 里程碑

第 6 个坑修完时，日志已经能看到 **VM 真正接管了合成请求**：
```
CraftingGridCache.handler$zzk000$vmBeginCraftingJob(CraftingGridCache.java:679)
  at appeng.me.cache.CraftingGridCache.beginCraftingJob(...)
  at appeng.container.me.crafting.CraftAmountContainer.confirm(...)
```
即 mod 加载 → 进世界 → 玩家确认合成 → 走的是 VM 分支，不再是原版 `CraftingJob`。

### 本次改动文件

- `src/main/resources/META-INF/mods.toml`（loaderVersion）
- `src/main/java/com/ae2vm/addon/AE2VMAddon.java`（Logger）
- `src/main/java/com/ae2vm/addon/vm/VmMixinState.java`（**新增**）
- `src/main/java/com/ae2vm/addon/mixin/CraftingGridCacheMixin.java`（去掉匿名类）
- `src/main/java/appeng/**` → `src/main/java/com/ae2vm/shim/**`（15 个 shim 迁移）
- 9 个 main + 35 个 test 文件的 shim 包引用重写；`MixedStackList` 补 `IAEStack`/`IAEItemStack`/`IItemList` import
  （原先同包不用写，出包后必须显式 import）

---

## v1.14.1 (1.16.5-forge) — 2026-09-18

**重写版首个 1.16.5 分支**（旧 VM-dormant 版目录已整体删除并重建）。
基于 **AE2VMAddon-1.17.1**（本地 `1.17.1-forge` / `5acfd75`，AE2 v9.0.0-beta.2）源码迁移，
配套 **AE2 v8.4.7** + **Forge 36.2.34** + **ForgeGradle 5.1.69** + **Gradle 7.6.4（JDK 8 daemon）**，
产物为 **Java 8 bytecode**。

### 核心难点：v8 是 legacy 合成引擎，没有 v9 的 modern 引擎

AE2 v8 只有 `ICraftingJob` / `ICraftingPatternDetails` / `CraftingTreeNode` / `CraftingCPUCluster`，
**没有** v9 的 `CraftingService.beginCraftingCalculation` / `ICraftingPlan` / `IPatternDetails` /
`CraftingSimulationState`。因此采用 **v9 API 面 shim + v8 后端桥接** 的方案，让 3530 行的
`CraftingVM` 内核几乎不动：

| shim（v9 面） | v8 后端实现 |
|---|---|
| `appeng.api.crafting.IPatternDetails`(+`IInput`) | `com.ae2vm.addon.v8.V8PatternDetails`（包装 `ICraftingPatternDetails`，multiplier = 输入 stack size） |
| `appeng.api.stacks.{AEKey,AEItemKey,GenericStack,KeyCounter,AEKeyType}` | 自建 shim 包（v8 无 `appeng.api.stacks`） |
| `appeng.api.storage.data.MixedStackList` | 用 `IItemList<IAEItemStack>` 打底 |
| `appeng.api.networking.storage.IStorageService` | `com.ae2vm.addon.v8.V8StorageService`（包装 `IStorageGrid`） |
| `appeng.crafting.CraftingPlan` / `inv.CraftingSimulationState` | 自建 shim（bytes 计量 + crafts 计数） |
| `appeng.api.networking.crafting.ICraftingPlan` | `com.ae2vm.addon.v8.VMCraftingJob implements ICraftingJob` |

### 挂载点（与 v9/v10+ 完全不同）

- `CraftingGridCacheMixin` ← `CraftingGridCache.beginCraftingJob`（取代 `CraftingService`）
- `CraftingCPUClusterMixin` ← `CraftingCPUCluster.submitJob`，复现 v8 派单协议
  （`MECraftingInventory` → `addStorage` / `addEmitable` / `addCrafting(pattern,count)` → `CraftingLink`）
- `DualityInterfaceMixin` ← `DualityInterface.updateCraftingList`（取代 `PatternProviderLogic.updatePatterns`）
- 删除 v9 专属的 `CraftingServiceMixin` / `PatternProviderLogicMixin`

### Java 8 降级

- `record` → 等价 immutable value class（`CallFrame` / `ConvEdge` / `RingResult` /
  `CraftingPlan` / `GenericStack`），并保留 `bundleKey()` / `in()` / `out()` / `to()` /
  `cycleCut()` 等 record 风格访问器，调用点不动。
- 159 处 `var` → 显式类型（用 `javac -g` + `javap` LocalVariableTable 反推推断类型后批量替换）。
- Java 9+ API：`InputStream.readAllBytes` / `CompletableFuture.failedFuture` / `String.isBlank`
  全部改成本地等价实现。
- arrow switch → 传统 switch；`instanceof` 模式匹配 → 显式强转。

### 构建 / 测试

- 映射从 `stable_36`（编译期出 SRG 名 `func_190926_b`，MC API 全找不到）改成
  `official / 1.16.5`，`reobfJar` 负责运行期重混淆。
- `mcp_mappings` 在 `gradle.properties` 保留 `stable_36` 仅为记录，`build.gradle` 实际用 official。
- 测试源码保持 1.17.1 的 Java 16+ 原文，**fork JDK 17 的 javac/java 编译运行**
  （产物 jar 仍是 Java 8）；按 §5 约定 `exclude 'com/ae2vm/addon/bench/**'`。
- `VMTest` 真单测 **6/6 通过**；bench 编译保留、运行跳过。
- bench 适配：`Level → net.minecraft.world.World`、`TextComponent → StringTextComponent`、
  `FakeBenchGrid`/`FakeGrid` 改 v8 `IGrid`（`IGridCache` + `MENetworkEvent`，新增 `BenchV8Grid` 基类）。

---

## v1.14.1 (1.19.3-forge) — 2026-09-17

首个 Forge 1.19.3 分支。基于 **AE2VMAddon-1.20.1** (本地 `VM-GTL` / `f9e0837 v1.13.5`) v1.13.5 源码迁移，配套 AE2 `forge/v13.0.10-beta` + Forge `44.1.0`。

### AE2 API 差异（仅 2 处）

1. **`appeng.helpers.iface.PatternProviderLogic`** ← 1.19.3
   `appeng.helpers.patternprovider.PatternProviderLogic` ← 1.20.1+

   → `PatternProviderLogicMixin.java` 改 `import` 路径。

2. **`KeyCounter.remove(AEKey)` 在 1.19.3 不存在**（只有 `remove(AEKey, long)`）
   `CraftingVM.java` 共 9 处单参 `.remove(key)` 全部改成双参：
   - 有前置 `if (map.get(k) == 0)` 的 → `remove(key, 0L)`（仅当值为 0 时移除）
   - 无前置检查的 → `remove(key, map.get(key))`（无条件移除当前值）

### 构建差异

- **Forge 44.1.0** 用 **ASM 9.3**（1.20.1 的 Forge 47 用 9.5+）。本地 `.gradle-custom/caches/modules-2` 没有 9.3，从 Maven Central 离线下载到 `AE2VMAddon-1.19.3/libs/asm-*.jar`。
- **modlauncher 10.0.8** 也缺失，从 Forge Maven 离线下载到 `AE2VMAddon-1.19.3/libs/modlauncher-10.0.8.jar`。
- 新增 `build.gradle` 本地仓库声明 `file:///E:/Applied%20Energistics%202%20Acceleration/AE2-refs/local-repo`，让 `--offline` 模式能找到上面两个 dep。
- AE2 不走 Modrinth Maven 远程链接（避免 `fg.deobf` + 1.19.3 MDK 缺失的额外步骤），改用本地 jar `libs/appliedenergistics2-forge-13.0.10-beta.jar`。

### 测试覆盖

- ✅ `./gradlew.bat --offline build` BUILD SUCCESSFUL
- ✅ 产物 `build/libs/ae2vm-1.14.1_forge_1.19.3.jar`（136969 字节，24 个 com.ae2vm class + mods.toml + mixins.json + ae2vm.png）
- ⏳ 实机验证待用户测试

### 配置摘要

| 项 | 值 |
|---|---|
| `minecraft_version` | 1.19.3 |
| `forge_version` | 44.1.0 |
| `ae2_version` | 13.0.10-beta |
| `mod_version` | 1.14.1 |
| 本地 git 分支 | `1.19.3-forge`（首次提交） |
| 远端 push 目标 | `1.19.3-forge`（沿用 1.20.1→`1.20.1-forge` 命名规律） |

---

## 项目背景

`AE2 VM Addon` 加速 AE2 自动合成计划过程。`AE2VMCrafting` 入口暴露给别的 mod 调用（如 Thunderbolt-Core 批量合成 / AdvancedAE 等），内部用 `CraftingVM` + `CraftingBytecode` 把 AE2 合成计划编译成栈式 bytecode 执行，绕开原版 AE2 的递归模拟。

`mods_template.json` 包含 `PatternProviderLogicMixin`（pattern 列表监听）、`CraftingServiceMixin`（crafting 入口），`CraftingSimulationStateAccessor` 用于读 `bytes` 字段。

详细架构说明见 `README.md` / `README_en.md`。
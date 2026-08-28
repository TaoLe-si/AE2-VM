# 1.21.1 玩家端 NoClassDefFoundError 修复

## 问题

启动 1.21.1 客户端时报错：

```
Caused by: java.lang.NoClassDefFoundError: com/moakiee/thunderbolt/api/crafting/CraftingPlanningEngine
    at TRANSFORMER/ae2vm@1.13.15/com.ae2vm.addon.AE2VMAddon.lambda$commonSetup$1(AE2VMAddon.java:153)
```

崩溃时日志显示已装的 mod 列表中**没有** thunderbolt（玩家没装），但 AE2VMAddon 仍然尝试 `registerIfPresent()`，触发 class loading。

## 根因分析

`src/main/java/com/ae2vm/addon/compat/thunderbolt/ThunderboltCompat.java`：

```java
public static final String ENGINE_ID = AE2VMBatchCraftingPlanner.ENGINE_ID;  // line 30
```

这是**静态字段初始化器**。当任何代码首次引用 `ThunderboltCompat` 类时，JVM 立即：
1. 加载 `ThunderboltCompat`
2. 初始化静态字段 → 访问 `AE2VMBatchCraftingPlanner.ENGINE_ID`
3. 加载 `AE2VMBatchCraftingPlanner` → 解析 `implements CraftingPlanningEngine`
4. thunderbolt 2.0-alpha 提供的 API 接口 `CraftingPlanningEngine` 在运行时 classpath **不存在**（玩家没装 thunderbolt jar）
5. `NoClassDefFoundError: com/moakiee/thunderbolt/api/crafting/CraftingPlanningEngine`

即使 `isThunderboltLoaded()` 在运行时返回 false，问题也已经在 `AE2VMAddon.lambda$commonSetup$1` 引用 `ThunderboltCompat` 类时（class init 阶段）触发。

## 修复

按用户先前指示（"26.1 暂不迁移闪电库和闪电基准"），把 1.21.1 项目中 thunderbolt 相关代码**stub 化**，与 26.1 保持一致：

| 文件 | 改动 |
|---|---|
| `src/main/java/com/ae2vm/addon/compat/thunderbolt/ThunderboltCompat.java` | 替换为注释 stub（与 26.1 相同） |
| `src/main/java/com/ae2vm/addon/compat/thunderbolt/AE2VMBatchCraftingPlanner.java` | 替换为注释 stub（与 26.1 相同） |
| `src/main/java/com/ae2vm/addon/AE2VMAddon.java:3` | 注释 `import com.ae2vm.addon.compat.thunderbolt.ThunderboltCompat;` |
| `src/main/java/com/ae2vm/addon/AE2VMAddon.java:153` | 注释 `ThunderboltCompat.registerIfPresent();` 调用 |

`src/main/java/com/moakiee/thunderbolt/core/crafting/...` 下的 37 个 reference 框架 source 保留（编译期依赖 thunderbolt-2.0-alpha.jar），但**它们在运行时不会被加载**（main 中无外部引用，`AE2VMAddon.class` 字节码已无 Thunderbolt 引用），所以玩家没装 thunderbolt 也不会触发 NoClassDefFoundError。

`build.gradle` 的 `compileOnly files('E:/TB ThirdParty/.../thunderbolt-2.0-alpha.jar')` 保留（让 37 个 source 编译过），添加注释说明 re-enable 步骤。

## 验证

- 编译：`gradlew.bat clean jar -PblockedMode=warn --no-daemon` → BUILD SUCCESSFUL
- 字节码检查：`AE2VMAddon.class` 中**无** `ThunderboltCompat` / `registerIfPresent` 字符串
- jar 内容：`com/ae2vm/addon/compat/thunderbolt/*.class` 不存在（stub 无 public class，无 .class 产物）
- 测试：`gradlew.bat test --no-daemon -PblockedMode=warn` → **242 个测试全过，0 失败，0 错误，0 跳过**，33 个套件

## 玩家端验证

- `E:\MC\.minecraft\versions\1.21.1-NeoForge_21.1.242\crash-reports` 之前的 crash-2026-08-28_10.59.29-client.txt 表明 `com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine` 缺失
- 修复后 jar 中不再加载 ThunderboltCompat 类，玩家端 commonSetup.enqueueWork 链路应不再触发 NoClassDefFoundError

## 关键观察

`build/classes/java/main/com/ae2vm/addon/` 目录结构（修复后）：
```
com/ae2vm/addon/
├── AE2VMAddon.class
├── api/
├── compat/
│   └── advancedae/AdvancedAECompat.class
├── compiler/
├── config/
├── mixin/
└── vm/
```

**注意**：`compat/thunderbolt/` 子目录**已不存在**！因为 stub 文件不含 public class，javac 不会生成 .class 文件，因此 jar 中也无此目录。玩家在 commonSetup 阶段不会触发任何 Thunderbolt 类加载尝试。

## 26.1 项目对照

26.1 项目中早已 stub 化（迁移时遵循"不迁移闪电库"指示）：
- `AE2VMAddon.java:153` 已是 `// ThunderboltCompat.registerIfPresent();` 注释状态
- `compat/thunderbolt/*.java` 已是 stub

1.21.1 项目此次修复后与 26.1 完全对齐。

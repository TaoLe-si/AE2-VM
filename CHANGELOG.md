# AE2 VM Addon — 1.19.3 Forge 变更日志

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
# 1.20.1 mods.toml 修复说明

## 问题

`E:\Applied Energistics 2 Acceleration\AE2VMAddon-1.20.1\build\libs\ae2vm-1.13.1_forge_1.20.1.jar`
加载时报"不是有效 mod 文件"。

## 根因

`src/main/resources/META-INF/mods.toml` 中 `thunderbolt` 和 `configured` 两个
**可选依赖** 用了 **NeoForge 1.21.1 语法** `type="optional"`：

```toml
[[dependencies.${mod_id}]]
    modId="thunderbolt"
    type="optional"          # ← NeoForge 1.21.1+ 语法
    versionRange="[2.0.0-beta.1,)"
    ordering="NONE"
    side="BOTH"
```

但本项目 `gradle.properties` 声明的是 **Forge 1.20.1 (47.4.22)**：

```
minecraft_version=1.20.1
forge_version=47.4.22
```

**Forge 1.20.1 (47.x) 的 `mods.toml` 不接受 `type` 字段**，只接受
`mandatory=true` / `mandatory=false`（这是 Forge 1.20.1 的老式语法）。
Forge 1.21.1+ 才改成 `type="required"` / `type="optional"`。

## 历史对比

| 版本 | commit | 关键依赖语法 |
|---|---|---|
| v1.8.20 (1.20.1 Forge) | 3fa3eb3 | `mandatory=true` / `mandatory=false` ✅ |
| v1.10.7 (1.20.1 Forge) | 89d0f51 | `mandatory=true` / `mandatory=false` ✅ |
| **v1.13.1 (1.20.1 Forge)** | 工作区 (移植自 1.21.1) | `type="optional"` ❌ |

v1.13.1 在 v1.10.7 基础上把 `cloth_config` 替换为 `thunderbolt` 和 `configured`
时，**错把 1.21.1 NeoForge 项目的 `type="optional"` 语法带了过来**。

## 修复

```diff
-    type="optional"
+    mandatory=false
```

两处替换：`thunderbolt` 和 `configured`。同时补上文件末尾换行符
（原文件以 `"` 结尾，无 newline；之前 git diff 也提示
"No newline at end of file"）。

## 验证

修复后两个 jar 都已重新生成并验证：

| 文件 | 大小 | mods.toml 检查 |
|---|---:|---|
| ae2vm-1.13.1_forge_1.20.1.jar (crash 模式) | 124222 B | ✅ 无 `type="optional"`、含 `mandatory=false`、有末尾换行 |
| ae2vm-nodetect-1.13.1_forge_1.20.1.jar (warn 模式) | 124221 B | ✅ 同上 |

## 影响范围

仅 1.20.1 Forge 项目。26.1 NeoForge 项目的 `neoforge.mods.toml` 仍用
`type="required"` / `type="optional"` 语法（已验证正常）。

# Changelog / 更新日志

版本号基于 `1.8.1`：每次编译 `mod_version` +0.0.1（1.8.1 → 1.8.2 → …）。

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

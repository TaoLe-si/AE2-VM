# Experimental net-growth planning patch / 实验性净增量补丁

[完整中文更新记录与兼容性矩阵](PATCH-README.zh-CN.md) — 包含初始适配、local.1 / local.2 / local.3、限制、排查和构建说明。

Modified AE2 VM, based on TaoLe-si/AE2-VM branch `1.21.1-neoforge`,
commit `f11842ea531a30f5731b3853dd079074fb8ad0af` (v1.13.19).
Local modification date: 2026-10-17. Distribution prepared: 2026-10-19.
Original project: https://github.com/TaoLe-si/AE2-VM
Original attribution and LGPL-3.0 license are retained; see LICENSE.
This is an unofficial experimental build, not an upstream release.

## Changes
- Plan a supported positive two-node growth ring through an acyclic dependency graph,
  accounting for starting seeds, external materials and net requested output.
- Bounded breadth-first exploration of encoded alternative recipes, comparing feasible
  plans by total recipe executions, rather than stopping at the first short cycle.
- Reject zero-net crystal -> dust -> seed -> crystal plans as new production.
- Traverse tagged inputs using the encoded primary valid ingredient, retaining the
  ORIGINAL provider pattern objects. Tagged target consumption is bounded conservatively.
- Ordinary AE2 crafting CPU feedback reservation, recycler-first scheduling, seed
  restoration and persistence support.

## Scope and limitations
This is NOT a general globally optimal recipe solver. Alternative search is bounded
(512 states, 200 ms soft budget, 16 choices per key, 4096 queued states); it selects
the lowest execution count among feasible candidates actually evaluated. No guarantee
of a global optimum or wall-clock fastest machine schedule is made. Each key uses one
selected recipe. Arbitrary larger growth rings, all tag substitutions, catalysts and
general byproduct optimization are not implemented. Two-node growth has a restricted
single-output/1:1 recycler shape with sufficient starting material.
CPU changes target ordinary AE2 CPUs, NOT AdvancedAE Quantum or DataEnergistics Trinity
CPUs. Compatibility and execution in a real world still require validation.

## Validation
The existing local source build reported 507 JUnit tests, zero failures/errors.
This includes the upstream suite, new regression tests and a diagnostic reproduction.
Execution tests use an independent CPU model; this is NOT a live-game integration test.
Tagged seed regressions cover quantities 1, 32, 100 and 1000. No live-world success
has been confirmed for this build. The sanitized source package is content-equivalent
to the tested sources, with line endings normalized and this document added.

## Build
Use JDK 21. Minecraft 1.21.1, NeoForge 21.1.169+, AE2 19.x.
The build dependency is AE2 19.2.17; the target installation has AE2 19.2.18.

```powershell
.\gradlew.bat -PblockedMode=warn test jar --no-daemon --no-configuration-cache
```

The tested local build used Gradle 8.8; the upstream wrapper requests Gradle 8.10.
Use `test jar`, NOT `build`: upstream's build finalizer copies/deletes jars in its
author-specific E:/MC directory. That unrelated upstream task is not changed here.
Upstream also retains an optional author-specific Thunderbolt compileOnly file path.
Author-specific Java-home and proxy settings were removed from gradle.properties.
The upstream `blockedMode=warn` option creates the `ae2vm-nodetect` variant; upstream's
compatibility-check implementation is otherwise unchanged.

## Installation / 安装
The patched JAR contains the original mod plus these modifications. It is a replacement,
NOT a separate addon: keep only one AE2 VM JAR in mods. Preserve your previous JAR for
rollback. Restart the game and recreate old crafting requests after replacement.
Do not install the original and modified JAR simultaneously (same mod ID).

发布包包含修复版 JAR、完整对应源码、可审查 Git 补丁和许可证。
不包含整合包、存档、实际样板导出、日志、账号信息或本地路径配置。

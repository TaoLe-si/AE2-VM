# AE2 VM Addon — 1.12.2 NovaEngineering-World 专用版 变更日志
## v1.14.2 (1.12.2-nova) — 2026-09-21（VM 沙箱补上真库存；本 fork 另含字典替代候选补漏 + 1.20 bench 迁移）

**主修（与 1.15.2/1.16.x 同一份改动）**：`shim/crafting/inv/CraftingSimulationState` 原来没有真库存
（`injectItems` 空操作、`extractItems` 不扣减），而 VM 的折抵 `fromInternal = min(got, simInternal)`
假设沙箱存得下自产物 —— 于是"100 工作台已派工木板样板 100 次"仍报 `missing={98xplanks}`，
且 `usedItems` 把 1 个库存记成 2。现在注入入账、抽取按 `Actionable.MODULATE` 扣减
（`ChildCraftingSimulationState` 把 mode 传下去、`RealtimeNetworkCraftingSimulationState` 扣网络快照）。
判据（新增单测 `VmMissingAfterStockRefillTest`，本地跑不启动游戏）：

```
round1  missing={100xlog}     used={}
round2  missing={}            used={100xlog}      ← 修复前 missing={98xplanks}
LEAF    missing={399xplanks}  used={1xplanks}
```

与 1.15.2 / 1.20.1 / 1.21.1 同一场景**数字逐字相同**；`V8BridgeQuantityTest 7/7`、`VMTest 6/6`、
复现件 2/2 绿；实机验证仍待你在整合包里下一次单（部署 jar sha1 见工作区 VERSION-STATUS）。

**本 fork 另两处**：① `V8PatternDetails.V8Input.getPossibleInputs()` 补回字典替代候选枚举
（1.16.4/5 兄弟版一直有，本 fork 从"没有 `getSubstituteInputs` API 的 1.16.1"抄过来时把它连同
注释论据一起搬了 —— 而 uel 有该 API；实测补回后 `cand` 1→114，但 `missing` 仍是 98，
故它**是真实漏移植却不是本案根因**，作为独立缺陷保留；顺带修了"condensed 下标喂逐槽 API 会串槽"，
证据是反编译 uel `PatternHelper.getSubstituteInputs` 用的是逐槽 `inputs[]` 下标）。
② 1.20 的 bench 测试套件已全量语法降级并纳入源码集（`J8` 工厂替身、111 处 `var`、45 处
`instanceof` 模式匹配、2 个 record，`BenchAEKey`/`VariantKey` 改为返回真实 `AEItemKey`），
但**尚未与 1.20 基线对齐**（130 条里约 66 条红），所以暂时排除编译，注释里写明差额与原因；
实验过的 sticky-peak 方案只修好 3 条，未采纳，代码留在 `AE2-refs/CraftingVM.peakmodel-experiment.java`。


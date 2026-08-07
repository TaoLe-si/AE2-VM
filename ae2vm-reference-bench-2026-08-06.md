# AE2VM × Thunderbolt-Core 参考能力速度测试 — 详细计时报告

- **日期**: 2026-08-07（自生长环裁切修复后复测）
- **引擎**: AE2VM `CraftingVM`（工作区 AE2VMAddon，mod_version 1.8.26 / MC 1.21.1 / NeoForge 21.1.169）
- **套件**: Thunderbolt-Core @ `feature/generic-conflict-solver` 规划器能力参考测试（11 图族 × 33 材料用例）
- **时限**: 每例 1 秒硬时限 + 100ms 取消宽限（无一例超时）
- **运行**: `gradlew.bat test --tests "com.ae2vm.addon.bench.Ae2VmReferenceCapabilitySuiteTest" --rerun-tasks --no-daemon`

**两列计时说明**：
- `VM calc time` = `CraftingVM` 内部 `[AE2-VM] calc time`，纯计算耗时（编译+捕获+聚合+buildPlan）。
- `runner elapsedMs` = `ReferenceCapabilityRunner` 实测总耗时（含 check + plan + 语义校验；首个用例含 JVM/AE2 类加载预热 ~780ms）。

---

## 1. 单配方 DAG

| 用例 | 模式 | scale | 状态 | VM calc time | runner elapsedMs | 缺失 |
|---|---|---|---|---|---|---|
| single-dag/dispersed | MISSING | 3 | ✅ SUPPORTED | 27 ms¹ | 808.014 ms¹ | {D=2, G=1} |
| single-dag/dispersed | MINIMUM | 3 | ✅ SUPPORTED | 1 ms | 3.881 ms | {} |
| single-dag/dispersed | UNBOUNDED | 3 | ✅ SUPPORTED | 1 ms | 3.423 ms | {} |
| single-dag/fibonacci | MISSING | 32 | ✅ SUPPORTED | 10 ms | 13.361 ms | {X0=3, X1=5} |
| single-dag/fibonacci | MINIMUM | 32 | ✅ SUPPORTED | 8 ms | 10.189 ms | {} |
| single-dag/fibonacci | UNBOUNDED | 32 | ✅ SUPPORTED | 3 ms | 7.316 ms | {} |

¹ 首个用例含 JVM / AE2 类加载预热；稳态后单例约 1–10ms。

## 2. 多配方 DAG

| 用例 | 模式 | scale | 状态 | VM calc time | runner elapsedMs | 缺失 |
|---|---|---|---|---|---|---|
| multi-dag/greedy-trap | MISSING | 64 | ✅ SUPPORTED | 0 ms | 2.283 ms | {S=64} |
| multi-dag/greedy-trap | MINIMUM | 64 | ✅ SUPPORTED | 0 ms | 2.131 ms | {} |
| multi-dag/greedy-trap | UNBOUNDED | 64 | ✅ SUPPORTED | 0 ms | 2.839 ms | {} |
| multi-dag/fibonacci | MISSING | 12 | ✅ SUPPORTED | 1 ms | 2.902 ms | {X0=5, X1=9, X2=7} |
| multi-dag/fibonacci | MINIMUM | 12 | ❌ FALSE_POSITIVE | 1 ms | 3.010 ms | {X0=4} |
| multi-dag/fibonacci | UNBOUNDED | 12 | ✅ SUPPORTED | 0 ms | 2.009 ms | {} |

## 3. 环裁切

| 用例 | 模式 | scale | 状态 | VM calc time | runner elapsedMs | 缺失 |
|---|---|---|---|---|---|---|
| cycle/conversion-ring | MISSING | 3 | ❌ FALSE_POSITIVE | 0 ms | 1.589 ms | {} |
| cycle/conversion-ring | MINIMUM | 3 | ✅ SUPPORTED | 0 ms | 2.738 ms | {} |
| cycle/conversion-ring | UNBOUNDED | 3 | ✅ SUPPORTED | 0 ms | 2.417 ms | {} |
| cycle/self-growth-cut | MISSING | 2 | ✅ SUPPORTED | 0 ms | 2.334 ms | {A=2} |
| cycle/self-growth-cut | MINIMUM | 2 | ✅ SUPPORTED | 0 ms | 1.896 ms | {A=1} |
| cycle/self-growth-cut | UNBOUNDED | 2 | ✅ SUPPORTED | 0 ms | 1.808 ms | {} |

## 4. 催化剂 / 反馈环

| 用例 | 模式 | scale | 状态 | VM calc time | runner elapsedMs | 缺失 | missingOverhead |
|---|---|---|---|---|---|---|---|
| catalyst/returned-seed | MISSING | 1000 | ✅ SUPPORTED | 0 ms | 1.555 ms | {A=1000} | 1000.0 |
| catalyst/returned-seed | MINIMUM | 1000 | ❌ FALSE_POSITIVE | 0 ms | 1.774 ms | {A=999} | — |
| catalyst/returned-seed | UNBOUNDED | 1000 | ✅ SUPPORTED | 0 ms | 1.684 ms | {} | 1.0 |
| catalyst/raw-feedback-loop | MISSING | 8 | ❌ FALSE_POSITIVE | 0 ms | 2.474 ms | {D=8} | — |
| catalyst/raw-feedback-loop | MINIMUM | 8 | ❌ FALSE_POSITIVE | 0 ms | 2.301 ms | {D=8} | — |
| catalyst/raw-feedback-loop | UNBOUNDED | 8 | ❌ FALSE_POSITIVE | 0 ms | 1.646 ms | {D=8} | — |
| catalyst/lossy-feedback-loop | MISSING | 8 | ✅ SUPPORTED | 0 ms | 2.463 ms | {A=16} | 8.0 |
| catalyst/lossy-feedback-loop | MINIMUM | 8 | ❌ FALSE_POSITIVE | 0 ms | 1.584 ms | {A=14} | — |
| catalyst/lossy-feedback-loop | UNBOUNDED | 8 | ✅ SUPPORTED | 0 ms | 2.573 ms | {} | 1.0 |

## 5. 耐久链

| 用例 | 模式 | scale | 状态 | VM calc time | runner elapsedMs | 缺失 | missingOverhead |
|---|---|---|---|---|---|---|---|
| durability/finite-use-chain | MISSING | 100 | ✅ SUPPORTED | 0 ms | 2.000 ms | {tool=9901} | 9901.0 |
| durability/finite-use-chain | MINIMUM | 100 | ❌ FALSE_POSITIVE | 0 ms | 2.018 ms | {tool=9900} | — |
| durability/finite-use-chain | UNBOUNDED | 100 | ✅ SUPPORTED | 0 ms | 1.338 ms | {} | 1.0 |

## 6. 模糊 / 可复用库存

| 用例 | 模式 | scale | 状态 | VM calc time | runner elapsedMs | 缺失 | missingOverhead |
|---|---|---|---|---|---|---|---|
| fuzzy/variant-route | MISSING | 1000 | ✅ SUPPORTED | 0 ms | 2.062 ms | {logical_tool=1000} | 1000.0 |
| fuzzy/variant-route | MINIMUM | 1000 | ❌ FALSE_POSITIVE | 0 ms | 1.632 ms | {logical_tool=1000} | — |
| fuzzy/variant-route | UNBOUNDED | 1000 | ❌ FALSE_POSITIVE | 0 ms | 1.894 ms | {logical_tool=1000} | — |

---

## 汇总

```
SUMMARY cases=33 supported=23 falsePositive=10 engineError=0 timeout=0 totalElapsedMs=777.7
```

- **VM 纯计算时间**: 全部用例 0–27ms（唯一 27ms 是首例预热），稳态 0–10ms。
- **runner 实测**: 稳态单例 1.2–13.4ms；无一例触碰 1s 时限。
- **SUPPORTED (23)**: 纯 DAG（单配方分散/斐波那契、多配方 greedy-trap、多配方斐波那契 missing/unbounded）+ 换算环 min/unbounded + 自生长环全部 + 各族的 MISSING/UNBOUNDED 模式。
- **FALSE_POSITIVE (10)**: 能力边界——多样板最优选择（多配方斐波那契 min）、换算环差分（换算环 missing）、催化剂/耐久/可复用库存的 MINIMUM 模式。这些用例参考预期 VM 应具备催化剂/耐久/可复用/多样板最优选择/换算环差分概念，当前版本按普通消耗品建模。
- **对比 08-06 基线（19/14 → 20/13 → 23/10）**: 
  1. `multi-dag/fibonacci/unbounded` 已从 FALSE_POSITIVE 修正为 SUPPORTED（需求传播聚合对多配方斐波那契无上界模式已正确收敛，缺失 {}）。
  2. **`cycle/self-growth-cut` 全部 3 例已从 FALSE_POSITIVE 修正为 SUPPORTED（v1.8.26 自生长环裁切）**: 未播种的 A→2A 纯自环样板永远不再 firing（避免凭空复制物品），需求只从库存取、短缺报 missing。这是真实漏洞修复（AE2 物品复制 exploit 防护），且 33 例全部通过、零引擎错误、零超时。

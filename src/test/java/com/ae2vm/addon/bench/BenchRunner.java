package com.ae2vm.addon.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * 全量基准的入口（Gradle 2.14 没有 {@code useJUnitPlatform()}，那是 4.6+ 的 API，
 * 而 FG 2.2 + mixin 0.7.11 把本 fork 锁在 2.14）。由 build.gradle 的 {@code bench} 任务
 * 以 JavaExec 调起来。
 *
 * <p>参数 {@code args[0]} = 期望的用例总数（gradle.properties 的 expected_tests）。
 * <b>发现数不等于期望数就失败</b> —— 这一条专门堵 MIGRATION-PATTERNS §20 那个坑：
 * provider 不匹配时 Gradle 会发现 0 个测试还报 BUILD SUCCESSFUL。
 * 同时按类打印条数，便于与 AE2-refs/BASELINE-TEST-ROSTER.md 的名册逐类比对。
 */
public final class BenchRunner {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("[BENCH] 用法: BenchRunner <expectedTests>");
            System.exit(2);
            return;
        }
        int expected = Integer.parseInt(args[0]);

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("com.ae2vm.addon"))
                .build();

        final Map<String, long[]> perClass = new LinkedHashMap<String, long[]>();
        TestExecutionListener tally = new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                if (!id.isTest()) {
                    return;
                }
                String owner = id.getParentId().orElse("?");
                long[] c = perClass.get(owner);
                if (c == null) {
                    c = new long[2];
                    perClass.put(owner, c);
                }
                c[0]++;
                if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                    c[1]++;
                }
            }
        };

        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, summary, tally);

        TestExecutionSummary s = summary.getSummary();
        for (Map.Entry<String, long[]> e : perClass.entrySet()) {
            System.out.println(String.format("[BENCH] %-58s %3d tests  %d red",
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        System.out.println(String.format(
                "[BENCH] 合计 classes=%d found=%d succeeded=%d failed=%d aborted=%d skipped=%d",
                perClass.size(), s.getTestsFoundCount(), s.getTestsSucceededCount(),
                s.getTestsFailedCount(), s.getTestsAbortedCount(), s.getTestsSkippedCount()));
        for (TestExecutionSummary.Failure f : s.getFailures()) {
            System.out.println("   RED " + f.getTestIdentifier().getDisplayName()
                    + "  <- " + String.valueOf(f.getException()));
            // NPE 这类没有 message 的红，光看异常名定位不到；打前 6 帧。
            StackTraceElement[] st = f.getException().getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                System.out.println("        at " + st[i]);
            }
        }

        if (s.getTestsFoundCount() != expected) {
            System.err.println("[BENCH] 发现 " + s.getTestsFoundCount() + " 条 != 期望 " + expected
                    + " 条 —— 拒收（用例集被改动，或 provider 没生效）");
            System.exit(2);
        }
        if (s.getTestsFailedCount() > 0 || s.getTestsAbortedCount() > 0) {
            System.err.println("[BENCH] 有红");
            System.exit(1);
        }
        System.out.println("[BENCH] OK: " + expected + " 条全绿");
    }

    private BenchRunner() {
    }
}

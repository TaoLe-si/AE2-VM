plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// ---------------------------------------------------------------------------
// 版本与产物名
// gtnhgradle 用 `git describe` 推 version（Probability-Pattern 的产物就是
// statpatterns-aa751b2-1-7-10+aa751b2356-dirty.jar）。本项目 13 个 fork 统一
// mod_version=1.14.1、产物名一律 ae2vm-<ver>_forge_<mc>，所以在插件应用之后显式覆盖
// （写在 plugins{} 之前会被插件的 convention 盖掉）。
// ---------------------------------------------------------------------------
version = "1.14.1_forge_1.7.10_gtnh"
base.archivesName.set("ae2vm")

// ---------------------------------------------------------------------------
// 全量基准（1.7.10 用 gtnhgradle → Gradle 9.3.1，有原生 JUnit Platform 支持，
// 不需要像 1.10.2 那样自己写 JavaExec + BenchRunner）。
// failOnNoDiscoveredTests 就是 MIGRATION-PATTERNS §20 那个坑的官方堵法：
// provider 不匹配时 Gradle 会发现 0 个测试仍然报 BUILD SUCCESSFUL。
// ---------------------------------------------------------------------------
tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = true
    testLogging {
        events("passed", "failed", "skipped")
    }
}

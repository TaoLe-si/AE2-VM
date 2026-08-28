package com.ae2vm.addon.compat.thunderbolt;

// (intentionally empty) AE2VMAddon 1.21.1 v1.13.16+ excludes the
// Thunderbolt-Core algorithm library, matching the 26.1 sibling project.
// The previous (v1.13.13..v1.13.15) implementation of this class
// `implements CraftingPlanningEngine`; with Thunderbolt 2.0.0-beta.1
// absent from the runtime classpath, JVM class resolution throws
// `NoClassDefFoundError: com/moakiee/thunderbolt/api/crafting/CraftingPlanningEngine`
// at static init, which crashes the game. Stubbing the package fixes that.
//
// To re-enable: restore the v1.13.15 sources from git, restore the
// `compileOnly files('E:/TB Thirdparty/Thunderbolt-Core/build/libs/thunderbolt-2.0-alpha.jar')`
// line in build.gradle, and uncomment the `ThunderboltCompat.registerIfPresent();`
// call in AE2VMAddon.java.

package com.ae2vm.addon.compat.thunderbolt;

// (intentionally empty) AE2VMAddon 26.1 deliberately excludes the
// Thunderbolt-Core algorithm library. The 1.21.1 sources for this package
// (ThunderboltCompat.java + AE2VMBatchCraftingPlanner.java) live only under
// AE2VMAddon-1.21.1/; they are NOT ported to 26.1.
//
// References in AE2VMAddon.java (// ThunderboltCompat.registerIfPresent();)
// are commented out — see that file's note dated 2026-08-07 for rationale.
// This stub package preserves the source layout so any future re-enable is a
// one-line import + uncomment in AE2VMAddon, with no Gradle source-set churn.

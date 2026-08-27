package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.Objects;

/**
 * Marks a private-host borrow that is converted into a missing reusable seed before a contracted
 * loop starts.
 *
 * <p>The borrowed {@code actualKey} remains an ordinary converter input. {@code returnedSeedKey}
 * identifies the loop state produced by that converter and ultimately returned to the host.
 */
public record ReusableBootstrapRoute<K>(Object ownerRoutingScope, K returnedSeedKey) {
    public ReusableBootstrapRoute {
        Objects.requireNonNull(ownerRoutingScope, "ownerRoutingScope");
        Objects.requireNonNull(returnedSeedKey, "returnedSeedKey");
    }
}

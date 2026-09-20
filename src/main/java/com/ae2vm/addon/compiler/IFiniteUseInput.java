package com.ae2vm.addon.compiler;

/**
 * (v1.10.x DURABILITY) Optional capability an {@code IPatternDetails.IInput} may
 * implement to declare itself a FINITE-USE (durability) tool input — one
 * {@code amount}-sized unit survives {@code durabilityUses()} firings (a degrading
 * catalyst, e.g. {@code 1·A(n) + 1·B → 1·C + A(n-1)}). The compiler then emits the
 * {@code DURABILITY_TOOL} opcode instead of {@code CATALYST_SEED}, and the
 * aggregation demands {@code amount × ceil(times/uses)} tools for a batch of
 * {@code times} firings (the reference's "成环差分" closed form).
 *
 * <p>Implementors should return {@link Long#MAX_VALUE} for a true catalyst (infinite
 * uses) and a positive finite count for a durability tool. Inputs that do NOT
 * implement this interface are treated as plain consumed or (if returned) catalyst
 * seeds as before.
 */
public interface IFiniteUseInput {
    /** Firings a single amount-sized unit survives; {@code Long.MAX_VALUE} = true catalyst. */
    long durabilityUses();
}

package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

/** Optional OR-Tools CP-SAT implementation of Thunderbolt's bounded integer-flow kernel. */
final class CpSatIntegerLinearSolver {
    private static final long SAFE_ACTIVITY = Long.MAX_VALUE / 4L;
    private static final long BRIDGE_SOLVED = 0L;
    private static final long BRIDGE_INFEASIBLE = 1L;
    private static final long BRIDGE_MODEL_INVALID = 2L;

    private CpSatIntegerLinearSolver() {
    }

    record StockObjective(
            long[][] netCoefficients,
            long[] demandAfterExternalSupply,
            long[] stockUpperBounds,
            int[] distances,
            int[] directUsedVariables,
            int[] directUsedDistances) {
        StockObjective {
            netCoefficients = netCoefficients.clone();
            demandAfterExternalSupply = demandAfterExternalSupply.clone();
            stockUpperBounds = stockUpperBounds.clone();
            distances = distances.clone();
            directUsedVariables = directUsedVariables.clone();
            directUsedDistances = directUsedDistances.clone();
        }

        static StockObjective none() {
            return new StockObjective(
                    new long[0][], new long[0], new long[0], new int[0],
                    new int[0], new int[0]);
        }
    }

    static boolean isAvailable() {
        return CpSatRuntime.isAvailable();
    }

    static Throwable loadFailure() {
        return CpSatRuntime.loadFailure();
    }

    static boolean installRuntime(Path cacheRoot) {
        return CpSatRuntime.install(cacheRoot);
    }

    static boolean initializeFromTestClasspath() {
        return CpSatRuntime.initializeFromTestClasspath();
    }

    static BoundedIntegerLinearSolver.Result solve(
            int variableCount,
            List<BoundedIntegerLinearSolver.Constraint> constraints,
            long maxValue) {
        return solve(variableCount, constraints, maxValue, variableCount);
    }

    static BoundedIntegerLinearSolver.Result solve(
            int variableCount,
            List<BoundedIntegerLinearSolver.Constraint> constraints,
            long maxValue,
            int executionVariableCount) {
        return solve(
                variableCount,
                constraints,
                maxValue,
                executionVariableCount,
                StockObjective.none());
    }

    static BoundedIntegerLinearSolver.Result solve(
            int variableCount,
            List<BoundedIntegerLinearSolver.Constraint> constraints,
            long maxValue,
            int executionVariableCount,
            StockObjective stockObjective) {
        if (!isAvailable() || variableCount <= 0 || constraints == null || maxValue < 0L) {
            return result(BoundedIntegerLinearSolver.Status.INVALID_INPUT, 0);
        }
        if (executionVariableCount < 0
                || executionVariableCount > variableCount
                || stockObjective == null) {
            return result(BoundedIntegerLinearSolver.Status.INVALID_INPUT, 0);
        }

        // Native work consumes the same deadline as the surrounding planning attempt. Do not
        // reserve an arbitrary slice here: a cheap model returns early, while a hard model may use
        // all time that the caller still has available.
        long remainingNanos = PlanningCancellation.remainingNanos(Long.MAX_VALUE);
        if (remainingNanos <= 0L) {
            return result(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, 0);
        }

        long[] maximumAbsoluteCoefficient = new long[variableCount];
        for (var constraint : constraints) {
            PlanningCancellation.check();
            if (constraint == null || constraint.coefficients().length != variableCount) {
                return result(BoundedIntegerLinearSolver.Status.INVALID_INPUT, 0);
            }
            long[] coefficients = constraint.coefficients();
            for (int variable = 0; variable < variableCount; variable++) {
                long coefficient = coefficients[variable];
                if (coefficient == Long.MIN_VALUE) {
                    return result(BoundedIntegerLinearSolver.Status.COEFFICIENT_OVERFLOW, 0);
                }
                maximumAbsoluteCoefficient[variable] = Math.max(
                        maximumAbsoluteCoefficient[variable], Math.abs(coefficient));
            }
        }

        // CP-SAT validates the full domain activity before solving. Use a conservative per-variable
        // cap so every row and the sum objective remain inside int64. A cap-caused infeasibility is
        // never promoted to a proof: the caller falls back to Thunderbolt's existing bounded path.
        long divisor = Math.max(1L, variableCount);
        long objectiveCap = SAFE_ACTIVITY / divisor;
        long[] upperBounds = new long[variableCount];
        boolean domainWasCapped = false;
        for (int variable = 0; variable < variableCount; variable++) {
            long coefficient = Math.max(1L, maximumAbsoluteCoefficient[variable]);
            long rowCap = SAFE_ACTIVITY / divisor / coefficient;
            long upper = Math.min(maxValue, Math.min(objectiveCap, rowCap));
            upperBounds[variable] = Math.max(0L, upper);
            domainWasCapped |= upperBounds[variable] < maxValue;
        }

        // Detect a row that the safety caps alone make impossible. This is an unsupported numeric
        // range, not a mathematical infeasibility proof.
        for (var constraint : constraints) {
            BigInteger maximum = BigInteger.ZERO;
            long[] coefficients = constraint.coefficients();
            for (int variable = 0; variable < variableCount; variable++) {
                if (coefficients[variable] > 0L) {
                    maximum = maximum.add(BigInteger.valueOf(coefficients[variable])
                            .multiply(BigInteger.valueOf(upperBounds[variable])));
                }
            }
            if (maximum.compareTo(BigInteger.valueOf(constraint.minimum())) < 0) {
                return result(BoundedIntegerLinearSolver.Status.COEFFICIENT_OVERFLOW, 0);
            }
        }

        try {
            long[][] coefficientRows = new long[constraints.size()][];
            long[] minimums = new long[constraints.size()];
            for (int row = 0; row < constraints.size(); row++) {
                coefficientRows[row] = constraints.get(row).coefficients();
                minimums[row] = constraints.get(row).minimum();
            }
            long[] raw = CpSatRuntime.solve(
                    coefficientRows,
                    minimums,
                    upperBounds,
                    executionVariableCount,
                    stockObjective.netCoefficients(),
                    stockObjective.demandAfterExternalSupply(),
                    stockObjective.stockUpperBounds(),
                    stockObjective.distances(),
                    stockObjective.directUsedVariables(),
                    stockObjective.directUsedDistances(),
                    remainingNanos / 1_000_000_000.0D);
            PlanningCancellation.check();
            if (raw == null || raw.length < 2) {
                return result(BoundedIntegerLinearSolver.Status.INTERNAL_ERROR, 0);
            }
            int branches = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, raw[1]));
            if (raw[0] == BRIDGE_SOLVED) {
                if (raw.length != variableCount + 2) {
                    return result(BoundedIntegerLinearSolver.Status.INTERNAL_ERROR, branches);
                }
                long[] values = Arrays.copyOfRange(raw, 2, raw.length);
                if (!isFeasible(values, constraints, maxValue)) {
                    return result(BoundedIntegerLinearSolver.Status.INTERNAL_ERROR, branches);
                }
                return new BoundedIntegerLinearSolver.Result(
                        BoundedIntegerLinearSolver.Status.SOLVED, values, branches);
            }
            if (raw[0] == BRIDGE_INFEASIBLE && !domainWasCapped) {
                return result(BoundedIntegerLinearSolver.Status.INFEASIBLE, branches);
            }
            if (raw[0] == BRIDGE_MODEL_INVALID) {
                return result(BoundedIntegerLinearSolver.Status.COEFFICIENT_OVERFLOW, branches);
            }
            return result(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, branches);
        } catch (PlanningExitException | CancellationException exit) {
            throw exit;
        } catch (ArithmeticException ignored) {
            return result(BoundedIntegerLinearSolver.Status.COEFFICIENT_OVERFLOW, 0);
        } catch (RuntimeException | LinkageError ignored) {
            return result(BoundedIntegerLinearSolver.Status.INTERNAL_ERROR, 0);
        }
    }

    private static boolean isFeasible(
            long[] values,
            List<BoundedIntegerLinearSolver.Constraint> constraints,
            long maxValue) {
        for (long value : values) {
            if (value < 0L || value > maxValue) {
                return false;
            }
        }
        for (var constraint : constraints) {
            BigInteger activity = BigInteger.ZERO;
            long[] coefficients = constraint.coefficients();
            for (int variable = 0; variable < values.length; variable++) {
                activity = activity.add(BigInteger.valueOf(coefficients[variable])
                        .multiply(BigInteger.valueOf(values[variable])));
            }
            if (activity.compareTo(BigInteger.valueOf(constraint.minimum())) < 0) {
                return false;
            }
        }
        return true;
    }

    private static BoundedIntegerLinearSolver.Result result(
            BoundedIntegerLinearSolver.Status status, int branches) {
        return new BoundedIntegerLinearSolver.Result(status, new long[0], branches);
    }
}

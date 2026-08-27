package com.moakiee.thunderbolt.core.crafting.planner;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Exact, node- and pivot-budgeted feasibility for
 * {@code A*x >= b, 0 <= x <= maxValue, x integral}.
 *
 * <p>The LP relaxation uses the same arbitrary-precision rational Phase-I simplex machinery as the
 * public positive-integer helper, but does not scale fractional coordinates: scaling would change a
 * fixed crafting request. Fractional coordinates are resolved with ordinary branch-and-bound. The
 * node and simplex-pivot budgets are independent of coefficient magnitude, so a {@code long}
 * request never becomes a loop over individual crafts. Production callers additionally supply a
 * tableau-shape, rational-cell-work, and elapsed-time budget because one BigInteger pivot can hide
 * far more work than one graph visit. Exhausting any budget returns a cutoff; it is never reported
 * as an infeasibility proof.
 */
final class BoundedIntegerLinearSolver {

    private static final int MIN_SIMPLEX_PIVOT_BUDGET = 64;
    private static final int MAX_SIMPLEX_PIVOT_BUDGET = 1_024;

    enum Status {
        SOLVED,
        INFEASIBLE,
        BUDGET_EXHAUSTED,
        COEFFICIENT_OVERFLOW,
        INVALID_INPUT,
        INTERNAL_ERROR
    }

    record Constraint(long[] coefficients, long minimum) {
        Constraint {
            Objects.requireNonNull(coefficients, "coefficients");
            coefficients = coefficients.clone();
        }

        @Override
        public long[] coefficients() {
            return coefficients.clone();
        }
    }

    record Result(Status status, long[] values, int visitedNodes) {
        Result {
            Objects.requireNonNull(status, "status");
            values = values == null ? new long[0] : values.clone();
            if (status == Status.SOLVED && values.length == 0) {
                throw new IllegalArgumentException("a solved result requires values");
            }
        }

        @Override
        public long[] values() {
            return values.clone();
        }

        boolean solved() {
            return status == Status.SOLVED;
        }
    }

    private BoundedIntegerLinearSolver() {
    }

    static Result solve(
            int variableCount,
            List<Constraint> constraints,
            long maxValue,
            int nodeBudget) {
        return solve(
                variableCount,
                constraints,
                maxValue,
                nodeBudget,
                WorkBudget.unlimited());
    }

    static Result solve(
            int variableCount,
            List<Constraint> constraints,
            long maxValue,
            int nodeBudget,
            WorkBudget workBudget) {
        if (variableCount <= 0 || constraints == null || maxValue < 0 || nodeBudget <= 0) {
            return result(Status.INVALID_INPUT, 0);
        }
        if (workBudget == null) {
            return result(Status.INVALID_INPUT, 0);
        }

        int positiveMinimums = 0;
        for (Constraint constraint : constraints) {
            PlanningCancellation.check();
            if (constraint == null || constraint.coefficients().length != variableCount) {
                return result(Status.INVALID_INPUT, 0);
            }
            if (constraint.minimum() > 0) {
                positiveMinimums++;
            }
        }
        long rootRows = constraints.size() + (long) variableCount;
        long rootColumns = variableCount + rootRows + positiveMinimums + 1L;
        if (!workBudget.admitsTableau(rootRows, rootColumns)) {
            return result(Status.BUDGET_EXHAUSTED, 0);
        }

        List<ExactConstraint> base = new ArrayList<>(constraints.size() + variableCount);
        for (Constraint constraint : constraints) {
            PlanningCancellation.check();
            long[] coefficients = constraint.coefficients();
            if (!workBudget.tryConsume(coefficients.length)) {
                return result(Status.BUDGET_EXHAUSTED, 0);
            }
            BigInteger[] exact = new BigInteger[variableCount];
            for (int i = 0; i < variableCount; i++) {
                exact[i] = BigInteger.valueOf(coefficients[i]);
            }
            base.add(new ExactConstraint(exact, BigInteger.valueOf(constraint.minimum())));
        }
        // CraftPlan firing counts use bounded long arithmetic. Adding the representable upper bound
        // to the relaxation prevents an arbitrary feasible basis from escaping that domain.
        for (int variable = 0; variable < variableCount; variable++) {
            BigInteger[] upper = zeros(variableCount);
            upper[variable] = BigInteger.ONE.negate();
            base.add(new ExactConstraint(upper, BigInteger.valueOf(maxValue).negate()));
        }

        Deque<List<ExactConstraint>> frontier = new ArrayDeque<>();
        frontier.push(List.of());
        int visited = 0;
        long proposedPivotLimit = 4L * (variableCount + (long) base.size());
        int pivotLimit = (int) Math.max(
                MIN_SIMPLEX_PIVOT_BUDGET,
                Math.min(MAX_SIMPLEX_PIVOT_BUDGET, proposedPivotLimit));
        PivotBudget pivotBudget = new PivotBudget(pivotLimit);
        try {
            while (!frontier.isEmpty() && visited < nodeBudget) {
                List<ExactConstraint> branches = frontier.pop();
                visited++;
                List<ExactConstraint> rows = new ArrayList<>(base.size() + branches.size());
                rows.addAll(base);
                rows.addAll(branches);
                Relaxation relaxation = relax(
                        variableCount, rows, pivotBudget, workBudget);
                if (relaxation.status == RelaxationStatus.INFEASIBLE) {
                    continue;
                }
                if (relaxation.status == RelaxationStatus.BUDGET_EXHAUSTED) {
                    return result(Status.BUDGET_EXHAUSTED, visited);
                }
                if (relaxation.status != RelaxationStatus.FEASIBLE) {
                    return result(Status.INTERNAL_ERROR, visited);
                }

                int fractional = firstFractional(relaxation.values);
                if (fractional < 0) {
                    BigInteger[] integer = new BigInteger[variableCount];
                    for (int i = 0; i < variableCount; i++) {
                        integer[i] = relaxation.values[i].toBigIntegerExact();
                    }
                    if (!minimizeCoordinates(integer, rows, workBudget)) {
                        return result(Status.BUDGET_EXHAUSTED, visited);
                    }
                    if (!isFeasible(integer, rows)) {
                        return result(Status.INTERNAL_ERROR, visited);
                    }
                    long[] values = new long[variableCount];
                    BigInteger max = BigInteger.valueOf(maxValue);
                    for (int i = 0; i < variableCount; i++) {
                        if (integer[i].signum() < 0 || integer[i].compareTo(max) > 0) {
                            return result(Status.COEFFICIENT_OVERFLOW, visited);
                        }
                        values[i] = integer[i].longValueExact();
                    }
                    return new Result(Status.SOLVED, values, visited);
                }

                Rational value = relaxation.values[fractional];
                BigInteger floor = value.floor();
                BigInteger ceil = floor.add(BigInteger.ONE);
                BigInteger max = BigInteger.valueOf(maxValue);

                // Stack is LIFO: push the upper half first so the lower-firing half is tried first.
                if (ceil.compareTo(max) <= 0) {
                    frontier.push(withBranch(
                            branches,
                            bound(variableCount, fractional, BigInteger.ONE, ceil)));
                }
                if (floor.signum() >= 0) {
                    frontier.push(withBranch(
                            branches,
                            bound(variableCount, fractional, BigInteger.ONE.negate(), floor.negate())));
                }
            }
            return frontier.isEmpty()
                    ? result(Status.INFEASIBLE, visited)
                    : result(Status.BUDGET_EXHAUSTED, visited);
        } catch (ArithmeticException ignored) {
            return result(Status.COEFFICIENT_OVERFLOW, visited);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException ignored) {
            // Planner helpers fail closed: unsupported arithmetic must not take down the server.
            return result(Status.INTERNAL_ERROR, visited);
        }
    }

    private static List<ExactConstraint> withBranch(
            List<ExactConstraint> branches, ExactConstraint branch) {
        List<ExactConstraint> result = new ArrayList<>(branches.size() + 1);
        result.addAll(branches);
        result.add(branch);
        return List.copyOf(result);
    }

    private static ExactConstraint bound(
            int variables, int variable, BigInteger coefficient, BigInteger minimum) {
        BigInteger[] row = zeros(variables);
        row[variable] = coefficient;
        return new ExactConstraint(row, minimum);
    }

    private static BigInteger[] zeros(int size) {
        BigInteger[] values = new BigInteger[size];
        Arrays.fill(values, BigInteger.ZERO);
        return values;
    }

    private static int firstFractional(Rational[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!values[i].isInteger()) {
                return i;
            }
        }
        return -1;
    }

    private static Relaxation relax(
            int variableCount,
            List<ExactConstraint> constraints,
            PivotBudget pivotBudget,
            WorkBudget workBudget) {
        int rowCount = constraints.size();
        int artificialCount = 0;
        for (ExactConstraint constraint : constraints) {
            if (constraint.minimum.signum() > 0) {
                artificialCount++;
            }
        }

        int auxiliaryOffset = variableCount;
        int artificialOffset = variableCount + rowCount;
        int totalVariables = artificialOffset + artificialCount;
        if (!workBudget.tryStartTableau(rowCount, (long) totalVariables + 1L)) {
            return new Relaxation(RelaxationStatus.BUDGET_EXHAUSTED, new Rational[0]);
        }
        Rational[][] tableau = new Rational[rowCount][totalVariables + 1];
        for (Rational[] row : tableau) {
            Arrays.fill(row, Rational.ZERO);
        }
        int[] basis = new int[rowCount];
        Rational[] costs = new Rational[totalVariables];
        Arrays.fill(costs, Rational.ZERO);

        int nextArtificial = artificialOffset;
        for (int row = 0; row < rowCount; row++) {
            ExactConstraint constraint = constraints.get(row);
            boolean needsArtificial = constraint.minimum.signum() > 0;
            BigInteger sign = needsArtificial ? BigInteger.ONE : BigInteger.ONE.negate();
            for (int column = 0; column < variableCount; column++) {
                tableau[row][column] = Rational.of(
                        constraint.coefficients[column].multiply(sign));
            }
            tableau[row][auxiliaryOffset + row] = needsArtificial
                    ? Rational.NEGATIVE_ONE : Rational.ONE;
            tableau[row][totalVariables] = Rational.of(constraint.minimum.multiply(sign));
            if (needsArtificial) {
                tableau[row][nextArtificial] = Rational.ONE;
                basis[row] = nextArtificial;
                costs[nextArtificial] = Rational.NEGATIVE_ONE;
                nextArtificial++;
            } else {
                basis[row] = auxiliaryOffset + row;
            }
        }

        SimplexStatus simplex = maximize(
                tableau, basis, costs, totalVariables, pivotBudget, workBudget);
        if (simplex == SimplexStatus.BUDGET_EXHAUSTED) {
            return new Relaxation(RelaxationStatus.BUDGET_EXHAUSTED, new Rational[0]);
        }
        if (simplex != SimplexStatus.OPTIMAL) {
            return new Relaxation(RelaxationStatus.ERROR, new Rational[0]);
        }
        Rational objective = Rational.ZERO;
        for (int row = 0; row < rowCount; row++) {
            objective = objective.add(costs[basis[row]].multiply(tableau[row][totalVariables]));
        }
        if (objective.signum() < 0) {
            return new Relaxation(RelaxationStatus.INFEASIBLE, new Rational[0]);
        }
        if (objective.signum() > 0) {
            return new Relaxation(RelaxationStatus.ERROR, new Rational[0]);
        }

        Rational[] values = new Rational[variableCount];
        Arrays.fill(values, Rational.ZERO);
        for (int row = 0; row < rowCount; row++) {
            int basic = basis[row];
            if (basic < variableCount) {
                values[basic] = tableau[row][totalVariables];
            }
        }
        return new Relaxation(RelaxationStatus.FEASIBLE, values);
    }

    private static SimplexStatus maximize(
            Rational[][] tableau,
            int[] basis,
            Rational[] costs,
            int variableCount,
            PivotBudget pivotBudget,
            WorkBudget workBudget) {
        while (true) {
            int entering = -1;
            for (int column = 0; column < variableCount; column++) {
                Rational reduced = costs[column];
                for (int row = 0; row < tableau.length; row++) {
                    if (!workBudget.tryConsume(1L)) {
                        return SimplexStatus.BUDGET_EXHAUSTED;
                    }
                    reduced = reduced.subtract(costs[basis[row]].multiply(tableau[row][column]));
                }
                if (reduced.signum() > 0) {
                    entering = column; // Bland's rule.
                    break;
                }
            }
            if (entering < 0) {
                return SimplexStatus.OPTIMAL;
            }

            int leaving = -1;
            Rational bestRatio = null;
            for (int row = 0; row < tableau.length; row++) {
                if (!workBudget.tryConsume(1L)) {
                    return SimplexStatus.BUDGET_EXHAUSTED;
                }
                Rational direction = tableau[row][entering];
                if (direction.signum() <= 0) {
                    continue;
                }
                Rational ratio = tableau[row][variableCount].divide(direction);
                if (leaving < 0 || ratio.compareTo(bestRatio) < 0
                        || (ratio.equals(bestRatio) && basis[row] < basis[leaving])) {
                    leaving = row;
                    bestRatio = ratio;
                }
            }
            if (leaving < 0) {
                return SimplexStatus.UNBOUNDED;
            }
            if (!pivotBudget.tryConsume()) {
                return SimplexStatus.BUDGET_EXHAUSTED;
            }
            if (!pivot(
                    tableau, basis, leaving, entering, variableCount, workBudget)) {
                return SimplexStatus.BUDGET_EXHAUSTED;
            }
        }
    }

    private static boolean pivot(
            Rational[][] tableau,
            int[] basis,
            int pivotRow,
            int pivotColumn,
            int variableCount,
            WorkBudget workBudget) {
        Rational pivot = tableau[pivotRow][pivotColumn];
        for (int column = 0; column <= variableCount; column++) {
            if (!workBudget.tryConsume(1L)) {
                return false;
            }
            tableau[pivotRow][column] = tableau[pivotRow][column].divide(pivot);
        }
        for (int row = 0; row < tableau.length; row++) {
            if (row == pivotRow) {
                continue;
            }
            Rational factor = tableau[row][pivotColumn];
            if (factor.signum() == 0) {
                continue;
            }
            for (int column = 0; column <= variableCount; column++) {
                if (!workBudget.tryConsume(1L)) {
                    return false;
                }
                tableau[row][column] = tableau[row][column]
                        .subtract(factor.multiply(tableau[pivotRow][column]));
            }
        }
        basis[pivotRow] = pivotColumn;
        return true;
    }

    /** One deterministic reduction pass removes unnecessary firings without another search. */
    private static boolean minimizeCoordinates(
            BigInteger[] values,
            List<ExactConstraint> constraints,
            WorkBudget workBudget) {
        for (int variable = 0; variable < values.length; variable++) {
            BigInteger lower = BigInteger.ZERO;
            for (ExactConstraint constraint : constraints) {
                if (!workBudget.tryConsume(values.length)) {
                    return false;
                }
                BigInteger coefficient = constraint.coefficients[variable];
                if (coefficient.signum() <= 0) {
                    continue;
                }
                BigInteger other = dot(constraint.coefficients, values)
                        .subtract(coefficient.multiply(values[variable]));
                BigInteger needed = ceilDivide(
                        constraint.minimum.subtract(other), coefficient);
                if (needed.compareTo(lower) > 0) {
                    lower = needed;
                }
            }
            if (lower.compareTo(values[variable]) < 0) {
                values[variable] = lower.max(BigInteger.ZERO);
            }
        }
        return true;
    }

    private static boolean isFeasible(
            BigInteger[] values, List<ExactConstraint> constraints) {
        for (BigInteger value : values) {
            if (value.signum() < 0) {
                return false;
            }
        }
        for (ExactConstraint constraint : constraints) {
            if (dot(constraint.coefficients, values).compareTo(constraint.minimum) < 0) {
                return false;
            }
        }
        return true;
    }

    private static BigInteger dot(BigInteger[] coefficients, BigInteger[] values) {
        BigInteger result = BigInteger.ZERO;
        for (int i = 0; i < coefficients.length; i++) {
            result = result.add(coefficients[i].multiply(values[i]));
        }
        return result;
    }

    private static BigInteger ceilDivide(BigInteger numerator, BigInteger positiveDenominator) {
        BigInteger[] divided = numerator.divideAndRemainder(positiveDenominator);
        if (divided[1].signum() != 0 && numerator.signum() > 0) {
            return divided[0].add(BigInteger.ONE);
        }
        return divided[0];
    }

    private static Result result(Status status, int visitedNodes) {
        return new Result(status, new long[0], visitedNodes);
    }

    private record ExactConstraint(BigInteger[] coefficients, BigInteger minimum) {
    }

    private record Relaxation(RelaxationStatus status, Rational[] values) {
    }

    private enum RelaxationStatus {
        FEASIBLE,
        INFEASIBLE,
        BUDGET_EXHAUSTED,
        ERROR
    }

    private enum SimplexStatus {
        OPTIMAL,
        UNBOUNDED,
        BUDGET_EXHAUSTED
    }

    private static final class PivotBudget {
        private int remaining;

        private PivotBudget(int limit) {
            remaining = limit;
        }

        private boolean tryConsume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    /**
     * Plan-shareable guard for the actual cost hidden behind a simplex-pivot count.
     *
     * <p>A pivot touches a rational tableau whose dimensions and BigInteger operands may be much
     * larger than the original conflict graph. The single-tableau cap rejects a locally dense
     * relaxation before allocation, the cell-work budget limits all admitted relaxations together,
     * and the monotonic deadline catches coefficient growth that a cell count cannot predict. A
     * rejected or exhausted solve is only a cutoff; the planner retains its ordinary fallback.
     */
    static final class WorkBudget {
        private static final long TIME_CHECK_INTERVAL = 256L;
        private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

        private final boolean unlimited;
        private final boolean wallClock;
        private final long maxTableauCells;
        private long remainingCellWork;
        private long startedNanos = Long.MIN_VALUE;
        private final long maxNanos;
        private long workUntilTimeCheck = TIME_CHECK_INTERVAL;
        private boolean exhausted;

        private WorkBudget(
                boolean unlimited,
                boolean wallClock,
                long maxTableauCells,
                long cellWork,
                long maxNanos) {
            this.unlimited = unlimited;
            this.wallClock = wallClock;
            this.maxTableauCells = maxTableauCells;
            this.remainingCellWork = cellWork;
            this.maxNanos = maxNanos;
        }

        static WorkBudget unlimited() {
            return new WorkBudget(
                    true, false, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        }

        static WorkBudget bounded(
                long maxTableauCells,
                long cellWork,
                long maxNanos) {
            return new WorkBudget(
                    false,
                    false,
                    Math.max(1L, maxTableauCells),
                    Math.max(1L, cellWork),
                    Math.max(1L, maxNanos));
        }

        /** Wall-clock variant for a user-visible local latency envelope. */
        static WorkBudget wallClockBounded(
                long maxTableauCells,
                long cellWork,
                long maxNanos) {
            return new WorkBudget(
                    false,
                    true,
                    Math.max(1L, maxTableauCells),
                    Math.max(1L, cellWork),
                    Math.max(1L, maxNanos));
        }

        private boolean admitsTableau(long rows, long columns) {
            if (!canContinue()) {
                return false;
            }
            return saturatedMultiply(rows, columns) <= maxTableauCells;
        }

        private boolean tryStartTableau(long rows, long columns) {
            long cells = saturatedMultiply(rows, columns);
            return cells <= maxTableauCells && tryConsume(cells);
        }

        private boolean tryConsume(long cellWork) {
            PlanningCancellation.check();
            if (unlimited) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            long requested = Math.max(1L, cellWork);
            if (requested > remainingCellWork) {
                exhausted = true;
                return false;
            }
            remainingCellWork -= requested;
            workUntilTimeCheck -= Math.min(workUntilTimeCheck, requested);
            if (workUntilTimeCheck == 0L) {
                workUntilTimeCheck = TIME_CHECK_INTERVAL;
                if (deadlineReached()) {
                    exhausted = true;
                    return false;
                }
            }
            return true;
        }

        private boolean canContinue() {
            PlanningCancellation.check();
            if (unlimited) {
                return true;
            }
            if (exhausted || remainingCellWork <= 0L || deadlineReached()) {
                exhausted = true;
                return false;
            }
            return true;
        }

        private boolean deadlineReached() {
            long now = workClockNanos();
            if (startedNanos == Long.MIN_VALUE) {
                startedNanos = now;
                return false;
            }
            return now - startedNanos >= maxNanos;
        }

        /** CPU time is the default; latency-sensitive callers may explicitly select wall time. */
        private long workClockNanos() {
            if (!wallClock && THREADS.isCurrentThreadCpuTimeSupported()) {
                long cpu = THREADS.getCurrentThreadCpuTime();
                if (cpu >= 0L) {
                    return cpu;
                }
            }
            return System.nanoTime();
        }

        private static long saturatedMultiply(long left, long right) {
            if (left <= 0L || right <= 0L) {
                return 0L;
            }
            return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
        }
    }

    private static final class Rational implements Comparable<Rational> {
        private static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
        private static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);
        private static final Rational NEGATIVE_ONE = new Rational(BigInteger.ONE.negate(), BigInteger.ONE);

        private final BigInteger numerator;
        private final BigInteger denominator;

        private Rational(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) {
                throw new ArithmeticException("zero denominator");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }

        static Rational of(BigInteger value) {
            if (value.signum() == 0) {
                return ZERO;
            }
            if (value.equals(BigInteger.ONE)) {
                return ONE;
            }
            if (value.equals(BigInteger.ONE.negate())) {
                return NEGATIVE_ONE;
            }
            return new Rational(value, BigInteger.ONE);
        }

        Rational add(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Rational subtract(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Rational multiply(Rational other) {
            return new Rational(
                    numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        Rational divide(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator), denominator.multiply(other.numerator));
        }

        int signum() {
            return numerator.signum();
        }

        boolean isInteger() {
            return denominator.equals(BigInteger.ONE);
        }

        BigInteger toBigIntegerExact() {
            if (!isInteger()) {
                throw new ArithmeticException("fractional value");
            }
            return numerator;
        }

        BigInteger floor() {
            BigInteger[] divided = numerator.divideAndRemainder(denominator);
            if (numerator.signum() < 0 && divided[1].signum() != 0) {
                return divided[0].subtract(BigInteger.ONE);
            }
            return divided[0];
        }

        @Override
        public int compareTo(Rational other) {
            return numerator.multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Rational rational
                    && numerator.equals(rational.numerator)
                    && denominator.equals(rational.denominator);
        }

        @Override
        public int hashCode() {
            return 31 * numerator.hashCode() + denominator.hashCode();
        }
    }
}

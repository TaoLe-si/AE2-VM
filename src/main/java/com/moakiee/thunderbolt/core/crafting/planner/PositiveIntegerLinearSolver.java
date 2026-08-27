package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Exact feasibility solver for positive-integer linear inequalities.
 *
 * <p>The public problem is {@code A*x >= b, x >= 1, b >= 0}. Phase I of the simplex method is evaluated
 * with arbitrary-precision rational arithmetic, so a feasible system is never rejected because of
 * a coefficient search bound or floating-point rounding. A rational solution can always be scaled
 * to a positive-integer solution because every coefficient and bound is integral. The scaled result
 * is then reduced without violating any constraint.
 */
public final class PositiveIntegerLinearSolver {

    public enum Status {
        SOLVED,
        INFEASIBLE,
        COEFFICIENT_OVERFLOW,
        INVALID_INPUT,
        INTERNAL_ERROR
    }

    /** One inequality of the form {@code coefficients dot x >= minimum}. */
    public record Constraint(long[] coefficients, long minimum) {
        public Constraint {
            Objects.requireNonNull(coefficients, "coefficients");
            coefficients = coefficients.clone();
        }

        @Override
        public long[] coefficients() {
            return coefficients.clone();
        }
    }

    public record Result(Status status, long[] coefficients) {
        public Result {
            Objects.requireNonNull(status, "status");
            coefficients = coefficients == null ? new long[0] : coefficients.clone();
            if (status == Status.SOLVED && coefficients.length == 0) {
                throw new IllegalArgumentException("a solved result requires coefficients");
            }
        }

        @Override
        public long[] coefficients() {
            return coefficients.clone();
        }

        public boolean solved() {
            return status == Status.SOLVED;
        }
    }

    public static Result solve(int variableCount, List<Constraint> constraints) {
        if (variableCount <= 0 || constraints == null) return result(Status.INVALID_INPUT);

        var rows = new ArrayList<ExactConstraint>(constraints.size());
        for (var constraint : constraints) {
            if (constraint == null) return result(Status.INVALID_INPUT);
            if (constraint.minimum() < 0) return result(Status.INVALID_INPUT);
            var coefficients = constraint.coefficients();
            if (coefficients.length != variableCount) return result(Status.INVALID_INPUT);
            var exact = new BigInteger[variableCount];
            for (int i = 0; i < variableCount; i++) {
                exact[i] = BigInteger.valueOf(coefficients[i]);
            }
            rows.add(new ExactConstraint(exact, BigInteger.valueOf(constraint.minimum())));
        }

        if (rows.isEmpty()) {
            var ones = new long[variableCount];
            Arrays.fill(ones, 1L);
            return new Result(Status.SOLVED, ones);
        }

        try {
            return solveExact(variableCount, rows);
        } catch (RuntimeException ignored) {
            // A foundation library must fail closed instead of taking the host mod down with it.
            return result(Status.INTERNAL_ERROR);
        }
    }

    private static Result solveExact(int variableCount, List<ExactConstraint> constraints) {
        int rowCount = constraints.size();
        var shiftedMinimums = new BigInteger[rowCount];
        int artificialCount = 0;
        for (int row = 0; row < rowCount; row++) {
            var constraint = constraints.get(row);
            BigInteger atOnes = BigInteger.ZERO;
            for (var coefficient : constraint.coefficients()) atOnes = atOnes.add(coefficient);
            shiftedMinimums[row] = constraint.minimum().subtract(atOnes);
            if (shiftedMinimums[row].signum() > 0) artificialCount++;
        }

        int auxiliaryOffset = variableCount;
        int artificialOffset = variableCount + rowCount;
        int totalVariables = artificialOffset + artificialCount;
        var tableau = new Rational[rowCount][totalVariables + 1];
        for (var row : tableau) Arrays.fill(row, Rational.ZERO);
        var basis = new int[rowCount];
        var costs = new Rational[totalVariables];
        Arrays.fill(costs, Rational.ZERO);

        int nextArtificial = artificialOffset;
        for (int row = 0; row < rowCount; row++) {
            var constraint = constraints.get(row);
            boolean needsArtificial = shiftedMinimums[row].signum() > 0;
            BigInteger sign = needsArtificial ? BigInteger.ONE : BigInteger.ONE.negate();
            for (int column = 0; column < variableCount; column++) {
                tableau[row][column] = Rational.of(
                        constraint.coefficients()[column].multiply(sign));
            }
            tableau[row][auxiliaryOffset + row] = needsArtificial
                    ? Rational.NEGATIVE_ONE : Rational.ONE;
            tableau[row][totalVariables] = Rational.of(
                    shiftedMinimums[row].multiply(sign));
            if (needsArtificial) {
                tableau[row][nextArtificial] = Rational.ONE;
                basis[row] = nextArtificial;
                costs[nextArtificial] = Rational.NEGATIVE_ONE;
                nextArtificial++;
            } else {
                basis[row] = auxiliaryOffset + row;
            }
        }

        var simplexStatus = maximize(tableau, basis, costs, totalVariables);
        if (simplexStatus != SimplexStatus.OPTIMAL) return result(Status.INTERNAL_ERROR);

        Rational objective = Rational.ZERO;
        for (int row = 0; row < rowCount; row++) {
            objective = objective.add(costs[basis[row]].multiply(tableau[row][totalVariables]));
        }
        if (objective.signum() < 0) return result(Status.INFEASIBLE);
        if (objective.signum() > 0) return result(Status.INTERNAL_ERROR);

        var positiveRational = new Rational[variableCount];
        Arrays.fill(positiveRational, Rational.ONE);
        for (int row = 0; row < rowCount; row++) {
            int basic = basis[row];
            if (basic < variableCount) {
                positiveRational[basic] = tableau[row][totalVariables].add(Rational.ONE);
            }
        }

        BigInteger commonDenominator = BigInteger.ONE;
        for (var value : positiveRational) {
            commonDenominator = lcm(commonDenominator, value.denominator());
        }
        var integer = new BigInteger[variableCount];
        for (int i = 0; i < variableCount; i++) {
            integer[i] = positiveRational[i].numerator()
                    .multiply(commonDenominator.divide(positiveRational[i].denominator()));
        }
        minimizeCoordinates(integer, constraints);
        if (!isFeasible(integer, constraints)) return result(Status.INTERNAL_ERROR);

        var result = new long[variableCount];
        for (int i = 0; i < variableCount; i++) {
            if (integer[i].signum() <= 0 || integer[i].compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                return result(Status.COEFFICIENT_OVERFLOW);
            }
            result[i] = integer[i].longValueExact();
        }
        return new Result(Status.SOLVED, result);
    }

    private static SimplexStatus maximize(
            Rational[][] tableau, int[] basis, Rational[] costs, int variableCount) {
        int rowCount = tableau.length;
        while (true) {
            int entering = -1;
            for (int column = 0; column < variableCount; column++) {
                Rational reduced = costs[column];
                for (int row = 0; row < rowCount; row++) {
                    reduced = reduced.subtract(costs[basis[row]].multiply(tableau[row][column]));
                }
                if (reduced.signum() > 0) {
                    entering = column; // Bland's rule: first eligible variable.
                    break;
                }
            }
            if (entering < 0) return SimplexStatus.OPTIMAL;

            int leaving = -1;
            Rational bestRatio = null;
            for (int row = 0; row < rowCount; row++) {
                var direction = tableau[row][entering];
                if (direction.signum() <= 0) continue;
                var ratio = tableau[row][variableCount].divide(direction);
                if (leaving < 0 || ratio.compareTo(bestRatio) < 0
                        || (ratio.equals(bestRatio) && basis[row] < basis[leaving])) {
                    leaving = row;
                    bestRatio = ratio;
                }
            }
            if (leaving < 0) return SimplexStatus.UNBOUNDED;
            pivot(tableau, basis, leaving, entering, variableCount);
        }
    }

    private static void pivot(
            Rational[][] tableau, int[] basis, int pivotRow, int pivotColumn, int variableCount) {
        var pivot = tableau[pivotRow][pivotColumn];
        for (int column = 0; column <= variableCount; column++) {
            tableau[pivotRow][column] = tableau[pivotRow][column].divide(pivot);
        }
        for (int row = 0; row < tableau.length; row++) {
            if (row == pivotRow) continue;
            var factor = tableau[row][pivotColumn];
            if (factor.signum() == 0) continue;
            for (int column = 0; column <= variableCount; column++) {
                tableau[row][column] = tableau[row][column]
                        .subtract(factor.multiply(tableau[pivotRow][column]));
            }
        }
        basis[pivotRow] = pivotColumn;
    }

    /** Reduces a feasible integer point one coordinate at a time while preserving every row. */
    private static void minimizeCoordinates(
            BigInteger[] values, List<ExactConstraint> constraints) {
        // Reduction is an output-quality step, not part of feasibility. Keep it one bounded pass so
        // adversarial constraints cannot turn a valid solve into an unbounded coordinate-descent loop.
        for (int variable = 0; variable < values.length; variable++) {
            BigInteger lower = BigInteger.ONE;
            for (var constraint : constraints) {
                var coefficient = constraint.coefficients()[variable];
                if (coefficient.signum() <= 0) continue;
                BigInteger other = dot(constraint.coefficients(), values)
                        .subtract(coefficient.multiply(values[variable]));
                BigInteger needed = ceilDivide(
                        constraint.minimum().subtract(other), coefficient);
                if (needed.compareTo(lower) > 0) lower = needed;
            }
            if (lower.compareTo(values[variable]) < 0) {
                values[variable] = lower.max(BigInteger.ONE);
            }
        }
    }

    private static boolean isFeasible(
            BigInteger[] values, List<ExactConstraint> constraints) {
        for (var value : values) if (value.signum() <= 0) return false;
        for (var constraint : constraints) {
            if (dot(constraint.coefficients(), values).compareTo(constraint.minimum()) < 0) return false;
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
        var divided = numerator.divideAndRemainder(positiveDenominator);
        if (divided[1].signum() != 0 && numerator.signum() > 0) {
            return divided[0].add(BigInteger.ONE);
        }
        return divided[0];
    }

    private static BigInteger lcm(BigInteger left, BigInteger right) {
        if (left.signum() == 0 || right.signum() == 0) return BigInteger.ZERO;
        return left.divide(left.gcd(right)).multiply(right).abs();
    }

    private static Result result(Status status) {
        return new Result(status, new long[0]);
    }

    private record ExactConstraint(BigInteger[] coefficients, BigInteger minimum) { }

    private enum SimplexStatus { OPTIMAL, UNBOUNDED }

    private static final class Rational implements Comparable<Rational> {
        private static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
        private static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);
        private static final Rational NEGATIVE_ONE = new Rational(BigInteger.ONE.negate(), BigInteger.ONE);

        private final BigInteger numerator;
        private final BigInteger denominator;

        private Rational(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) throw new ArithmeticException("zero denominator");
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            var gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }

        static Rational of(BigInteger value) {
            if (value.signum() == 0) return ZERO;
            if (value.equals(BigInteger.ONE)) return ONE;
            if (value.equals(BigInteger.ONE.negate())) return NEGATIVE_ONE;
            return new Rational(value, BigInteger.ONE);
        }

        Rational add(Rational other) {
            return new Rational(numerator.multiply(other.denominator)
                    .add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Rational subtract(Rational other) {
            return new Rational(numerator.multiply(other.denominator)
                    .subtract(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Rational multiply(Rational other) {
            return new Rational(numerator.multiply(other.numerator),
                    denominator.multiply(other.denominator));
        }

        Rational divide(Rational other) {
            return new Rational(numerator.multiply(other.denominator),
                    denominator.multiply(other.numerator));
        }

        int signum() { return numerator.signum(); }
        BigInteger numerator() { return numerator; }
        BigInteger denominator() { return denominator; }

        @Override
        public int compareTo(Rational other) {
            return numerator.multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Rational other
                    && numerator.equals(other.numerator)
                    && denominator.equals(other.denominator);
        }

        @Override
        public int hashCode() {
            return 31 * numerator.hashCode() + denominator.hashCode();
        }
    }

    private PositiveIntegerLinearSolver() { }
}

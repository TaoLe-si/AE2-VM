package com.moakiee.thunderbolt.core.crafting.planner;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/** Owns the isolated class loader for the optional downloaded CP-SAT runtime. */
final class CpSatRuntime {
    private static final String BRIDGE_CLASS =
            "com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.CpSatBridge";
    private static final String BRIDGE_RESOURCE = BRIDGE_CLASS.replace('.', '/') + ".class";

    private static volatile Bridge bridge;
    private static volatile Throwable loadFailure;
    private static boolean initializationAttempted;
    private static final String BRIDGE_PACKAGE =
            "com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.";

    private CpSatRuntime() {
    }

    static synchronized boolean install(Path cacheRoot) {
        if (bridge != null) {
            return true;
        }
        if (initializationAttempted) {
            return false;
        }
        initializationAttempted = true;
        try {
            initialize(CpSatRuntimeInstaller.install(cacheRoot));
            return true;
        } catch (Throwable failure) {
            loadFailure = unwrap(failure);
            return false;
        }
    }

    static boolean isAvailable() {
        return bridge != null;
    }

    static Throwable loadFailure() {
        return loadFailure;
    }

    static long[] solve(
            long[][] coefficients,
            long[] minimums,
            long[] upperBounds,
            int executionVariableCount,
            long[][] stockUseNetCoefficients,
            long[] stockUseOffsets,
            long[] stockUseUpperBounds,
            int[] stockUseDistances,
            int[] directUsedVariables,
            int[] directUsedDistances,
            double maxSeconds) {
        Bridge loaded = bridge;
        if (loaded == null) {
            throw new IllegalStateException("CP-SAT runtime is not initialized", loadFailure);
        }
        try {
            return (long[]) loaded.solve().invoke(
                    null,
                    coefficients,
                    minimums,
                    upperBounds,
                    executionVariableCount,
                    stockUseNetCoefficients,
                    stockUseOffsets,
                    stockUseUpperBounds,
                    stockUseDistances,
                    directUsedVariables,
                    directUsedDistances,
                    maxSeconds);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("CP-SAT bridge is inaccessible", impossible);
        } catch (InvocationTargetException failure) {
            Throwable cause = unwrap(failure);
            loadFailure = cause;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("CP-SAT bridge failed", cause);
        }
    }

    static long[] solveRankedPlan(
            long[][] consumed,
            long[][] produced,
            long[][] catalysts,
            long[][] finiteUseAmounts,
            long[][] finiteUseLifetimes,
            int[] outputItems,
            int[] primaryOutputItems,
            long[] primaryOutputAmounts,
            int[] rankGroups,
            int[][] cycleRecipes,
            int[][] cycleInputItems,
            long[][] cycleInputAmounts,
            long[][] cyclePrimitiveFirings,
            long[] stocks,
            long[][] reusableCatalysts,
            int[] reusableItems,
            int[][] reusableCandidatePhysicals,
            long[] reusablePhysicalStocks,
            int[] itemDistances,
            int targetItem,
            long targetAmount,
            long[] firingUpperBounds,
            double maxSeconds) {
        Bridge loaded = bridge;
        if (loaded == null) {
            throw new IllegalStateException("CP-SAT runtime is not initialized", loadFailure);
        }
        try {
            return (long[]) loaded.solveRankedPlan().invoke(
                    null,
                    consumed,
                    produced,
                    catalysts,
                    finiteUseAmounts,
                    finiteUseLifetimes,
                    outputItems,
                    primaryOutputItems,
                    primaryOutputAmounts,
                    rankGroups,
                    cycleRecipes,
                    cycleInputItems,
                    cycleInputAmounts,
                    cyclePrimitiveFirings,
                    stocks,
                    reusableCatalysts,
                    reusableItems,
                    reusableCandidatePhysicals,
                    reusablePhysicalStocks,
                    itemDistances,
                    targetItem,
                    targetAmount,
                    firingUpperBounds,
                    maxSeconds);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("CP-SAT bridge is inaccessible", impossible);
        } catch (InvocationTargetException failure) {
            Throwable cause = unwrap(failure);
            loadFailure = cause;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("CP-SAT ranked bridge failed", cause);
        }
    }

    static long[] chooseFeedbackOption(
            long[][] requirements,
            long[] stocks,
            long[] scoreRanks,
            double maxSeconds) {
        Bridge loaded = bridge;
        if (loaded == null) {
            throw new IllegalStateException("CP-SAT runtime is not initialized", loadFailure);
        }
        try {
            return (long[]) loaded.chooseFeedbackOption().invoke(
                    null, requirements, stocks, scoreRanks, maxSeconds);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("CP-SAT bridge is inaccessible", impossible);
        } catch (InvocationTargetException failure) {
            Throwable cause = unwrap(failure);
            loadFailure = cause;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("CP-SAT feedback bridge failed", cause);
        }
    }

    /** Initializes from Gradle's test runtime without involving production startup downloads. */
    static synchronized boolean initializeFromTestClasspath() {
        if (bridge != null) {
            return true;
        }
        try {
            String platform = CpSatRuntimeInstaller.currentPlatform();
            String nativeName = "ortools-" + platform + "-"
                    + CpSatRuntimeInstaller.ORTOOLS_VERSION + ".jar";
            String javaName = "ortools-java-"
                    + CpSatRuntimeInstaller.ORTOOLS_VERSION + ".jar";
            String protobufName = "protobuf-java-"
                    + CpSatRuntimeInstaller.PROTOBUF_VERSION + ".jar";
            List<Path> artifacts = new java.util.ArrayList<>();
            for (String entry : System.getProperty("java.class.path", "")
                    .split(java.io.File.pathSeparator)) {
                Path path = Path.of(entry);
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (name.equals(javaName) || name.equals(protobufName) || name.equals(nativeName)) {
                    artifacts.add(path);
                }
            }
            if (artifacts.size() != 3) {
                throw new IOException("expected three CP-SAT test artifacts, found " + artifacts);
            }
            initialize(artifacts);
            return true;
        } catch (Throwable failure) {
            loadFailure = unwrap(failure);
            return false;
        }
    }

    private static void initialize(List<Path> artifacts) throws Exception {
        URL[] urls = new URL[artifacts.size()];
        for (int index = 0; index < artifacts.size(); index++) {
            urls[index] = artifacts.get(index).toUri().toURL();
        }
        var loader = new BridgeClassLoader(urls, CpSatRuntime.class.getClassLoader());
        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, true, loader);
            Method initialize = bridgeClass.getMethod("initialize");
            Method solve = bridgeClass.getMethod(
                    "solve",
                    long[][].class,
                    long[].class,
                    long[].class,
                    int.class,
                    long[][].class,
                    long[].class,
                    long[].class,
                    int[].class,
                    int[].class,
                    int[].class,
                    double.class);
            Method solveRankedPlan = bridgeClass.getMethod(
                    "solveRankedPlan",
                    long[][].class,
                    long[][].class,
                    long[][].class,
                    long[][].class,
                    long[][].class,
                    int[].class,
                    int[].class,
                    long[].class,
                    int[].class,
                    int[][].class,
                    int[][].class,
                    long[][].class,
                    long[][].class,
                    long[].class,
                    long[][].class,
                    int[].class,
                    int[][].class,
                    long[].class,
                    int[].class,
                    int.class,
                    long.class,
                    long[].class,
                    double.class);
            Method chooseFeedbackOption = bridgeClass.getMethod(
                    "chooseFeedbackOption",
                    long[][].class,
                    long[].class,
                    long[].class,
                    double.class);
            initialize.invoke(null);
            bridge = new Bridge(loader, solve, solveRankedPlan, chooseFeedbackOption);
            loadFailure = null;
        } catch (Throwable failure) {
            try {
                loader.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    private record Bridge(
            URLClassLoader loader,
            Method solve,
            Method solveRankedPlan,
            Method chooseFeedbackOption) {
    }

    private static final class BridgeClassLoader extends URLClassLoader {
        private BridgeClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!isChildFirst(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith(BRIDGE_PACKAGE)) {
                return super.findClass(name);
            }
            ClassLoader parent = getParent();
            String resource = name.equals(BRIDGE_CLASS) ? BRIDGE_RESOURCE
                    : name.replace('.', '/') + ".class";
            try (InputStream input = parent.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException("missing embedded CP-SAT class " + resource);
                }
                byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException failure) {
                throw new ClassNotFoundException("failed to read embedded CP-SAT class "
                        + resource, failure);
            }
        }

        private static boolean isChildFirst(String name) {
            return isMirroredFromParent(name);
        }

        private static boolean isMirroredFromParent(String name) {
            return name.startsWith(BRIDGE_PACKAGE)
                    || name.startsWith("com.google.ortools.")
                    || name.startsWith("com.google.protobuf.");
        }
    }
}

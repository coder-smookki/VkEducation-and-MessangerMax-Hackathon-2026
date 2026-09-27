package ru.koren.inference;

/** Evaluated analyzers allowed to produce visible advice. Unknown/retired versions fail closed. */
public final class AnalysisPolicy {
    private AnalysisPolicy() {}

    public static final String RULES_VERSION = "rules-baseline-v3";

    public static boolean supports(InferenceContract.Response response) {
        return response.mode() == InferenceContract.Mode.RULES
            && RULES_VERSION.equals(response.modelVersion());
    }
}

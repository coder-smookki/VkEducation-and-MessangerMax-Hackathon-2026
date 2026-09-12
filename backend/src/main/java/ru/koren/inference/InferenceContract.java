package ru.koren.inference;

import java.util.List;
import java.util.UUID;

/** Mirrors contracts/inference.openapi.json. No business-state command exists in this contract. */
public final class InferenceContract {
    private InferenceContract() {}
    public enum Category { LIGHTING, ENTRANCE_DOOR, CLEANING, OTHER }
    public enum Relation { REPORTED_DONE, PLANNED, SYMPTOM_PERSISTS, INSUFFICIENT_INFORMATION, UNRELATED, CONFLICT }
    public enum Mode { STUB, RULES, NLI, MANUAL }
    public enum Reason { MODEL_NOT_CONFIGURED, ANALYSIS_UNAVAILABLE, ANALYZED }
    public record Item(UUID itemId, int contentVersion, int workRound, Category category, String location, String symptom) {}
    public record Request(UUID caseId, int sourceRevision, UUID reportId, String reportText, List<Item> items) {}
    /** Offsets are Unicode code points, start inclusive and end exclusive; never UTF-16 code units. */
    public record Evidence(int start, int end, String quote) {}
    public record Suggestion(UUID itemId, int contentVersion, int workRound, Relation relation,
                             Evidence evidence, Reason reason) {}
    public record Response(UUID caseId, int sourceRevision, UUID reportId, String modelVersion,
                           Mode mode, List<Suggestion> suggestions) {}
}

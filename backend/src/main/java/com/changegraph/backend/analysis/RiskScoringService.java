package com.changegraph.backend.analysis;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RiskScoringService {

    public static final int SCORE_MAX = 100;
    public static final String INTERPRETATION =
            "Score 0-100 from a deterministic baseline, not a trained model. "
                    + "0-24 is LOW, 25-49 is MEDIUM, 50-74 is HIGH, and 75-100 is CRITICAL. "
                    + "The score estimates review priority. It does not mean a function is broken.";

    public record Score(String level, int score, List<String> reasons) {
    }

    public Score score(
            int changedFunctions,
            int impactedCallers,
            int impactedDownstream,
            boolean serviceBoundaryCrossed,
            int changedFunctionsMissingDirectTests,
            int verifiedLinkedIncidentReports,
            boolean resolutionUncertainty) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (changedFunctions > 0) {
            score += Math.min(36, changedFunctions * 12);
            reasons.add(changedFunctions + (changedFunctions == 1 ? " function was" : " functions were") + " directly changed.");
        }
        if (impactedCallers > 0) {
            score += Math.min(24, impactedCallers * 6);
            reasons.add(impactedCallers + " caller" + (impactedCallers == 1 ? " is" : "s are")
                    + " potentially impacted through incoming CALLS relationships.");
        }
        if (impactedDownstream > 0) {
            score += Math.min(24, impactedDownstream * 8);
            reasons.add(impactedDownstream + " downstream function" + (impactedDownstream == 1 ? " is" : "s are")
                    + " potentially impacted through outgoing CALLS relationships.");
        }
        if (serviceBoundaryCrossed) {
            score += 12;
            reasons.add("The change reaches a function in another annotated service.");
        }
        if (changedFunctionsMissingDirectTests > 0) {
            score += 10;
            reasons.add(changedFunctionsMissingDirectTests + " changed function"
                    + (changedFunctionsMissingDirectTests == 1 ? " has" : "s have")
                    + " no direct test relationship in the graph.");
        }
        if (verifiedLinkedIncidentReports > 0) {
            score += 10;
            reasons.add("A verified synthetic incident report is linked to a changed or potentially impacted function. "
                    + "This is not a production incident and does not prove a root cause.");
        }
        if (resolutionUncertainty) {
            score += 5;
            reasons.add("Parser or call-resolution uncertainty means some dependencies may be missing.");
        }
        score = Math.min(SCORE_MAX, score);
        return new Score(levelFor(score), score, List.copyOf(reasons));
    }

    public static String levelFor(int score) {
        if (score >= 75) {
            return "CRITICAL";
        }
        if (score >= 50) {
            return "HIGH";
        }
        if (score >= 25) {
            return "MEDIUM";
        }
        return "LOW";
    }
}

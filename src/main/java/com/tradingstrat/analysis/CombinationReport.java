package com.tradingstrat.analysis;

import java.util.List;

/**
 * Results of a combination search, kept strictly separated into search and holdout.
 *
 * <p>The {@code expectedFalseSurvivors} figure is reported deliberately: it is how many
 * combinations would be expected to clear the search filter by chance alone, given the
 * number of hypotheses tested. If the survivor count is close to it, the survivors carry
 * no information regardless of how good their numbers look.
 */
public record CombinationReport(
        String interval,
        int maxCombinationSize,
        int hypothesesTested,
        List<String> searchWindows,
        List<String> holdoutWindows,
        int minOccurrences,
        double expectedFalseSurvivors,
        int survivorsBull,
        int survivorsBear,
        List<Survivor> bull,
        List<Survivor> bear,
        List<Pinned> pinned,
        List<String> notes
) {

    /**
     * A named combination reported across every window, pass or fail.
     *
     * Windows are listed in order (search windows first, then holdout), so a
     * pre-registered hypothesis can be read straight across and judged on whether it
     * holds up rather than on whether it cleared a filter.
     */
    public record Pinned(
            String combination,
            List<String> windows,
            List<Double> bullEdges,
            List<Integer> bullCounts,
            List<Double> bullHitRates,
            List<Double> bullMeans,
            List<Double> bullBaselines,
            List<Double> bearEdges,
            List<Integer> bearCounts,
            List<Double> bearHitRates,
            List<Double> bearMeans,
            List<Double> bearBaselines
    ) {}

    /**
     * A combination that passed every search window, with its untouched holdout result.
     *
     * @param searchEdges  edge over baseline in each search window, in order
     * @param holdoutEdges the same for the holdout windows — the only figures that count
     * @param holdoutPassed whether it stayed positive across every holdout window
     */
    public record Survivor(
            String combination,
            int size,
            List<Integer> searchCounts,
            List<Double> searchEdges,
            double worstSearchEdge,
            List<Integer> holdoutCounts,
            List<Double> holdoutEdges,
            double worstHoldoutEdge,
            double holdoutHitRate,
            boolean holdoutPassed
    ) {}
}

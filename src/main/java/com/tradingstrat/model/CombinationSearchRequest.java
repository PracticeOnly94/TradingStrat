package com.tradingstrat.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Search indicator combinations under a search/holdout split.
 *
 * Example:
 * {
 *   "topSymbols": 20, "interval": "4h", "bars": 2190, "maxCombinationSize": 4,
 *   "searchWindows": ["2023-01-01", "2024-01-01", "2025-01-01"],
 *   "holdoutWindows": ["2026-01-01", ""]
 * }
 *
 * @param searchWindows  end dates of the windows combinations are selected on
 * @param holdoutWindows end dates of the windows survivors are tested on; these must
 *                       never be consulted while choosing what to test
 * @param minOccurrences occurrences a combination needs per window to be considered
 */
public record CombinationSearchRequest(
        List<String> symbols,
        @Min(1) @Max(50) Integer topSymbols,
        @NotBlank String interval,
        @Min(500) @Max(20000) int bars,
        @Min(1) @Max(6) Integer maxCombinationSize,
        @NotEmpty List<String> searchWindows,
        @NotEmpty List<String> holdoutWindows,
        Integer minOccurrences,

        /*
         * Combinations to report in every window regardless of whether they pass any
         * filter, given as lists of primitive names. This is what makes a pre-registered
         * test possible: a named hypothesis must be visible when it fails, not silently
         * dropped from the survivor list.
         */
        List<List<String>> pinnedCombinations
) {}

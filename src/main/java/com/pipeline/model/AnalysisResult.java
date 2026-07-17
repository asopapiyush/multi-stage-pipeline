package com.pipeline.model;

import java.util.List;
import java.util.Map;

public record AnalysisResult(
    int index,
    String url,
    List<String> links,
    Map<String, Long> wordFrequencies,
    double readabilityScore,
    boolean failed,
    String error
) {
    // ponytail: convenience ctor for success case (existing callers)
    public AnalysisResult(int index, String url, List<String> links, Map<String, Long> wordFrequencies, double readabilityScore) {
        this(index, url, links, wordFrequencies, readabilityScore, false, null);
    }
}

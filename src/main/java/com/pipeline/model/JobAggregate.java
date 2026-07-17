package com.pipeline.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

public class JobAggregate {
    private int documentsProcessed;
    private int documentsErrored;
    private double averageReadability;
    private long totalWordsAnalyzed;
    private Map<String, Long> topWords = new HashMap<>();
    private long startTime;
    private long lastUpdated;

    @JsonProperty("documentsProcessed")
    public int documentsProcessed() { return documentsProcessed; }
    public void setDocumentsProcessed(int documentsProcessed) { this.documentsProcessed = documentsProcessed; }

    @JsonProperty("documentsErrored")
    public int documentsErrored() { return documentsErrored; }
    public void setDocumentsErrored(int documentsErrored) { this.documentsErrored = documentsErrored; }

    @JsonProperty("averageReadability")
    public double averageReadability() { return averageReadability; }
    public void setAverageReadability(double averageReadability) { this.averageReadability = averageReadability; }

    @JsonProperty("totalWordsAnalyzed")
    public long totalWordsAnalyzed() { return totalWordsAnalyzed; }
    public void setTotalWordsAnalyzed(long totalWordsAnalyzed) { this.totalWordsAnalyzed = totalWordsAnalyzed; }

    @JsonProperty("topWords")
    public Map<String, Long> topWords() { return topWords; }
    public void setTopWords(Map<String, Long> topWords) { this.topWords = topWords; }

    @JsonProperty("startTime")
    public long startTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    @JsonProperty("lastUpdated")
    public long lastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
}

package com.pipeline.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ItemStatus {
    private int index;
    private String url;
    private ProcessingStage stage;
    private ProcessingState state;
    private String error;
    private long startTime;
    private long endTime;

    @JsonProperty("index")
    public int index() { return index; }
    public void setIndex(int index) { this.index = index; }

    @JsonProperty("url")
    public String url() { return url; }
    public void setUrl(String url) { this.url = url; }

    @JsonProperty("stage")
    public ProcessingStage stage() { return stage; }
    public void setStage(ProcessingStage stage) { this.stage = stage; }

    @JsonProperty("state")
    public ProcessingState state() { return state; }
    public void setState(ProcessingState state) { this.state = state; }

    @JsonProperty("error")
    public String error() { return error; }
    public void setError(String error) { this.error = error; }

    @JsonProperty("startTime")
    public long startTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    @JsonProperty("endTime")
    public long endTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    private double readabilityScore;
    private long wordCount;
    private java.util.List<String> links;

    @JsonProperty("readabilityScore")
    public double readabilityScore() { return readabilityScore; }
    public void setReadabilityScore(double readabilityScore) { this.readabilityScore = readabilityScore; }

    @JsonProperty("wordCount")
    public long wordCount() { return wordCount; }
    public void setWordCount(long wordCount) { this.wordCount = wordCount; }

    @JsonProperty("links")
    public java.util.List<String> links() { return links; }
    public void setLinks(java.util.List<String> links) { this.links = links; }
}

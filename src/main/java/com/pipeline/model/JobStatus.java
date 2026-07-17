package com.pipeline.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class JobStatus {
    private String jobId;
    private JobState state;
    private List<ItemStatus> items;
    private JobAggregate aggregates;
    private long createdAt;
    private long updatedAt;

    @JsonProperty("jobId")
    public String jobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    @JsonProperty("state")
    public JobState state() { return state; }
    public void setState(JobState state) { this.state = state; }

    @JsonProperty("items")
    public List<ItemStatus> items() { return items; }
    public void setItems(List<ItemStatus> items) { this.items = items; }

    @JsonProperty("aggregates")
    public JobAggregate aggregates() { return aggregates; }
    public void setAggregates(JobAggregate aggregates) { this.aggregates = aggregates; }

    @JsonProperty("createdAt")
    public long createdAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    @JsonProperty("updatedAt")
    public long updatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}

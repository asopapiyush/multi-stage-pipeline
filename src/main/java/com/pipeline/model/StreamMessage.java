package com.pipeline.model;

import lombok.Data;

@Data
public class StreamMessage {
    private String eventType;
    private String jobId;
    private ItemStatus itemStatus;
    private JobAggregate aggregates;
    private long timestamp;
    private int queueDepth;

    public StreamMessage(String eventType, String jobId, ItemStatus itemStatus, JobAggregate aggregates, long timestamp) {
        this.eventType = eventType;
        this.jobId = jobId;
        this.itemStatus = itemStatus;
        this.aggregates = aggregates;
        this.timestamp = timestamp;
    }

    public int getQueueDepth() { return queueDepth; }
    public void setQueueDepth(int queueDepth) { this.queueDepth = queueDepth; }
}

package com.pipeline.model;

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

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public ItemStatus getItemStatus() { return itemStatus; }
    public void setItemStatus(ItemStatus itemStatus) { this.itemStatus = itemStatus; }

    public JobAggregate getAggregates() { return aggregates; }
    public void setAggregates(JobAggregate aggregates) { this.aggregates = aggregates; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getQueueDepth() { return queueDepth; }
    public void setQueueDepth(int queueDepth) { this.queueDepth = queueDepth; }
}

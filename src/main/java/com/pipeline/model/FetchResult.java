package com.pipeline.model;

public record FetchResult(
    int index,
    String url,
    String content,
    FetchStatus status,
    String error,
    long fetchTimeMs
) {
}

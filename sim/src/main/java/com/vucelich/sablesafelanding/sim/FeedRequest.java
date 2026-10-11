package com.vucelich.sablesafelanding.sim;

/**
 * One contraption observed this pass. {@code inWorld} is false when it already lives inside a sub-level.
 */
public record FeedRequest(String id, String kindName, long shapeHash, boolean inWorld, boolean alive) {
    public FeedRequest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("contraption id required");
        }
    }
}

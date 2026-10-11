package com.vucelich.sablesafelanding.sim;

public record FeedAction(Type type, String id, ContraptionKind kind, long shapeHash) {
    public enum Type {
        ADD,
        REMOVE,
        REBUILD,
        KEEP
    }
}

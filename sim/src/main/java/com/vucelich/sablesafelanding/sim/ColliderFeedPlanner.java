package com.vucelich.sablesafelanding.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which world contraptions to add, rebuild, or drop. Stationary assemblies are fed too:
 * an assembled elevator has no world blocks, so velocity is not a reason to skip it.
 */
public final class ColliderFeedPlanner {
    private final Map<String, Slot> fed = new LinkedHashMap<>();

    public List<FeedAction> plan(List<FeedRequest> seen, boolean feedEnabled) {
        Map<String, Slot> next = new LinkedHashMap<>();
        List<FeedAction> actions = new ArrayList<>();
        if (feedEnabled) {
            for (FeedRequest request : seen) {
                ContraptionKind kind = ContraptionKind.parse(request.kindName());
                if (kind == null || !request.inWorld() || !request.alive()) {
                    continue;
                }
                Slot existing = fed.get(request.id());
                if (existing == null) {
                    actions.add(new FeedAction(FeedAction.Type.ADD, request.id(), kind, request.shapeHash()));
                } else if (existing.shapeHash != request.shapeHash() || existing.kind != kind) {
                    actions.add(new FeedAction(FeedAction.Type.REBUILD, request.id(), kind, request.shapeHash()));
                } else {
                    actions.add(new FeedAction(FeedAction.Type.KEEP, request.id(), kind, request.shapeHash()));
                }
                next.put(request.id(), new Slot(kind, request.shapeHash()));
            }
        }
        for (Map.Entry<String, Slot> entry : fed.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                Slot slot = entry.getValue();
                actions.add(new FeedAction(FeedAction.Type.REMOVE, entry.getKey(), slot.kind, slot.shapeHash));
            }
        }
        fed.clear();
        fed.putAll(next);
        return List.copyOf(actions);
    }

    public int size() {
        return fed.size();
    }

    public void forget(String id) {
        fed.remove(id);
    }

    private record Slot(ContraptionKind kind, long shapeHash) {
    }
}

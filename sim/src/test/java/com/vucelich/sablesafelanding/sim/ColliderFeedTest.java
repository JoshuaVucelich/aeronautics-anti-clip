package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColliderFeedTest {
    @Test
    void elevatorIsAddedAndUnknownKindsAreIgnored() {
        ColliderFeedPlanner planner = new ColliderFeedPlanner();
        List<FeedAction> actions = planner.plan(List.of(
                new FeedRequest("elevator-1", "elevator", 11L, true, true),
                new FeedRequest("windmill-1", "windmill", 22L, true, true),
                new FeedRequest("sail-1", "unknown", 33L, true, true)
        ), true);

        assertEquals(List.of(new FeedAction(FeedAction.Type.ADD, "elevator-1", ContraptionKind.ELEVATOR, 11L)), actions);
        assertEquals(1, planner.size());
    }

    @Test
    void bearingPistonAndGantryAreFedEvenWhenStationary() {
        ColliderFeedPlanner planner = new ColliderFeedPlanner();
        List<FeedAction> actions = planner.plan(List.of(
                new FeedRequest("bearing", "bearing", 1L, true, true),
                new FeedRequest("clockwork", "clockwork", 2L, true, true),
                new FeedRequest("stabilized", "stabilized", 3L, true, true),
                new FeedRequest("piston", "piston", 4L, true, true),
                new FeedRequest("gantry", "gantry", 5L, true, true)
        ), true);

        assertEquals(5, actions.size());
        assertTrue(actions.stream().allMatch(action -> action.type() == FeedAction.Type.ADD));
        assertEquals(ContraptionKind.BEARING, actions.get(0).kind());
        assertEquals(ContraptionKind.BEARING, actions.get(1).kind());
        assertEquals(ContraptionKind.BEARING, actions.get(2).kind());
        assertEquals(ContraptionKind.PISTON, actions.get(3).kind());
        assertEquals(ContraptionKind.GANTRY, actions.get(4).kind());
    }

    @Test
    void blockChangeRebuildsAndRemovalUnfeeds() {
        ColliderFeedPlanner planner = new ColliderFeedPlanner();
        planner.plan(List.of(new FeedRequest("elevator-1", "elevator", 11L, true, true)), true);

        List<FeedAction> kept = planner.plan(List.of(new FeedRequest("elevator-1", "elevator", 11L, true, true)), true);
        assertEquals(FeedAction.Type.KEEP, kept.get(0).type());

        List<FeedAction> rebuilt = planner.plan(List.of(new FeedRequest("elevator-1", "elevator", 99L, true, true)), true);
        assertEquals(FeedAction.Type.REBUILD, rebuilt.get(0).type());
        assertEquals(99L, rebuilt.get(0).shapeHash());

        List<FeedAction> removed = planner.plan(List.of(), true);
        assertEquals(FeedAction.Type.REMOVE, removed.get(0).type());
        assertEquals("elevator-1", removed.get(0).id());
        assertEquals(0, planner.size());
    }

    @Test
    void contraptionsInsideASubLevelStayOnSablesPath() {
        ColliderFeedPlanner planner = new ColliderFeedPlanner();
        List<FeedAction> actions = planner.plan(List.of(
                new FeedRequest("ship-elevator", "elevator", 11L, false, true),
                new FeedRequest("dead", "gantry", 12L, true, false)
        ), true);
        assertTrue(actions.isEmpty());
        assertEquals(0, planner.size());
    }

    @Test
    void disablingTheFeedRemovesWhatWasAdded() {
        ColliderFeedPlanner planner = new ColliderFeedPlanner();
        planner.plan(List.of(new FeedRequest("elevator-1", "elevator", 11L, true, true)), true);
        List<FeedAction> actions = planner.plan(List.of(
                new FeedRequest("elevator-1", "elevator", 11L, true, true)
        ), false);
        assertEquals(FeedAction.Type.REMOVE, actions.get(0).type());
        assertEquals(0, planner.size());
    }
}

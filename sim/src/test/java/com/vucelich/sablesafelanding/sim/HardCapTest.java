package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardCapTest {
    @Test
    void aStepThatWouldPassTheCapIsNotStarted() {
        TickBudget budget = new TickBudget();
        budget.begin(1, 2_000);
        budget.charge(1_500_000);
        assertTrue(budget.allows(400_000));
        assertFalse(budget.allows(600_000));
        assertEquals(1, budget.exhaustedTicks());
        budget.defer();
        assertEquals(1, budget.deferredSteps());
    }

    @Test
    void bulkWorkLeavesAQuarterForWheelRays() {
        TickBudget budget = new TickBudget();
        budget.begin(1, 2_000);
        budget.charge(1_400_000);
        assertFalse(budget.allowsBulk(200_000));
        assertTrue(budget.allows(200_000));
        budget.charge(200_000);
        assertTrue(budget.allows(400_000));
    }

    @Test
    void lowSettingsAreRaisedToTheFloorAndZeroStaysOff() {
        assertEquals(0, TickBudget.effectiveCapMicros(0));
        assertEquals(0, TickBudget.effectiveCapMicros(-5));
        assertEquals(TickBudget.MIN_CAP_MICROS, TickBudget.effectiveCapMicros(50));
        assertEquals(3_000, TickBudget.effectiveCapMicros(3_000));
        TickBudget budget = new TickBudget();
        budget.begin(1, 50);
        assertEquals(TickBudget.MIN_CAP_MICROS * 1_000L, budget.capNanos());
        budget.begin(2, 0);
        assertFalse(budget.allows(0));
    }

    @Test
    void estimateLearnsSlowStepsAtOnceAndFastOnesSlowly() {
        CostEstimate cost = new CostEstimate(100, 10);
        assertEquals(10_000, cost.estimate(100));
        cost.observe(100, 30_000);
        assertEquals(30_000, cost.estimate(100));
        cost.observe(100, 10_000);
        assertEquals(25_000, cost.estimate(100));
        // A much slower step raises the estimate at most fourfold at once.
        cost.observe(100, 10_000_000);
        assertEquals(100_000, cost.estimate(100));
        for (int i = 0; i < 40; i++) {
            cost.observe(100, 10_000);
        }
        for (int i = 0; i < 200; i++) {
            cost.observe(100, 1);
        }
        assertEquals(1_000, cost.estimate(100));
        assertEquals(0, cost.estimate(0));
    }

    @Test
    void cappedSlicesNeverPassTheCapByMoreThanOneMisjudgedSlice() {
        TickBudget budget = new TickBudget();
        CostEstimate cost = new CostEstimate(1_000, 1);
        budget.begin(1, 1_000);
        int slices = 0;
        while (budget.allows(cost.estimate(1))) {
            budget.charge(900);
            cost.observe(1, 900);
            slices++;
        }
        assertTrue(slices > 0);
        assertTrue(budget.spentNanos() <= budget.capNanos());
    }

    @Test
    void rescueBandSkipsShipsFarFromEveryLine() {
        assertFalse(RescueBand.inBand(60, 80, 0, -100, 400, -70));
        assertTrue(RescueBand.inBand(-40, -30, 0, -100, 400, -70));
        assertTrue(RescueBand.inBand(370, 390, 0, -100, 400, Double.NaN));
        assertTrue(RescueBand.inBand(60, 80, -200, -100, 400, -70));
        assertFalse(RescueBand.inBand(-30, -20, 0, -100, 400, Double.NaN));
        assertTrue(RescueBand.inBand(Double.NaN, 80, 0, -100, 400, -70));
    }

    @Test
    void oneSlowOutlierCannotLockWorkOutForGood() {
        CostEstimate cost = new CostEstimate(60.0, 2.0);
        // A cold first run: one unit took 2 ms.
        cost.observe(1, 2_000_000L);
        TickBudget budget = new TickBudget();
        int retriedAt = -1;
        for (int tick = 0; tick < 40 && retriedAt < 0; tick++) {
            budget.begin(tick, 2000);
            if (budget.allowsBulk(cost.estimate(256))) {
                retriedAt = tick;
            } else {
                cost.relax();
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(retriedAt >= 0 && retriedAt < 20, "retried at " + retriedAt);
    }

    @Test
    void aTinySampleIsNotReadAsAHugeCostPerUnit() {
        CostEstimate cost = new CostEstimate(150.0, 5.0, 64);
        cost.observe(1, 100_000L);
        org.junit.jupiter.api.Assertions.assertTrue(cost.nanosPerUnit() <= 600.0, "per unit " + cost.nanosPerUnit());
        TickBudget budget = new TickBudget();
        budget.begin(1, 2000);
        org.junit.jupiter.api.Assertions.assertTrue(budget.allowsBulk(cost.estimate(256)));
    }
}

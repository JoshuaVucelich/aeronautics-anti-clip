package com.vucelich.sablesafelanding.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckIndexTest {
    private static Set<Long> cover(List<Aabb> boxes) {
        Set<Long> cells = new HashSet<>();
        for (Aabb box : boxes) {
            for (int x = (int) box.minX(); x < (int) box.maxX(); x++) {
                for (int y = (int) box.minY(); y < (int) box.maxY(); y++) {
                    for (int z = (int) box.minZ(); z < (int) box.maxZ(); z++) {
                        assertTrue(cells.add(GridMerger.pack(x, y, z)), "a cell is in two boxes");
                    }
                }
            }
        }
        return cells;
    }

    private static long[] randomCells(Random random, int sizeX, int sizeY, int sizeZ, int offset) {
        List<Long> cells = new ArrayList<>();
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (y == 0 || random.nextInt(3) == 0) {
                        cells.add(GridMerger.pack(x - offset, y - offset, z - offset));
                    }
                }
            }
        }
        long[] out = new long[cells.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = cells.get(i);
        }
        return out;
    }

    @Test
    void packRoundTripsNegativeCoordinates() {
        long cell = GridMerger.pack(-5, 300, -1_000_000);
        assertEquals(-5, GridMerger.unpackX(cell));
        assertEquals(300, GridMerger.unpackY(cell));
        assertEquals(-1_000_000, GridMerger.unpackZ(cell));
    }

    @Test
    void mergeCoversExactlyTheCells() {
        Random random = new Random(7);
        for (int round = 0; round < 20; round++) {
            long[] cells = randomCells(random, 9, 4, 11, round % 3);
            Set<Long> expected = new HashSet<>();
            for (long cell : cells) {
                expected.add(cell);
            }
            assertEquals(expected, cover(GridMerger.merge(cells, cells.length)));
            // Locked axes still cover exactly, and boxes only grow along the open axis.
            List<Aabb> columns = GridMerger.merge(cells, cells.length, false, true, false);
            assertEquals(expected, cover(columns));
            for (Aabb box : columns) {
                assertEquals(1.0, box.maxX() - box.minX(), 1.0e-9);
                assertEquals(1.0, box.maxZ() - box.minZ(), 1.0e-9);
            }
        }
    }

    @Test
    void mergeJoinsAFullPlateIntoOneBox() {
        List<Long> cells = new ArrayList<>();
        for (int x = 0; x < 41; x++) {
            for (int z = 0; z < 41; z++) {
                cells.add(GridMerger.pack(x, 0, z));
            }
        }
        long[] packed = cells.stream().mapToLong(Long::longValue).toArray();
        List<Aabb> boxes = GridMerger.merge(packed, packed.length);
        assertEquals(1, boxes.size());
        assertEquals(new Aabb(0, 0, 0, 41, 1, 41), boxes.get(0));
    }

    @Test
    void mergeOfFortyThousandBlocksIsFast() {
        long[] cells = randomCells(new Random(3), 81, 6, 81, 40);
        assertTrue(cells.length > 15_000);
        long start = System.nanoTime();
        List<Aabb> boxes = GridMerger.merge(cells, cells.length);
        long millis = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(cells.length, cover(boxes).size());
        // The old merger takes about 50 s on 6,854 blocks. This must stay far under a second.
        assertTrue(millis < 1_000, "merge took " + millis + " ms");
    }

    @Test
    void regionQueryMatchesBruteForce() {
        Random random = new Random(11);
        long[] cells = randomCells(random, 70, 5, 70, 35);
        DeckIndex index = new DeckIndex();
        for (long cell : cells) {
            index.add(GridMerger.unpackX(cell), GridMerger.unpackY(cell), GridMerger.unpackZ(cell));
        }
        index.seal();
        assertEquals(cells.length, index.size());
        for (int round = 0; round < 50; round++) {
            int x0 = random.nextInt(90) - 45;
            int z0 = random.nextInt(90) - 45;
            int y0 = random.nextInt(8) - 37;
            int x1 = x0 + random.nextInt(20);
            int z1 = z0 + random.nextInt(20);
            int y1 = y0 + random.nextInt(6);
            Set<Long> expected = new HashSet<>();
            for (long cell : cells) {
                int x = GridMerger.unpackX(cell);
                int y = GridMerger.unpackY(cell);
                int z = GridMerger.unpackZ(cell);
                if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) {
                    expected.add(cell);
                }
            }
            DeckIndex.Region region = index.query(x0, y0, z0, x1, y1, z1, 1_000_000);
            Set<Long> got = new HashSet<>();
            for (int i = 0; i < region.count(); i++) {
                got.add(region.cells()[i]);
            }
            assertFalse(region.truncated());
            assertEquals(expected, got);
        }
    }

    @Test
    void regionQueryStopsAtTheCap() {
        DeckIndex index = new DeckIndex();
        for (int x = 0; x < 50; x++) {
            for (int z = 0; z < 50; z++) {
                index.add(x, 0, z);
            }
        }
        index.seal();
        DeckIndex.Region region = index.query(0, 0, 0, 49, 0, 49, 100);
        assertEquals(100, region.count());
        assertTrue(region.truncated());
    }

    @Test
    void budgetRunsOutWithinATickAndRefillsOnTheNext() {
        TickBudget budget = new TickBudget();
        budget.begin(1, 2_000);
        assertTrue(budget.allows());
        budget.charge(1_500_000);
        assertTrue(budget.allows());
        budget.charge(600_000);
        assertFalse(budget.allows());
        assertFalse(budget.allows());
        assertEquals(1, budget.exhaustedTicks());
        budget.begin(1, 2_000);
        assertFalse(budget.allows());
        budget.begin(2, 2_000);
        assertTrue(budget.allows());
        budget.begin(3, 0);
        assertFalse(budget.allows());
        assertEquals(2, budget.exhaustedTicks());
    }

    @Test
    void oldMergerAndGridMergerCoverTheSameSpace() {
        Random random = new Random(5);
        long[] cells = randomCells(random, 8, 3, 8, 0);
        List<Aabb> unit = new ArrayList<>();
        for (long cell : cells) {
            int x = GridMerger.unpackX(cell);
            int y = GridMerger.unpackY(cell);
            int z = GridMerger.unpackZ(cell);
            unit.add(new Aabb(x, y, z, x + 1, y + 1, z + 1));
        }
        assertEquals(cover(AabbMerger.merge(unit)), cover(GridMerger.merge(cells, cells.length)));
    }
}

package com.koalaguard.engine.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the sweep/intersection math every clip and reach test relies on. */
class AABBTest {

    private static AABB unit() {
        return new AABB(0, 0, 0, 1, 1, 1);
    }

    @Test
    void sweepThroughCentreHits() {
        assertTrue(unit().sweep(-1, 0.5, 0.5, 2, 0.5, 0.5));
    }

    @Test
    void sweepStartingInsideHits() {
        assertTrue(unit().sweep(0.5, 0.5, 0.5, 5, 5, 5));
    }

    @Test
    void sweepParallelMissOutsideSlab() {
        // Runs parallel to the box one block above it.
        assertFalse(unit().sweep(-1, 2.0, 0.5, 2, 2.0, 0.5));
    }

    @Test
    void sweepDiagonalCornerClip() {
        // Diagonal through the box volume.
        assertTrue(unit().sweep(-0.5, -0.5, -0.5, 1.5, 1.5, 1.5));
        // Diagonal that passes clearly outside the corner.
        assertFalse(unit().sweep(-1.0, 1.5, -1.0, 1.5, 4.0, 1.5));
    }

    @Test
    void sweepSegmentStopsShortOfBox() {
        // Heading straight at the box but the segment ends before reaching it.
        assertFalse(unit().sweep(-3, 0.5, 0.5, -1.5, 0.5, 0.5));
    }

    @Test
    void playerBoxDimensions() {
        AABB p = AABB.player(10, 64, -10, 1.8);
        org.junit.jupiter.api.Assertions.assertEquals(9.7, p.minX, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(10.3, p.maxX, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(64.0, p.minY, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(65.8, p.maxY, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(-10.3, p.minZ, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(-9.7, p.maxZ, 1e-9);
    }

    @Test
    void intersectsIsExclusiveAtTouch() {
        // Boxes sharing only a face do NOT intersect (strict >/<) — matches
        // vanilla collision, where standing flush against a wall is legal.
        assertFalse(unit().intersects(new AABB(1, 0, 0, 2, 1, 1)));
        assertTrue(unit().intersects(new AABB(0.99, 0, 0, 2, 1, 1)));
    }

    @Test
    void expandGrowsSymmetrically() {
        AABB e = unit().expand(0.5, 0, 0.25);
        org.junit.jupiter.api.Assertions.assertEquals(-0.5, e.minX, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(1.5, e.maxX, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(0.0, e.minY, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(1.0, e.maxY, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(-0.25, e.minZ, 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(1.25, e.maxZ, 1e-9);
    }
}

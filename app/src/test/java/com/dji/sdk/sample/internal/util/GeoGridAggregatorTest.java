package com.dji.sdk.sample.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers R10 — one aggregated record per real location. */
public class GeoGridAggregatorTest {

    // ~Bandung-area coordinates; 4 decimals => ~11 m cells.
    // Chosen at a cell CENTER (-6.91470) so small GPS jitter stays inside
    // one cell. Note (known MAUP boundary effect): a pile located exactly
    // on a cell edge can legitimately map to two adjacent cells under GPS
    // jitter — the dashboard's KDE smoothing absorbs this.
    private static final double LAT = -6.914700;
    private static final double LNG = 107.609800;

    @Test
    public void stationaryPile_10sOfCycles_writesOnce() {
        GeoGridAggregator g = new GeoGridAggregator();
        // 25 detection cycles (~10 s at 400 ms), 1 object, GPS jitter < 11 m
        int writes = 0;
        for (int i = 0; i < 25; i++) {
            double jitter = (i % 2 == 0) ? 0.00002 : -0.00002; // ~2 m
            if (g.recordCount(LAT + jitter, LNG, 1) > 0) writes++;
        }
        assertEquals("exactly one Firestore write for a stationary pile", 1, writes);
        assertEquals(1, g.getCellCount());
    }

    @Test
    public void peakIncrease_triggersOneMoreWrite() {
        GeoGridAggregator g = new GeoGridAggregator();
        assertEquals(1, g.recordCount(LAT, LNG, 1));   // first sighting -> write
        assertEquals(-1, g.recordCount(LAT, LNG, 1));  // same count -> no write
        assertEquals(3, g.recordCount(LAT, LNG, 3));   // more piles visible -> write
        assertEquals(-1, g.recordCount(LAT, LNG, 2));  // fewer than peak -> no write
        assertEquals(3, g.getPeak(LAT, LNG));
    }

    @Test
    public void adjacentCells_produceSeparateRecords() {
        GeoGridAggregator g = new GeoGridAggregator();
        // Two piles ~22 m apart (0.0002 deg) => different 4-decimal cells
        assertTrue(g.recordCount(LAT, LNG, 1) > 0);
        assertTrue(g.recordCount(LAT + 0.0002, LNG, 1) > 0);
        assertEquals(2, g.getCellCount());
    }

    @Test
    public void sameCell_peakNotSum() {
        GeoGridAggregator g = new GeoGridAggregator();
        g.recordCount(LAT, LNG, 2);
        g.recordCount(LAT, LNG, 2);
        g.recordCount(LAT, LNG, 2);
        assertEquals("peak, not accumulated sum", 2, g.getPeak(LAT, LNG));
    }

    @Test
    public void gpsGuards_preserved() {
        GeoGridAggregator g = new GeoGridAggregator();
        assertEquals(-1, g.recordCount(Double.NaN, LNG, 1));
        assertEquals(-1, g.recordCount(LAT, Double.NaN, 1));
        assertEquals(-1, g.recordCount(0, 0, 1));
        assertEquals(-1, g.recordCount(LAT, LNG, 0));
        assertEquals(0, g.getCellCount());
    }

    @Test
    public void cellKey_isStableAndLocaleSafe() {
        GeoGridAggregator g = new GeoGridAggregator();
        assertEquals("-6.9147,107.6098", g.cellKey(LAT, LNG));
        // decimal point (not comma) regardless of device locale
        assertTrue(g.cellKey(LAT, LNG).contains("."));
    }

    @Test
    public void reset_clearsAllCells() {
        GeoGridAggregator g = new GeoGridAggregator();
        g.recordCount(LAT, LNG, 2);
        g.reset();
        assertEquals(0, g.getCellCount());
        assertEquals("after reset the same cell writes again",
                1, g.recordCount(LAT, LNG, 1));
    }
}

package com.dji.sdk.sample.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class MetricsLoggerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void happyPath_100FramesProduce100WellFormedRows() throws Exception {
        MetricsLogger logger = new MetricsLogger(tmp.newFolder("metrics"));
        long t0 = 1_000_000L;
        for (int i = 0; i < 100; i++) {
            logger.logFrame(t0 + i * 400L, 30, 200, 0, i % 3, 1, 1, true, 400);
        }
        List<String> lines = Files.readAllLines(logger.getLogFile().toPath());
        assertEquals(101, lines.size()); // header + 100 rows
        assertEquals(MetricsLogger.HEADER, lines.get(0));
        int cols = MetricsLogger.HEADER.split(",").length;
        long prevTs = Long.MIN_VALUE;
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            assertEquals("row " + i + " column count", cols, parts.length);
            long ts = Long.parseLong(parts[0]);
            assertTrue("timestamps monotonic", ts > prevTs);
            prevTs = ts;
        }
    }

    @Test
    public void zeroDetections_rowStillWritten() throws Exception {
        MetricsLogger logger = new MetricsLogger(tmp.newFolder("metrics"));
        logger.logFrame(123L, 10, 20, 0, 0, 0, 0, false, 400);
        List<String> lines = Files.readAllLines(logger.getLogFile().toPath());
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains(",0,0,0,"));
    }

    @Test
    public void rotation_boundsFileSize() throws Exception {
        File dir = tmp.newFolder("metrics");
        MetricsLogger logger = new MetricsLogger(dir, 2048); // tiny rotation size
        for (int i = 0; i < 200; i++) {
            logger.logFrame(i, 30, 200, 0, 1, 1, 1, true, 400);
        }
        assertTrue("rotated file exists", logger.getRotatedFile().exists());
        assertTrue("active file bounded", logger.getLogFile().length() <= 4096);
        // active file must still start with a header after rotation
        List<String> lines = Files.readAllLines(logger.getLogFile().toPath());
        assertEquals(MetricsLogger.HEADER, lines.get(0));
    }

    @Test
    public void unwritableDirectory_degradesToNoOpWithoutThrowing() {
        MetricsLogger logger = new MetricsLogger(null);
        assertFalse(logger.isEnabled());
        // must not throw
        logger.logFrame(1, 2, 3, 4, 5, 6, 7, true, 400);
        logger.logEvent("TAG", "message");
    }
}

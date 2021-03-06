package org.mp.naumann.processor.helper;

import org.mp.naumann.processor.batch.Batch;
import org.mp.naumann.processor.handler.BatchHandler;

import static org.junit.Assert.assertEquals;

public class OccurenceCountingBatchHandler implements BatchHandler {
    private static int num = 0;
    private final int occurance;

    public OccurenceCountingBatchHandler(int occurance) {
        this.occurance = occurance;
    }

    public static void reset() {
        num = 0;
    }

    @Override
    public void handleBatch(Batch batch) {
        assertEquals(num, occurance);
        num++;
    }
}

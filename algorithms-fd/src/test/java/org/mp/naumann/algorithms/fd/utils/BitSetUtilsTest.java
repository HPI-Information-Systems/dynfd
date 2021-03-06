package org.mp.naumann.algorithms.fd.utils;

import org.apache.lucene.util.OpenBitSet;
import org.junit.Test;
import org.mp.naumann.algorithms.fd.utils.BitSetUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BitSetUtilsTest {

    @Test
    public void testContainment() {
        OpenBitSet a = new OpenBitSet(3);
        OpenBitSet b = new OpenBitSet(3);
        b.fastSet(1);
        assertTrue(BitSetUtils.isContained(a, b));
        a.fastSet(0);
        assertFalse(BitSetUtils.isContained(a, b));
    }

    @Test
    public void testDifferentLength() {
        OpenBitSet a = new OpenBitSet(3);
        OpenBitSet b = new OpenBitSet(4);
        b.fastSet(1);
        assertTrue(BitSetUtils.isContained(a, b));
        b.fastSet(3);
        assertTrue(BitSetUtils.isContained(a, b));
    }

}

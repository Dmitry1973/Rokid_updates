package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InputArbiterTest {
    @Test public void swipeWinsOverDuplicateKeys() {
        InputArbiter a = new InputArbiter();
        a.touchDown(1000);
        assertFalse(a.acceptKey(1100));        // system key during the swipe
        a.touchUp(1200);
        assertTrue(a.acceptSwipe(1200));       // the swipe itself
        assertFalse(a.acceptKey(1250));        // late duplicate key
        assertFalse(a.acceptKey(1500));
    }

    @Test public void wheelBurstGivesOneStep() {
        InputArbiter a = new InputArbiter();
        assertTrue(a.acceptKey(5000));
        assertFalse(a.acceptKey(5040));
        assertFalse(a.acceptKey(5200));
        assertTrue(a.acceptKey(5300));
    }

    @Test public void separateSwipesEachStep() {
        InputArbiter a = new InputArbiter();
        for (int i = 0; i < 4; i++) {
            long t = 10_000 + i * 600;
            a.touchDown(t);
            a.touchUp(t + 200);
            assertTrue(a.acceptSwipe(t + 200));
        }
    }
}

package io.github.jukomu.desktop.lifecycle;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloseSequenceTest {
    @Test
    void keepsDeclaredOrderAndContinuesAfterFailure() {
        List<String> closed = new ArrayList<>();

        CloseSequence.run(
                LoggerFactory.getLogger(CloseSequenceTest.class),
                new CloseSequence.Step("first", () -> closed.add("first")),
                new CloseSequence.Step("failed", () -> {
                    closed.add("failed");
                    throw new IllegalStateException("simulated close failure");
                }),
                new CloseSequence.Step("last", () -> closed.add("last"))
        );

        assertEquals(List.of("first", "failed", "last"), closed);
    }
}

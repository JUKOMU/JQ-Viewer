package io.github.jukomu.desktop.backend;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendRouteTest {
    @Test
    void staleOrEmptySavedDomainFallsBackToAuto() {
        assertFalse(Backend.isSavedDomainCurrent(
            "www.expired.example", Map.of("www.current.example", 0)));
        assertFalse(Backend.isSavedDomainCurrent(
            "", Map.of("www.current.example", 0)));
    }

    @Test
    void currentButUnreachableSavedDomainRemainsSelectable() {
        assertTrue(Backend.isSavedDomainCurrent(
            "www.current.example", Map.of("www.current.example", -1)));
    }
}

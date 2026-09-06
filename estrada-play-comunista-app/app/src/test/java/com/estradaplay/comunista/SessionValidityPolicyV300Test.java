package com.estradaplay.comunista;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SessionValidityPolicyV300Test {
    @Test public void onlyUnauthorizedAndForbiddenAreExplicitRevocations() {
        assertTrue(SessionValidityPolicyV300.isExplicitRevocation(401));
        assertTrue(SessionValidityPolicyV300.isExplicitRevocation(403));
        assertFalse(SessionValidityPolicyV300.isExplicitRevocation(0));
        assertFalse(SessionValidityPolicyV300.isExplicitRevocation(408));
        assertFalse(SessionValidityPolicyV300.isExplicitRevocation(429));
        assertFalse(SessionValidityPolicyV300.isExplicitRevocation(500));
        assertFalse(SessionValidityPolicyV300.isExplicitRevocation(503));
    }
}

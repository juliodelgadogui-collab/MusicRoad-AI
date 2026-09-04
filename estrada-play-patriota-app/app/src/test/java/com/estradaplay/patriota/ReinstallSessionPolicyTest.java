package com.estradaplay.patriota;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReinstallSessionPolicyTest {
    @Test public void rememberedAccountOnlineWithoutSecureSessionRequiresLogin() {
        assertTrue(ReinstallSessionPolicy.shouldRequireLogin(true, true, false));
    }

    @Test public void rememberedAccountWithSecureSessionKeepsNormalBoot() {
        assertFalse(ReinstallSessionPolicy.shouldRequireLogin(true, true, true));
    }

    @Test public void offlineUseDoesNotForceLogin() {
        assertFalse(ReinstallSessionPolicy.shouldRequireLogin(true, false, false));
    }

    @Test public void freshInstallWithoutRememberedAccountUsesNormalFirstLoginFlow() {
        assertFalse(ReinstallSessionPolicy.shouldRequireLogin(false, true, false));
    }
}

package com.estradaplay.comunista;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class PremiumBrandTextFixTest {
    @Test public void removesLegacyFullBrand() {
        assertEquals("Estrada Play", PremiumBrandTextFix.sanitize("Estrada Play Comunista"));
        assertEquals("Estrada Play", PremiumBrandTextFix.sanitize("ESTRADA PLAY COMUNISTA UNIVERSAL"));
        assertEquals("Estrada Play", PremiumBrandTextFix.sanitize("EstradaPlay Comunista"));
    }

    @Test public void removesLegacyQualifierInsideDynamicServerText() {
        String value = PremiumBrandTextFix.sanitize("Assinatura Estrada Play Comunista · Premium");
        assertEquals("Assinatura Estrada Play · Premium", value);
        assertFalse(value.toLowerCase().contains("comunista"));
    }

    @Test public void normalizesLegacyAcronym() {
        assertEquals("EP · Central", PremiumBrandTextFix.sanitize("EPC · Central"));
    }
}

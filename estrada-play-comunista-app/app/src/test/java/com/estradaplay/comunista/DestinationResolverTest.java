package com.estradaplay.comunista;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class DestinationResolverTest {

    @Test public void bomJesusPrefersMunicipalityOverPirapetingaDistrict() throws Exception {
        String raw = "["
                + "{\"lat\":\"-21.0700\",\"lon\":\"-41.6900\",\"name\":\"Pirapetinga de Bom Jesus do Itabapoana\",\"display_name\":\"Pirapetinga de Bom Jesus do Itabapoana, Bom Jesus do Itabapoana, Rio de Janeiro, Brasil\",\"type\":\"village\",\"addresstype\":\"village\",\"importance\":0.35,\"address\":{\"village\":\"Pirapetinga de Bom Jesus do Itabapoana\",\"municipality\":\"Bom Jesus do Itabapoana\",\"state\":\"Rio de Janeiro\",\"ISO3166-2-lvl4\":\"BR-RJ\"}},"
                + "{\"lat\":\"-21.1337\",\"lon\":\"-41.6820\",\"name\":\"Bom Jesus do Itabapoana\",\"display_name\":\"Bom Jesus do Itabapoana, Rio de Janeiro, Brasil\",\"type\":\"administrative\",\"addresstype\":\"municipality\",\"importance\":0.55,\"address\":{\"municipality\":\"Bom Jesus do Itabapoana\",\"state\":\"Rio de Janeiro\",\"ISO3166-2-lvl4\":\"BR-RJ\"}}"
                + "]";

        List<DestinationStore.Destination> results = DestinationResolver.rankJsonForTest("Bom Jesus do Itabapoana - RJ", raw);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).label.startsWith("Bom Jesus do Itabapoana,"));
    }

    @Test public void rioDeJaneiroPrefersCityOverState() throws Exception {
        String raw = "["
                + "{\"lat\":\"-22.2500\",\"lon\":\"-42.5000\",\"name\":\"Rio de Janeiro\",\"display_name\":\"Rio de Janeiro, Brasil\",\"type\":\"administrative\",\"addresstype\":\"state\",\"importance\":0.80,\"address\":{\"state\":\"Rio de Janeiro\"}},"
                + "{\"lat\":\"-22.9110\",\"lon\":\"-43.2094\",\"name\":\"Rio de Janeiro\",\"display_name\":\"Rio de Janeiro, Região Geográfica Imediata do Rio de Janeiro, Rio de Janeiro, Brasil\",\"type\":\"administrative\",\"addresstype\":\"municipality\",\"importance\":0.90,\"address\":{\"city\":\"Rio de Janeiro\",\"municipality\":\"Rio de Janeiro\",\"state\":\"Rio de Janeiro\"}}"
                + "]";

        List<DestinationStore.Destination> results = DestinationResolver.rankJsonForTest("Rio de Janeiro", raw);
        assertFalse(results.isEmpty());
        assertTrue(Math.abs(results.get(0).lat - (-22.9110)) < 0.0001);
    }

    @Test public void explicitUfRejectsSameNameFromWrongState() throws Exception {
        String raw = "["
                + "{\"lat\":\"-20.0000\",\"lon\":\"-45.0000\",\"name\":\"Bom Jesus do Itabapoana\",\"display_name\":\"Bom Jesus do Itabapoana, Minas Gerais, Brasil\",\"type\":\"administrative\",\"addresstype\":\"municipality\",\"importance\":0.80,\"address\":{\"municipality\":\"Bom Jesus do Itabapoana\",\"state\":\"Minas Gerais\",\"ISO3166-2-lvl4\":\"BR-MG\"}},"
                + "{\"lat\":\"-21.1337\",\"lon\":\"-41.6820\",\"name\":\"Bom Jesus do Itabapoana\",\"display_name\":\"Bom Jesus do Itabapoana, Rio de Janeiro, Brasil\",\"type\":\"administrative\",\"addresstype\":\"municipality\",\"importance\":0.50,\"address\":{\"municipality\":\"Bom Jesus do Itabapoana\",\"state\":\"Rio de Janeiro\",\"ISO3166-2-lvl4\":\"BR-RJ\"}}"
                + "]";

        List<DestinationStore.Destination> results = DestinationResolver.rankJsonForTest("Bom Jesus do Itabapoana - RJ", raw);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).label.contains("Rio de Janeiro"));
    }
}

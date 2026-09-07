package com.estradaplay.comunista;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RoadPackStoreOsmClassifierTest {
    @Test public void redlightOutranksMaxspeedWhenBothArePresent() throws Exception {
        JSONObject tags = new JSONObject();
        tags.put("enforcement", "maxspeed;redlight");
        assertEquals("SEMAFORO_RADAR", RoadPackStore.directType(tags));
    }

    @Test public void trafficSignalEnforcementOutranksGenericSpeedCamera() throws Exception {
        JSONObject tags = new JSONObject();
        tags.put("highway", "speed_camera");
        tags.put("enforcement", "traffic_signals");
        assertEquals("SEMAFORO_RADAR", RoadPackStore.directType(tags));
    }

    @Test public void ordinarySpeedCameraRemainsRadar() throws Exception {
        JSONObject tags = new JSONObject();
        tags.put("highway", "speed_camera");
        assertEquals("RADAR", RoadPackStore.directType(tags));
    }

    @Test public void bumpAndTrafficCameraRemainDistinct() throws Exception {
        JSONObject bump = new JSONObject();
        bump.put("traffic_calming", "hump");
        assertEquals("QUEBRA_MOLAS", RoadPackStore.directType(bump));

        JSONObject camera = new JSONObject();
        camera.put("man_made", "surveillance");
        camera.put("surveillance", "traffic");
        assertEquals("CAMERA_MONITORAMENTO", RoadPackStore.directType(camera));
    }
}

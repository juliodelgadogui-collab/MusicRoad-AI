package com.estradaplay.comunista;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-side smoke tests for the two views that caused the most field regressions. */
@RunWith(AndroidJUnit4.class)
public class RoadCockpitInstrumentationTest {
    @Test public void speedometerMeasuresAndAcceptsGpsState() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ReferenceSpeedometerView[] ref = new ReferenceSpeedometerView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ReferenceSpeedometerView v = new ReferenceSpeedometerView(context);
            v.setSpeed(87.0);
            v.setGpsAvailable(false);
            int spec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY);
            v.measure(spec, spec);
            v.layout(0, 0, 480, 480);
            ref[0] = v;
        });
        assertNotNull(ref[0]);
        assertTrue(ref[0].getMeasuredWidth() > 0);
    }

    @Test public void mapViewCanEnterAndLeaveLifecycleWithoutCrashing() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final RoadMapView[] ref = new RoadMapView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RoadMapView map = new RoadMapView(context);
            map.onStartMap();
            map.onResumeMap();
            map.onPauseMap();
            map.onStopMap();
            map.onDestroyMap();
            ref[0] = map;
        });
        assertNotNull(ref[0]);
    }
}

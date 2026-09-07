package com.estradaplay.comunista;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/** Regression gates for the V4.1 foreground-only road-alert policy. */
@RunWith(AndroidJUnit4.class)
public class ForegroundOnlyServicesInstrumentationTest {

    @Test public void appDoesNotRequestBootRestartPermission() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        String[] requested = info.requestedPermissions == null ? new String[0] : info.requestedPermissions;
        assertFalse(Arrays.asList(requested).contains(Manifest.permission.RECEIVE_BOOT_COMPLETED));
    }

    @Test public void roadMicrophoneServicesStopWithTask() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_SERVICES);
        assertNotNull(info.services);
        assertStopWithTask(info.services, RoadSafetyService.class.getName());
        assertStopWithTask(info.services, CopilotService.class.getName());
        assertStopWithTask(info.services, RoadRadioService.class.getName());
    }

    @Test public void bootReceiverIsNotRegistered() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_RECEIVERS);
        if (info.receivers == null) return;
        for (android.content.pm.ActivityInfo receiver : info.receivers) {
            assertFalse("RoadSafetyBootReceiver must not be registered",
                    receiver != null && receiver.name != null && receiver.name.endsWith("RoadSafetyBootReceiver"));
        }
    }

    private static void assertStopWithTask(ServiceInfo[] services, String serviceName) {
        for (ServiceInfo service : services) {
            if (service != null && serviceName.equals(service.name)) {
                assertTrue(serviceName + " must stop with app task",
                        (service.flags & ServiceInfo.FLAG_STOP_WITH_TASK) != 0);
                return;
            }
        }
        throw new AssertionError("Service not found: " + serviceName);
    }
}

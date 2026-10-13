package com.diogobatista.scrcpymanager.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhysicalDisplayStateTest {
    private static String panel(long id, String mode) {
        return "Display " + id + "\n    connectionType=Internal\n    powerMode=" + mode + "\n";
    }

    @Test public void pixelDumpIgnoresRunningScrcpyVirtualDisplay() {
        String dump = panel(4619826886480892416L, "Off")
                + "Virtual Display 11529215049742450233\n    name=\"scrcpy\"\n    powerMode=On\n"
                + "Virtual Display Helper Threads (Pooled by UID)\n    Status=None active\n";
        assertEquals(Boolean.TRUE, PhysicalDisplayState.allInternalDisplaysOff(dump));
    }

    @Test public void normalPanelPowerDoesNotSuppress() {
        assertEquals(Boolean.FALSE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "On")));
    }

    @Test public void foldedInactivePanelDoesNotSuppressActivePanel() {
        assertEquals(Boolean.FALSE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "Off") + panel(2, "On")));
        assertEquals(Boolean.TRUE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "Off") + panel(2, "Off")));
    }

    @Test public void dozeIsNotFauxOff() {
        assertEquals(Boolean.FALSE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "Doze")));
        assertEquals(Boolean.FALSE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "DozeSuspend")));
    }

    @Test public void numericPowerModesAreSupported() {
        assertEquals(Boolean.TRUE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "0")));
        assertEquals(Boolean.FALSE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "2")));
        assertNull(PhysicalDisplayState.allInternalDisplaysOff(panel(1, "99")));
    }

    @Test public void externalDisplaysDoNotOverrideInternalPanelState() {
        String external = "Display 2\n    connectionType=External\n    powerMode=On\n";
        assertEquals(Boolean.TRUE, PhysicalDisplayState.allInternalDisplaysOff(panel(1, "Off") + external));
        assertNull(PhysicalDisplayState.allInternalDisplaysOff(external));
    }

    @Test public void missingOrUnrecognizedStateIsUnknown() {
        assertNull(PhysicalDisplayState.allInternalDisplaysOff("Permission denied"));
        assertNull(PhysicalDisplayState.allInternalDisplaysOff("Virtual Display 2\n    powerMode=Off\n"));
        assertNull(PhysicalDisplayState.allInternalDisplaysOff(panel(1, "unknown")));
        assertNull(PhysicalDisplayState.allInternalDisplaysOff(panel(1, "Off") + panel(2, "unknown")));
        assertNull(PhysicalDisplayState.allInternalDisplaysOff("Display 1\n    powerMode=Off\n"));
    }
}

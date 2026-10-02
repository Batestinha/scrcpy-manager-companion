package com.diogobatista.scrcpymanager.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public class CableStateTest {
    private static final String HEADER = "EthernetNetworkFactory\n  Tracking interfaces:\n";

    @Test public void ethernetUsesSelectedInterface() {
        String dump = HEADER + "    eth0:NetworkInterfaceState{ port: EthernetPort(eth0), up: false, networkAgent: null}\n"
                + "    eth1:NetworkInterfaceState{ up: true, networkAgent: active}\n";
        assertEquals(Boolean.FALSE, CableState.ethernet(dump, "eth0"));
        assertEquals(Boolean.TRUE, CableState.ethernet(dump, "eth1"));
    }

    @Test public void removedEthernetAdapterIsDisconnected() {
        assertEquals(Boolean.FALSE, CableState.ethernet(HEADER + "Handler:\n", "eth0"));
    }

    @Test public void failedOrPartialQueryIsUnknown() {
        assertNull(CableState.ethernet("Permission denied", "eth0"));
        assertNull(CableState.ethernet("Tracking interfaces:", "eth0"));
        assertNull(CableState.ethernet(HEADER + "eth0:unrecognized format", "eth0"));
        assertNull(CableState.usb("Permission denied"));
    }

    @Test public void usbUsesGadgetConnectionNotHostAdapterOrHistory() {
        String dump = "UsbDeviceManager:\n  USB Device State:\n    connected=false\n    host_connected=true\n"
                + "UsbHostManager:\n  connected=true\n";
        assertEquals(Boolean.FALSE, CableState.usb(dump));
        assertEquals(Boolean.TRUE, CableState.usb("UsbDeviceManager:\n    connected=true\n    configured=true\n"));
        assertEquals(Boolean.FALSE, CableState.usb("USB MANAGER STATE:\n{\n  device_manager={\n    handler={\n      connected=false\n      host_connected=true\n    }\n    USB Event Log=UsbDeviceManager activity\n      connected=true\n"));
    }
}

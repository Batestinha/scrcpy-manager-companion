package com.diogobatista.scrcpymanager.companion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only affirmative local cable state can end a session. Query failures are unknown. */
final class CableState {
    static Boolean ethernet(String dump, String name) {
        int tracking = dump.indexOf("Tracking interfaces:");
        if (tracking < 0) return null;
        Matcher state = Pattern.compile("(?m)^\\s*" + Pattern.quote(name)
                + ":.*?\\bup:\\s*(true|false)\\b").matcher(dump.substring(tracking));
        if (state.find()) return Boolean.valueOf(state.group(1));
        if (Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + ":").matcher(dump.substring(tracking)).find()) return null;
        // A complete tracking section without this interface means the adapter was removed.
        return dump.contains("EthernetNetworkFactory") ? false : null;
    }

    static Boolean usb(String dump) {
        int device = dump.indexOf("device_manager={");
        if (device < 0) device = dump.indexOf("UsbDeviceManager:");
        if (device < 0) return null;
        String section = dump.substring(device);
        int host = section.indexOf("UsbHostManager");
        if (host >= 0) section = section.substring(0, host);
        int history = section.indexOf("USB Event Log");
        if (history >= 0) section = section.substring(0, history);
        Matcher connected = Pattern.compile("(?m)^\\s*connected=(true|false)\\s*$").matcher(section);
        return connected.find() ? Boolean.valueOf(connected.group(1)) : null;
    }
}

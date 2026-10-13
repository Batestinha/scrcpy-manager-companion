package com.diogobatista.scrcpymanager.companion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SurfaceFlinger reports the physical power mode even when DisplayManager still says ON. */
final class PhysicalDisplayState {
    private static final Pattern HEADER = Pattern.compile("(?m)^(?:Virtual )?Display [0-9]+[^\\r\\n]*$");
    private static final Pattern INTERNAL = Pattern.compile("\\bconnectionType=Internal\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODE = Pattern.compile("\\bpowerMode=(Off|On|Normal|DozeSuspend|Doze|[0-9]+)\\b", Pattern.CASE_INSENSITIVE);

    static Boolean allInternalDisplaysOff(String dump) {
        Matcher headers = HEADER.matcher(dump);
        boolean found = false;
        Boolean result = Boolean.TRUE;
        int start = -1;
        boolean physical = false;
        while (headers.find()) {
            if (physical) {
                Boolean state = internalOff(dump.substring(start, headers.start()));
                if (INTERNAL.matcher(dump.substring(start, headers.start())).find()) {
                    found = true;
                    if (state == null) result = null;
                    else if (!state) return false;
                }
            }
            start = headers.end();
            physical = !headers.group().startsWith("Virtual ");
        }
        if (physical) {
            String section = dump.substring(start);
            if (INTERNAL.matcher(section).find()) {
                found = true;
                Boolean state = internalOff(section);
                if (state == null) result = null;
                else if (!state) return false;
            }
        }
        return found ? result : null;
    }

    private static Boolean internalOff(String section) {
        Matcher mode = MODE.matcher(section);
        if (!mode.find()) return null;
        String value = mode.group(1);
        if ("off".equalsIgnoreCase(value) || "0".equals(value)) return true;
        if ("on".equalsIgnoreCase(value) || "normal".equalsIgnoreCase(value)
                || "doze".equalsIgnoreCase(value) || "dozesuspend".equalsIgnoreCase(value)
                || "1".equals(value) || "2".equals(value) || "3".equals(value)) return false;
        return null;
    }
}

package com.alexbrazier.icloudframe;

import java.util.Calendar;

/**
 * Frames have no clock battery, so they start in 1970 until they get the time from the network.
 * HTTPS certificates and the screen schedule both need the real time, so they wait for it.
 */
final class Clock {
    private Clock() {}

    static boolean isSet() {
        return Calendar.getInstance().get(Calendar.YEAR) >= 2025;
    }
}

package com.moveinsync.evfleet.core.util;

/** Minutes since the shift day's midnight; values past 1440 are the next calendar day. */
public final class Clock {

    private Clock() {
    }

    public static int hm(int hour, int minute) {
        return hour * 60 + minute;
    }

    /** Reads "19:30", "25:10" or "01:10+1" (the last two are 01:10 the next day). */
    public static int parse(String text) {
        boolean nextDay = text.endsWith("+1");
        String[] hm = text.replace("+1", "").split(":");
        int minute = hm(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        return nextDay ? minute + 24 * 60 : minute;
    }

    public static String format(int minute) {
        int m = Math.floorMod(minute, 24 * 60);
        String s = String.format("%02d:%02d", m / 60, m % 60);
        return minute >= 24 * 60 ? s + "+1" : s;
    }
}

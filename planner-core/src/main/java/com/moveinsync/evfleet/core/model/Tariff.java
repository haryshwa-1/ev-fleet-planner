package com.moveinsync.evfleet.core.model;

import java.util.List;

/**
 * Time-of-day electricity tariff in rupees per kWh. Windows are given in minute-of-day
 * and may wrap past midnight (e.g. off-peak 22:00 to 06:00). Minutes not covered by any
 * window are charged at {@code defaultRate}.
 */
public record Tariff(List<Window> windows, double defaultRate) {

    public record Window(String label, int startMinuteOfDay, int endMinuteOfDay, double rate) {

        boolean contains(int minuteOfDay) {
            if (startMinuteOfDay <= endMinuteOfDay) {
                return minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay;
            }
            return minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay;
        }
    }

    public Tariff {
        windows = List.copyOf(windows);
    }

    public double rateAt(int minute) {
        int minuteOfDay = Math.floorMod(minute, 24 * 60);
        for (Window w : windows) {
            if (w.contains(minuteOfDay)) return w.rate();
        }
        return defaultRate;
    }

    public double cheapestRate() {
        return windows.stream().mapToDouble(Window::rate).reduce(defaultRate, Math::min);
    }
}

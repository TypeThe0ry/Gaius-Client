package dev.gaius.classlib;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * java.util.Date.toString (HotSpot: {@code EEE MMM dd HH:mm:ss zzz yyyy}), the deprecated
 * field getters, GregorianCalendar and SimpleDateFormat (vanilla names screenshots
 * {@code yyyy-MM-dd_HH.mm.ss}). The runner fixes the time zone to UTC everywhere.
 */
final class Dates {
    private Dates() {
    }

    @SuppressWarnings("deprecation")
    static void run() {
        long[] instants = {0L, 1L, -1L, 1000000000000L, 1759536000123L, -100000000000L, 951782400000L,
            1709164800000L, 2147483647000L, 4102444800000L, -62135596800000L, 253402300799999L};
        for (long millis : instants) {
            Date date = new Date(millis);
            String key = Long.toString(millis);
            Out.check("toString." + key, date::toString);
            Out.check("fields." + key, () -> date.getYear() + " " + date.getMonth() + " " + date.getDate() + " "
                    + date.getDay() + " " + date.getHours() + " " + date.getMinutes() + " " + date.getSeconds()
                    + " " + date.getTimezoneOffset());
            Out.check("calendar." + key, () -> {
                Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
                c.setTimeInMillis(millis);
                return c.get(Calendar.YEAR) + "-" + c.get(Calendar.MONTH) + "-" + c.get(Calendar.DAY_OF_MONTH) + " "
                        + c.get(Calendar.HOUR_OF_DAY) + ":" + c.get(Calendar.MINUTE) + ":" + c.get(Calendar.SECOND)
                        + "." + c.get(Calendar.MILLISECOND) + " dow=" + c.get(Calendar.DAY_OF_WEEK) + " doy="
                        + c.get(Calendar.DAY_OF_YEAR) + " era=" + c.get(Calendar.ERA);
            });
            Out.check("screenshotName." + key, () -> {
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format.format(date);
            });
            Out.check("simpleDateFormat." + key, () -> {
                SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss.SSS Z", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format.format(date);
            });
        }
        Out.check("parse", () -> {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return format.parse("2026-10-04 12:34:56").getTime();
            } catch (java.text.ParseException e) {
                return "throws ParseException at " + e.getErrorOffset();
            }
        });
        Out.check("calendar.set", () -> {
            Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            c.clear();
            c.set(2024, Calendar.FEBRUARY, 29, 23, 59, 59);
            c.add(Calendar.YEAR, 1);
            return c.getTimeInMillis();
        });
        Out.check("timezone.utc", () -> TimeZone.getTimeZone("UTC").getID() + " " + TimeZone.getTimeZone("UTC").getRawOffset());
        Out.check("date.compare", () -> new Date(5).compareTo(new Date(3)) + " " + new Date(5).equals(new Date(5)) + " "
                + new Date(5).hashCode());
    }
}

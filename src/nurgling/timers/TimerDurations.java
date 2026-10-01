package nurgling.timers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and writing timer lengths.
 *
 * <p>Accepts what people actually type: {@code 3h20m}, {@code 3h 20m}, {@code 3:20}, {@code 200m},
 * {@code 3.5h}, {@code 1d}. A bare number is minutes. The popover previews the resulting ready time, so a
 * misread is visible before the timer starts.
 */
public final class TimerDurations {
    private static final long SEC = 1000L;
    private static final long MIN = 60 * SEC;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,3}):(\\d{1,2})");
    private static final Pattern PART = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*([dhms])");
    private static final Pattern UNITS = Pattern.compile("(?:\\d+(?:[.,]\\d+)?\\s*[dhms]\\s*)+");
    private static final Pattern BARE = Pattern.compile("\\d+");
    /** A duration at the start of a line, followed by whitespace or the end. */
    private static final Pattern LEADING = Pattern.compile(
        "^\\s*((?:\\d+(?:[.,]\\d+)?\\s*[dhms]\\s*)+|\\d{1,3}:\\d{1,2}|\\d+)(?=\\s|$)\\s*(.*)$",
        Pattern.DOTALL);

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter DAY_HM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US);

    private TimerDurations() {
    }

    /** Milliseconds, or -1 when the text is not a duration. */
    public static long parse(String text) {
        if(text == null)
            return -1;
        String s = text.trim().toLowerCase(Locale.ROOT);
        if(s.isEmpty())
            return -1;
        Matcher m = CLOCK.matcher(s);
        if(m.matches()) {
            long h = Long.parseLong(m.group(1));
            long mm = Long.parseLong(m.group(2));
            if(mm >= 60)
                return -1;
            return positive(h * HOUR + mm * MIN);
        }
        if(BARE.matcher(s).matches())
            return positive(Long.parseLong(s) * MIN);
        if(!UNITS.matcher(s).matches())
            return -1;
        double total = 0;
        Matcher p = PART.matcher(s);
        while(p.find()) {
            double v = Double.parseDouble(p.group(1).replace(',', '.'));
            switch(p.group(2)) {
                case "d": total += v * DAY; break;
                case "h": total += v * HOUR; break;
                case "m": total += v * MIN; break;
                default: total += v * SEC; break;
            }
        }
        return positive(Math.round(total));
    }

    private static long positive(long ms) {
        return (ms > 0) ? ms : -1;
    }

    /** A duration at the start of the line plus the rest as a note. */
    public static final class Leading {
        public final long ms;
        public final String rest;

        Leading(long ms, String rest) {
            this.ms = ms;
            this.rest = rest;
        }
    }

    /** Splits {@code "1h30m check the smelters"}; null when the line does not start with a duration. */
    public static Leading parseLeading(String line) {
        if(line == null)
            return null;
        Matcher m = LEADING.matcher(line);
        if(!m.matches())
            return null;
        long ms = parse(m.group(1));
        if(ms < 0)
            return null;
        return new Leading(ms, m.group(2).trim());
    }

    /** "2h 14m", "9h 02m", "38m", "1d 3h", "<1m". */
    public static String format(long ms) {
        if(ms < MIN)
            return "<1m";
        long d = ms / DAY;
        long h = (ms % DAY) / HOUR;
        long m = (ms % HOUR) / MIN;
        if(d > 0)
            return (h > 0) ? String.format("%dd %dh", d, h) : String.format("%dd", d);
        if(h > 0)
            return String.format("%dh %02dm", h, m);
        return String.format("%dm", m);
    }

    /** Short form for chips: "6h", "1h30m", "15m", "1d". */
    public static String formatShort(long ms) {
        long d = ms / DAY;
        long h = (ms % DAY) / HOUR;
        long m = (ms % HOUR) / MIN;
        StringBuilder sb = new StringBuilder();
        if(d > 0)
            sb.append(d).append('d');
        if(h > 0)
            sb.append(h).append('h');
        if(m > 0 || sb.length() == 0)
            sb.append(m).append('m');
        return sb.toString();
    }

    /** "18:40", "tomorrow 01:28" or "Mon 14:00" - when the timer is ready, in local time. */
    public static String formatClock(long epochMs, String tomorrowWord) {
        LocalDateTime at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        LocalDate today = LocalDate.now();
        if(at.toLocalDate().equals(today))
            return at.format(HM);
        if(at.toLocalDate().equals(today.plusDays(1)))
            return tomorrowWord + " " + at.format(HM);
        return at.format(DAY_HM);
    }
}

import org.quartz.CronExpression;
import tech.qiantong.qdata.quartz.util.CronUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

/** Offline parser check. Compile with the existing Quartz dependency and current CronUtils source. */
public class QuartzYearRoundtrip {
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date anchor = Date.from(Instant.parse("2026-10-16T12:00:00Z"));
        int checked = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] fields = line.split("\\t", -1);
            String input = fields[0], output = fields[1];
            if (!CronUtils.isValid(input) || !CronUtils.isValid(output)) throw new AssertionError(line);
            if (!input.equals(output)) throw new AssertionError("Expression changed: " + line);
            if (!Objects.equals(CronUtils.getNextExecution(input), CronUtils.getNextExecution(output))) {
                throw new AssertionError("Backend next execution changed: " + line);
            }
            CronExpression before = new CronExpression(input), after = new CronExpression(output);
            Date previous = anchor;
            String[] preview = fields[2].split(";");
            for (int i = 0; i < preview.length; i++) {
                Date expected = before.getNextValidTimeAfter(previous);
                Date actual = after.getNextValidTimeAfter(previous);
                if (!Objects.equals(expected, actual) || actual == null || !format.format(actual).equals(preview[i])) {
                    throw new AssertionError("Preview mismatch for " + output + ": " + preview[i] + " vs " + actual);
                }
                previous = actual;
            }
            checked++;
            System.out.println("PASS " + output + " | next=" + format.format(after.getNextValidTimeAfter(anchor))
                    + " | preview=" + preview.length);
        }
        if (checked != 9) throw new AssertionError("Expected nine roundtrips, got " + checked);
        System.out.println("Quartz/CronUtils roundtrips passed: " + checked);
    }
}

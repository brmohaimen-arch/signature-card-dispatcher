package ly.alwaha.card;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Changes key=value lines in application.properties in place, keeping comments, after a timestamped backup. */
public final class PropertiesFile {

    private static final Pattern KEY = Pattern.compile("[a-z0-9.]+");

    private PropertiesFile() {}

    public static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    /** Returns the backup file. */
    public static Path update(Path file, Map<String, String> updates) throws IOException {
        Path backup = backup(file);
        Files.write(file, apply(Files.readAllLines(file, StandardCharsets.UTF_8), updates), StandardCharsets.UTF_8);
        return backup;
    }

    /** The new lines, without writing anything (used to validate first). */
    static List<String> apply(List<String> lines, Map<String, String> updates) {
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (!KEY.matcher(e.getKey()).matches()) throw new IllegalArgumentException("bad setting name " + e.getKey());
            if (e.getValue().indexOf('\n') >= 0 || e.getValue().indexOf('\r') >= 0) {
                throw new IllegalArgumentException(e.getKey() + ": line breaks are not allowed");
            }
        }
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String line : lines) {
            String t = line.stripLeading();
            int eq = t.indexOf('=');
            String key = (t.startsWith("#") || eq < 0) ? null : t.substring(0, eq).strip();
            if (key != null && updates.containsKey(key)) {
                out.add(key + "=" + escape(updates.get(key)));
                done.add(key);
            } else {
                out.add(line);
            }
        }
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (!done.contains(e.getKey())) out.add(e.getKey() + "=" + escape(e.getValue()));
        }
        return out;
    }

    private static String escape(String v) {
        String s = v.replace("\\", "\\\\");
        return s.startsWith(" ") ? "\\" + s : s;
    }

    private static Path backup(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent().resolve("backups");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path b = dir.resolve("application-" + stamp + ".properties");
        Files.copy(file, b, StandardCopyOption.REPLACE_EXISTING);
        return b;
    }
}

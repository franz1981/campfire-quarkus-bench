///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/**
 * Reads a run directory written by bench.sh and prints its results: medians over reps per app and
 * connection count (closed loop) or offered rate (open), the errors seen on either side, and a bar
 * chart of req/s.
 *
 *   jbang bench/Report.java RUN_DIR [--markdown]    the tables (Markdown with --markdown)
 *   jbang bench/Report.java RUN_DIR --capacity       the best app's median req/s at 64 connections
 *   jbang bench/Report.java RUN_DIR --charts         SVG charts of the all-out medians into RUN_DIR/charts/
 */
public class Report {

    /** One measured step: what the driver reported plus what bench.sh sampled around it. */
    record Step(String kind, String app, int rep, int load, Map<String, Double> v) {
        double get(String k) { return v.getOrDefault(k, Double.NaN); }
        boolean failed() { return get("failed") == 1; }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { System.err.println("usage: Report.java RUN_DIR [--markdown|--capacity]"); System.exit(2); }
        Path dir = Path.of(args[0]);
        Set<String> flags = Set.of(Arrays.copyOfRange(args, 1, args.length));
        List<Step> all = new ArrayList<>(load(dir.resolve("closed"), "closed", "-c"));
        all.addAll(load(dir.resolve("open"), "open", "-r"));
        List<Step> closed = all.stream().filter(s -> s.kind().equals("closed") && !s.failed()).toList();
        List<Step> open = all.stream().filter(s -> s.kind().equals("open") && !s.failed()).toList();
        if (flags.contains("--capacity")) { System.out.println(capacity(closed)); return; }
        if (flags.contains("--charts")) { charts(closed, dir.resolve("charts")); return; }
        boolean md = flags.contains("--markdown");
        if (Files.exists(dir.resolve("config.txt")) && !md) System.out.println(Files.readString(dir.resolve("config.txt")));
        if (!closed.isEmpty()) closedTables(closed, md);
        if (!open.isEmpty()) openTables(open, md);
        problems(all);
    }

    // ---- loading -----------------------------------------------------------------------------

    /** Every APP-REP-<c|r>LOAD.properties in dir, merged with the driver's output next to it. */
    static List<Step> load(Path dir, String kind, String marker) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<Step> steps = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".properties")).sorted().toList()) {
                String name = p.getFileName().toString().replace(".properties", "");
                int m = name.lastIndexOf(marker), r = name.lastIndexOf('-', m - 1);
                Map<String, Double> v = new TreeMap<>();
                Properties props = new Properties();
                try (Reader in = Files.newBufferedReader(p)) { props.load(in); }
                props.forEach((k, val) -> v.put((String) k, Double.parseDouble((String) val)));
                Path csv = dir.resolve(name + ".csv");
                if (Files.exists(csv)) hyperfoil(Files.readAllLines(csv), v);
                steps.add(new Step(kind, name.substring(0, r), Integer.parseInt(name.substring(r + 1, m)),
                        Integer.parseInt(name.substring(m + marker.length())), v));
            }
        }
        return steps;
    }

    /** Hyperfoil's stats/total.csv: the measured ("main") phase; latencies there are in ns. */
    static void hyperfoil(List<String> lines, Map<String, Double> v) {
        String[] head = lines.get(0).split(",");
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",");
            if (!cols[0].equals("main")) continue;
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < head.length; i++) row.put(head[i], cols[i]);
            double secs = (Long.parseLong(row.get("End")) - Long.parseLong(row.get("Start"))) / 1000.0;
            double ok = Double.parseDouble(row.get("http.2xx"));
            v.put("ok", ok);
            v.put("served", ok / secs);
            v.put("rps", ok / secs);
            v.put("p50_ms", ns(row, "p50.0"));
            v.put("p99_ms", ns(row, "p99.0"));
            v.put("p999_ms", ns(row, "p99.9"));
            v.put("max_ms", ns(row, "Max"));
            v.put("errors", sum(row, "ConnectionErrors", "RequestTimeouts", "InternalErrors", "http.4xx", "http.5xx", "http.OtherStatus"));
            v.put("invalid", Double.parseDouble(row.get("Invalid")));
            v.put("blocked_ms", ns(row, "BlockedTime"));
            // Hyperfoil's own measure (SLA blockedRatio): time waiting for a connection over all response time.
            double waited = Long.parseLong(row.get("BlockedTime")), served = Long.parseLong(row.get("Mean")) * Double.parseDouble(row.get("Responses"));
            v.put("blocked_ratio", waited / (waited + served));
            v.put("max_sessions", Double.parseDouble(row.get("MaxSessions")));
        }
    }

    static double ns(Map<String, String> row, String k) { return Long.parseLong(row.get(k)) / 1e6; }

    static double sum(Map<String, String> row, String... keys) {
        return Arrays.stream(keys).mapToDouble(k -> Double.parseDouble(row.get(k))).sum();
    }

    // ---- tables ------------------------------------------------------------------------------

    static void closedTables(List<Step> steps, boolean md) {
        List<String> apps = apps(steps);
        System.out.println(md ? "### Closed loop (medians)\n" : "Closed loop: medians over reps");
        List<String> head = new ArrayList<>(List.of("conns"));
        apps.forEach(a -> head.add(a + " req/s"));
        if (apps.contains("quarkus") && apps.contains("kotlin")) head.add("quarkus / kotlin");
        head.addAll(List.of("µs CPU/req " + String.join(" / ", apps), "p99 ms " + String.join(" / ", apps),
                "RSS MB " + String.join(" / ", apps), "peak RSS MB " + String.join(" / ", apps)));
        List<List<String>> rows = new ArrayList<>();
        for (int conns : loads(steps)) {
            List<String> row = new ArrayList<>(List.of(String.valueOf(conns)));
            for (String a : apps) row.add(fmt(median(steps, a, conns, s -> s.get("rps")), "%,.0f"));
            if (apps.contains("quarkus") && apps.contains("kotlin"))
                row.add(fmt(100 * median(steps, "quarkus", conns, s -> s.get("rps")) / median(steps, "kotlin", conns, s -> s.get("rps")), "%.0f%%"));
            row.add(join(apps, a -> median(steps, a, conns, s -> s.get("cpu_secs") * 1e6 / s.get("responses")), "%.1f"));
            row.add(join(apps, a -> median(steps, a, conns, s -> s.get("p99_ms")), "%.1f"));
            row.add(join(apps, a -> median(steps, a, conns, s -> s.get("rss_mb")), "%.0f"));
            row.add(join(apps, a -> median(steps, a, conns, s -> s.get("rss_peak_mb")), "%.0f"));
            rows.add(row);
        }
        table(head, rows, md);
        if (!md) bars(steps, apps, s -> s.get("rps"), "req/s");
    }

    static void openTables(List<Step> steps, boolean md) {
        List<String> apps = apps(steps);
        System.out.println(md ? "\n### Fixed offered rates (medians)\n" : "\nFixed offered rates: medians over reps");
        List<String> head = new ArrayList<>(List.of("offered req/s"));
        for (String k : List.of("served req/s", "µs CPU/req", "p50 ms", "p99 ms", "p99.9 ms", "RSS MB"))
            head.add(k + " " + String.join(" / ", apps));
        List<List<String>> rows = new ArrayList<>();
        for (int rate : loads(steps)) {
            rows.add(List.of(String.format(Locale.ROOT, "%,d", rate),
                    join(apps, a -> median(steps, a, rate, s -> s.get("served")), "%,.0f"),
                    join(apps, a -> median(steps, a, rate, s -> s.get("cpu_secs") * 1e6 / s.get("responses")), "%.1f"),
                    join(apps, a -> median(steps, a, rate, s -> s.get("p50_ms")), "%.2f"),
                    join(apps, a -> median(steps, a, rate, s -> s.get("p99_ms")), "%.1f"),
                    join(apps, a -> median(steps, a, rate, s -> s.get("p999_ms")), "%.1f"),
                    join(apps, a -> median(steps, a, rate, s -> s.get("rss_mb")), "%.0f")));
        }
        table(head, rows, md);
    }

    /** Errors on either side, connections that never opened, a saturated load driver. */
    static void problems(List<Step> steps) {
        List<String> out = new ArrayList<>();
        for (Step s : steps) {
            String at = s.app() + " rep " + s.rep() + " " + s.kind() + " " + s.load();
            if (s.failed()) { out.add(at + ": failed (no measurement; see the run's logs)"); continue; }
            if (s.get("invalid") > 0) out.add(at + ": " + (long) s.get("invalid") + " invalid responses");
            if (s.get("errors") > 0) out.add(at + ": " + (long) s.get("errors") + " client errors");
            if (s.get("app_log_errors") > 0) out.add(at + ": " + (long) s.get("app_log_errors") + " error lines in the app log");
            if (s.kind().equals("closed") && s.get("opens") < s.load()) out.add(at + ": only " + (long) s.get("opens") + " connections opened");
            if (s.get("listen_overflows") > 0) out.add(at + ": " + (long) s.get("listen_overflows") + " listen-queue overflows");
            // Only the measured phase is read: waiting during the warmup (while connections open) doesn't count.
            if (s.get("blocked_ms") > 0) out.add(at + ": requests waited " + fmt(s.get("blocked_ms"), "%,.0f") + " ms in total for a free connection in the measured phase ("
                    + fmt(100 * s.get("blocked_ratio"), "%.2f") + "% of the response time)");
            if (s.get("max_sessions") > s.get("sessions")) out.add(at + ": " + (long) s.get("max_sessions") + " sessions in flight, above the " + (long) s.get("sessions") + " preallocated");
            if (s.get("loadgen_busy") > 0.8 * s.get("loadgen_cpus")) out.add(at + ": load driver saturated (" + fmt(s.get("loadgen_busy"), "%.1f") + " of " + (long) s.get("loadgen_cpus") + " CPUs busy)");
        }
        System.out.println(out.isEmpty() ? "\nNo errors, invalid responses or missing connections on either side."
                : "\nProblems:\n  " + String.join("\n  ", out));
    }

    /** The original harness' open-phase reference: the best app's median req/s at 64 connections. */
    static long capacity(List<Step> closed) {
        return (long) apps(closed).stream().mapToDouble(a -> median(closed, a, 64, s -> s.get("rps")))
                .filter(d -> !Double.isNaN(d)).max().orElse(0);
    }

    // ---- charts ------------------------------------------------------------------------------

    static final Map<String, String> COLOR = Map.of("kotlin", "#7F52FF", "quarkus", "#4695EB", "rust", "#CE7B3C");

    /** One grouped bar chart per metric: connection counts on the x axis, one bar per app. */
    static void charts(List<Step> steps, Path out) throws IOException {
        Files.createDirectories(out);
        chart(steps, out.resolve("throughput.svg"), "Throughput, all out (req/s, higher is better)", s -> s.get("rps"), "%,.0f");
        chart(steps, out.resolve("cpu-per-request.svg"), "CPU per request (µs, lower is better)", s -> s.get("cpu_secs") * 1e6 / s.get("responses"), "%.1f");
        chart(steps, out.resolve("memory.svg"), "Resident memory at the end of each step (RSS MB, lower is better)", s -> s.get("rss_mb"), "%.0f");
        System.out.println("charts: " + out);
    }

    static void chart(List<Step> steps, Path file, String title, ToDoubleFunction<Step> f, String format) throws IOException {
        List<String> apps = apps(steps);
        List<Integer> loads = loads(steps);
        double max = 0;
        for (String a : apps) for (int l : loads) { double m = median(steps, a, l, f); if (!Double.isNaN(m)) max = Math.max(max, m); }
        max = niceCeiling(max);
        int left = 70, right = 20, top = 60, bottom = 60, groupWidth = 30 + 34 * apps.size(), plotHeight = 300;
        int width = left + right + groupWidth * loads.size(), height = top + plotHeight + bottom;
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT, "<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d' font-family='sans-serif' font-size='12'>%n", width, height));
        svg.append(String.format(Locale.ROOT, "<rect width='%d' height='%d' fill='white'/>%n", width, height));
        svg.append(String.format(Locale.ROOT, "<text x='%d' y='22' font-size='15' font-weight='bold'>%s</text>%n", left, esc(title)));
        int lx = left;  // legend
        for (String a : apps) {
            svg.append(String.format(Locale.ROOT, "<rect x='%d' y='32' width='12' height='12' fill='%s'/><text x='%d' y='43'>%s</text>%n", lx, COLOR.getOrDefault(a, "#888"), lx + 16, a));
            lx += 30 + 8 * a.length();
        }
        for (int i = 0; i <= 4; i++) {  // grid and y labels
            double v = max * i / 4; int y = top + plotHeight - (int) Math.round(plotHeight * i / 4.0);
            svg.append(String.format(Locale.ROOT, "<line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#ddd'/>%n", left, y, width - right, y));
            svg.append(String.format(Locale.ROOT, "<text x='%d' y='%d' text-anchor='end' fill='#555'>%s</text>%n", left - 6, y + 4, String.format(Locale.ROOT, "%,.0f", v)));
        }
        for (int g = 0; g < loads.size(); g++) {
            int x0 = left + g * groupWidth + 15;
            for (int i = 0; i < apps.size(); i++) {
                double m = median(steps, apps.get(i), loads.get(g), f);
                if (Double.isNaN(m)) continue;
                int h = (int) Math.round(plotHeight * m / max), x = x0 + 34 * i, y = top + plotHeight - h;
                svg.append(String.format(Locale.ROOT, "<rect x='%d' y='%d' width='30' height='%d' fill='%s'><title>%s: %s</title></rect>%n",
                        x, y, h, COLOR.getOrDefault(apps.get(i), "#888"), apps.get(i), String.format(Locale.ROOT, format, m)));
                svg.append(String.format(Locale.ROOT, "<text x='%d' y='%d' text-anchor='middle' font-size='9' fill='#333'>%s</text>%n",
                        x + 15, y - 3, m >= 1000 ? String.format(Locale.ROOT, m >= 10_000 ? "%.0fk" : "%.1fk", m / 1000) : String.format(Locale.ROOT, format, m)));
            }
            svg.append(String.format(Locale.ROOT, "<text x='%d' y='%d' text-anchor='middle'>%,d</text>%n", x0 + 17 * apps.size() - 2, top + plotHeight + 18, loads.get(g)));
        }
        svg.append(String.format(Locale.ROOT, "<text x='%d' y='%d' text-anchor='middle' fill='#555'>connections</text>%n", left + (width - left - right) / 2, height - 12));
        svg.append("</svg>\n");
        Files.writeString(file, svg.toString());
    }

    static double niceCeiling(double v) {
        double step = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[] {1, 2, 2.5, 5, 10}) if (m * step >= v) return m * step;
        return 10 * step;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

    // ---- helpers -----------------------------------------------------------------------------

    static void bars(List<Step> steps, List<String> apps, ToDoubleFunction<Step> f, String unit) {
        double max = steps.stream().mapToDouble(f).filter(d -> !Double.isNaN(d)).max().orElse(1);
        int width = apps.stream().mapToInt(String::length).max().orElse(6);
        System.out.println();
        for (int load : loads(steps)) {
            for (String a : apps) {
                double m = median(steps, a, load, f);
                if (Double.isNaN(m)) continue;
                String label = a.equals(apps.get(0)) ? String.format(Locale.ROOT, "%6d", load) : "      ";
                System.out.printf(Locale.ROOT, "%s  %-" + width + "s %s %,.0f %s%n", label, a, "█".repeat((int) Math.round(50 * m / max)), m, unit);
            }
        }
    }

    static List<String> apps(List<Step> steps) {
        List<String> order = new ArrayList<>(List.of("kotlin", "quarkus", "rust"));
        steps.stream().map(Step::app).distinct().filter(a -> !order.contains(a)).forEach(order::add);
        return order.stream().filter(a -> steps.stream().anyMatch(s -> s.app().equals(a))).toList();
    }

    static List<Integer> loads(List<Step> steps) {
        return steps.stream().map(Step::load).distinct().sorted().toList();
    }

    static double median(List<Step> steps, String app, int load, ToDoubleFunction<Step> f) {
        double[] v = steps.stream().filter(s -> s.app().equals(app) && s.load() == load).mapToDouble(f).sorted().toArray();
        if (v.length == 0) return Double.NaN;
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    static String join(List<String> apps, ToDoubleFunction<String> f, String format) {
        return String.join(" / ", apps.stream().map(a -> fmt(f.applyAsDouble(a), format)).toList());
    }

    static String fmt(double v, String format) { return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, format, v); }

    static void table(List<String> head, List<List<String>> rows, boolean md) {
        int[] w = new int[head.size()];
        for (int i = 0; i < w.length; i++) {
            final int c = i;
            w[i] = Math.max(head.get(i).length(), rows.stream().mapToInt(r -> r.get(c).length()).max().orElse(0));
        }
        if (md) {
            System.out.println("| " + String.join(" | ", head) + " |");
            System.out.println("|" + "---:|".repeat(head.size()));
            rows.forEach(r -> System.out.println("| " + String.join(" | ", r) + " |"));
            return;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < w.length; i++) line.append(String.format(Locale.ROOT, "%" + w[i] + "s  ", head.get(i)));
        System.out.println(line.toString().stripTrailing());
        for (List<String> r : rows) {
            line.setLength(0);
            for (int i = 0; i < w.length; i++) line.append(String.format(Locale.ROOT, "%" + w[i] + "s  ", r.get(i)));
            System.out.println(line.toString().stripTrailing());
        }
    }
}

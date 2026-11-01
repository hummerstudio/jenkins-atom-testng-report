package com.jenkinschina.atom.testngreport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * testng-plugin as an atom: read the {@code testng-results.xml} files a build produced, count the methods, and apply
 * the plugin's threshold rules.
 *
 * <h2>What is ported, and what is not</h2>
 *
 * <p>The plugin's step is {@code testNG} (declared by {@code hudson.plugins.testng.Publisher}, a {@code SimpleBuildStep}
 * whose descriptor carries {@code @Symbol("testNG")}). Its job is: find the XML, walk every {@code test-method}, count
 * PASS/FAIL/SKIP, keep configuration methods apart from tests, then decide FAILURE/UNSTABLE by threshold. All of that
 * is here, with the same attributes, the same defaults (mode 2 = percentage, failed 100%, unstable-fails 0%, skips
 * 100%) and the same order of comparison (failed-fails, failed-skips, unstable-fails, unstable-skips, all strictly
 * "greater than").
 *
 * <p>What is not here is the build page: the plugin turns the parsed model into a {@code TestNGTestResultBuildAction}
 * with per-class/per-package drill-down and a trend graph, which is a UI host this atom has no way to attach to. The
 * atom writes one self-contained HTML file instead, so the numbers and the failure stacks stay readable.
 */
public class TestngReportAtom implements AtomTask {

    /** The plugin keeps the last 200 failures in its own report; a longer list is noise, not information. */
    private static final int MAX_CASES = 200;

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String pattern = context.getParam(
                "reportFilenamePattern", context.getParam("testResults", "**/testng-results.xml"));
        String label = context.getParam("label", "TestNG results");
        String outDir = context.getParam("outputDir", "out/testng");
        boolean failureOnFailedTestConfig = context.getBooleanParam("failureOnFailedTestConfig", false);
        boolean failIfNoReports = context.getBooleanParam("failIfNoReports", false);

        // the plugin's defaults, verbatim (Publisher fields): mode 2 is percentage, not count
        int thresholdMode = context.getIntParam("thresholdMode", 2);
        if (thresholdMode != 1 && thresholdMode != 2) {
            return AtomResult.error(ErrorType.USER, 40002,
                    "thresholdMode must be 1 (number of tests) or 2 (percentage of tests), got " + thresholdMode);
        }
        int failedFails = context.getIntParam("failedFails", 100);
        int failedSkips = context.getIntParam("failedSkips", 100);
        int unstableFails = context.getIntParam("unstableFails", 0);
        int unstableSkips = context.getIntParam("unstableSkips", 100);
        boolean allowUnreadable = "true".equalsIgnoreCase(context.getParam("allowUnreadableReports", "false"));

        Path workspace = context.getWorkspace();
        List<Path> files = Glob.find(workspace, patterns(pattern));
        if (files.isEmpty()) {
            // the plugin logs "Did not find any matching files." and lets the build continue; same here unless asked
            if (failIfNoReports) {
                return AtomResult.error(ErrorType.USER, 40001,
                        "no TestNG report matched " + pattern + " under " + workspace);
            }
            context.log("[testng] did not find any matching files for " + pattern + " — continuing, as the plugin does");
            return zero("no report matched " + pattern);
        }

        Summary summary = new Summary();
        for (Path file : files) {
            read(file, summary, context);
        }
        int total = summary.failed + summary.skipped + summary.passed;

        if (summary.unreadable > 0 && !allowUnreadable) {
            // the plugin logs a parse failure and keeps the build green; a gate that says "0 tests" while the
            // report it was given never parsed is worse than a red build
            return AtomResult.error(ErrorType.USER, 40003,
                    summary.unreadable + " of " + files.size() + " report(s) could not be parsed: "
                            + summary.firstParseError
                            + " (set allowUnreadableReports=true to accept this)");
        }

        if (total == 0 && summary.failedConfig == 0) {
            // "Found matching files but did not find any TestNG results." — again, no result change in the plugin
            context.log("[testng] " + files.size() + " file(s) matched but contained no test methods");
            return zero("matched " + files.size() + " file(s) but found no test methods");
        }

        String[] verdict = decide(summary, total, thresholdMode,
                failedFails, failedSkips, unstableFails, unstableSkips, failureOnFailedTestConfig);

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Path html = dir.resolve("index.html");
        Files.writeString(html, render(label, pattern, files.size(), summary, total, verdict), StandardCharsets.UTF_8);

        context.log(String.format(Locale.ROOT,
                "[testng] %d tests: %d passed, %d failed, %d skipped; config methods %d failed, %d skipped; "
                        + "%d file(s) matched %s",
                total, summary.passed, summary.failed, summary.skipped,
                summary.failedConfig, summary.skippedConfig, files.size(), pattern));
        for (Case item : summary.cases) {
            context.log("[testng] " + item.describe());
        }
        context.log("[testng] verdict: " + verdict[0].toLowerCase(Locale.ROOT) + " — " + verdict[1]);

        AtomResult result;
        if ("FAILURE".equals(verdict[0])) {
            result = AtomResult.failure(verdict[1]);
        } else {
            result = AtomResult.success(verdict[1]);
            if ("UNSTABLE".equals(verdict[0])) {
                result.markUnstable();
            }
        }
        return result
                .data("total", String.valueOf(total))
                .data("passed", String.valueOf(summary.passed))
                .data("failed", String.valueOf(summary.failed))
                .data("skipped", String.valueOf(summary.skipped))
                .data("failedConfigMethods", String.valueOf(summary.failedConfig))
                .data("skippedConfigMethods", String.valueOf(summary.skippedConfig))
                .data("durationSeconds", String.format(Locale.ROOT, "%.3f", summary.seconds()))
                .data("verdict", verdict[0])
                .report("report", label, dir.toString(), "index.html");
    }

    private static AtomResult zero(String message) {
        return AtomResult.success(message)
                .data("total", "0")
                .data("passed", "0")
                .data("failed", "0")
                .data("skipped", "0")
                .data("failedConfigMethods", "0")
                .data("skippedConfigMethods", "0")
                .data("durationSeconds", "0.000")
                .data("verdict", "NONE");
    }

    /**
     * The plugin accepts an Ant pattern or a list separated by {@code ; : ,} (its help text says so); the SDK's glob
     * takes commas, so the other two separators are folded into commas here.
     */
    private static String patterns(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "**/testng-results.xml";
        }
        List<String> parts = new ArrayList<>();
        for (String part : pattern.split("[,;]")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? "**/testng-results.xml" : String.join(",", parts);
    }

    // ------------------------------------------------------------------ parsing

    private static void read(Path file, Summary summary, AtomContext context) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // the report is data, not a document: no DTDs, no external entities
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(file.toFile());
            Element root = document.getDocumentElement();
            if (root == null) {
                summary.unreadable++;
                if (summary.firstParseError == null) {
                    summary.firstParseError = file.getFileName() + ": empty document";
                }
                context.log("[testng] " + file + " is empty");
                return;
            }

            // <testng-results> → <suite> → <test> → <class> → <test-method>
            NodeList classes = root.getElementsByTagName("class");
            if (classes.getLength() == 0) {
                // defensive: a hand-written report may put test-method straight under <test>
                NodeList methods = root.getElementsByTagName("test-method");
                for (int i = 0; i < methods.getLength(); i++) {
                    take((Element) methods.item(i), "", "", "", summary);
                }
                return;
            }
            for (int i = 0; i < classes.getLength(); i++) {
                Element clazz = (Element) classes.item(i);
                String className = clazz.getAttribute("name");
                String testName = ancestorName(clazz, "test");
                String suiteName = ancestorName(clazz, "suite");
                NodeList methods = clazz.getElementsByTagName("test-method");
                for (int m = 0; m < methods.getLength(); m++) {
                    take((Element) methods.item(m), suiteName, testName, className, summary);
                }
            }
        } catch (Exception e) {
            // the plugin logs the parse failure and keeps the build alive; the same here, but the file is counted
            summary.unreadable++;
            if (summary.firstParseError == null) {
                summary.firstParseError = file.getFileName() + ": " + e.getMessage();
            }
            context.log("[testng] cannot parse " + file + ": " + e.getMessage());
        }
    }

    /** One {@code <test-method>}: configuration methods are counted apart, exactly as {@code ClassResult.tally()} does. */
    private static void take(Element method, String suite, String test, String className, Summary summary) {
        String status = method.getAttribute("status");
        // the plugin's parser treats the mere presence of is-config as "this is a configuration method"
        boolean config = !method.getAttribute("is-config").isEmpty();
        int ms = toInt(method.getAttribute("duration-ms"));
        if (!config) {
            summary.millis += ms;
        }
        if ("FAIL".equals(status)) {
            if (config) {
                summary.failedConfig++;
            } else {
                summary.failed++;
            }
        } else if ("SKIP".equals(status)) {
            if (config) {
                summary.skippedConfig++;
            } else {
                summary.skipped++;
            }
        } else if (!config) {
            // anything that is not FAIL/SKIP counts as passed — the plugin's else branch, kept on purpose
            summary.passed++;
        }

        boolean interesting = "FAIL".equals(status)
                || ("SKIP".equals(status) && summary.cases.size() < MAX_CASES);
        if (interesting && summary.cases.size() < MAX_CASES) {
            summary.cases.add(new Case(
                    suite, test, className, method.getAttribute("name"), status, config,
                    firstLine(method.getAttribute("description")),
                    exceptionMessage(method), stackTrace(method)));
        }
    }

    private static String ancestorName(Element element, String tag) {
        Node node = element.getParentNode();
        while (node instanceof Element current) {
            if (tag.equals(current.getTagName())) {
                return current.getAttribute("name");
            }
            node = node.getParentNode();
        }
        return "";
    }

    private static Element exception(Element method) {
        NodeList exceptions = method.getElementsByTagName("exception");
        return exceptions.getLength() == 0 ? null : (Element) exceptions.item(0);
    }

    private static String exceptionMessage(Element method) {
        Element exception = exception(method);
        if (exception == null) {
            return "";
        }
        NodeList messages = exception.getElementsByTagName("message");
        String text = messages.getLength() == 0 ? "" : messages.item(0).getTextContent();
        if (text == null || text.isBlank()) {
            return exception.getAttribute("class");
        }
        return firstLine(text);
    }

    private static String stackTrace(Element method) {
        Element exception = exception(method);
        if (exception == null) {
            return "";
        }
        NodeList traces = exception.getElementsByTagName("full-stacktrace");
        String text = traces.getLength() == 0 ? "" : traces.item(0).getTextContent();
        if (text == null || text.isBlank()) {
            traces = exception.getElementsByTagName("short-stacktrace");
            text = traces.getLength() == 0 ? "" : traces.item(0).getTextContent();
        }
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() > 4000 ? trimmed.substring(0, 4000) + "\n…" : trimmed;
    }

    // ------------------------------------------------------------------ thresholds

    /**
     * {@code Publisher.perform}'s decision, in its order: a failed configuration method trumps everything when asked
     * for, then failed-fails, failed-skips, unstable-fails, unstable-skips — each compared with a strict {@code >}.
     */
    private static String[] decide(
            Summary summary,
            int total,
            int mode,
            int failedFails,
            int failedSkips,
            int unstableFails,
            int unstableSkips,
            boolean failureOnFailedTestConfig) {
        if (failureOnFailedTestConfig && summary.failedConfig > 0) {
            return new String[] {"FAILURE", summary.failedConfig + " configuration method(s) failed; "
                    + "failureOnFailedTestConfig marks the build FAILURE"};
        }
        if (mode == 1) {
            if (summary.failed > failedFails) {
                return new String[] {"FAILURE", summary.failed + " test(s) failed, over the failedFails threshold of "
                        + failedFails};
            }
            if (summary.skipped > failedSkips) {
                return new String[] {"FAILURE", summary.skipped + " test(s) skipped, over the failedSkips threshold of "
                        + failedSkips};
            }
            if (summary.failed > unstableFails) {
                return new String[] {"UNSTABLE", summary.failed + " test(s) failed, over the unstableFails threshold of "
                        + unstableFails};
            }
            if (summary.skipped > unstableSkips) {
                return new String[] {"UNSTABLE", summary.skipped + " test(s) skipped, over the unstableSkips threshold of "
                        + unstableSkips};
            }
            return new String[] {"NONE", "within every threshold"};
        }
        double failedPercent = total == 0 ? 0 : 100.0 * summary.failed / total;
        double skipPercent = total == 0 ? 0 : 100.0 * summary.skipped / total;
        if (failedPercent > failedFails) {
            return new String[] {"FAILURE", String.format(Locale.ROOT,
                    "%.1f%% of tests failed, over the failedFails threshold of %d%%", failedPercent, failedFails)};
        }
        if (skipPercent > failedSkips) {
            return new String[] {"FAILURE", String.format(Locale.ROOT,
                    "%.1f%% of tests skipped, over the failedSkips threshold of %d%%", skipPercent, failedSkips)};
        }
        if (failedPercent > unstableFails) {
            return new String[] {"UNSTABLE", String.format(Locale.ROOT,
                    "%.1f%% of tests failed, over the unstableFails threshold of %d%%", failedPercent, unstableFails)};
        }
        if (skipPercent > unstableSkips) {
            return new String[] {"UNSTABLE", String.format(Locale.ROOT,
                    "%.1f%% of tests skipped, over the unstableSkips threshold of %d%%", skipPercent, unstableSkips)};
        }
        return new String[] {"NONE", "within every threshold"};
    }

    // ------------------------------------------------------------------ report

    private static String render(
            String label, String pattern, int files, Summary summary, int total, String[] verdict) {
        double seconds = summary.seconds();
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9;color:#1f2328}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".verdict{display:inline-block;padding:6px 12px;border-radius:999px;font-size:13px;font-weight:600;margin-bottom:18px}")
                .append(".v-none{background:#ecfdf3;color:#15803d}.v-unstable{background:#fffbeb;color:#b45309}.v-failure{background:#fef2f2;color:#b91c1c}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:22px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:14px 18px;min-width:104px}")
                .append(".card b{display:block;font-size:24px;line-height:1.2}.card span{font-size:12px;color:#6b7280}")
                .append(".bad b{color:#b91c1c}.ok b{color:#15803d}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;border-radius:10px;overflow:hidden}")
                .append("th,td{text-align:left;padding:9px 12px;border-bottom:1px solid #eef0f3;font-size:13px;vertical-align:top}")
                .append("th{background:#fafbfc;color:#6b7280;font-weight:600}code{font-family:ui-monospace,Menlo,monospace}")
                .append("pre{white-space:pre-wrap;font-size:12px;color:#4b5563;margin:6px 0 0}")
                .append("</style></head><body>");

        html.append("<h1>").append(escape(label)).append("</h1>");
        html.append("<p class=\"meta\">").append(files).append(" file(s) matched <code>")
                .append(escape(pattern)).append("</code>");
        if (summary.unreadable > 0) {
            html.append(" · ").append(summary.unreadable).append(" unreadable");
        }
        html.append("</p>");

        String css = switch (verdict[0]) {
            case "FAILURE" -> "v-failure";
            case "UNSTABLE" -> "v-unstable";
            default -> "v-none";
        };
        html.append("<div class=\"verdict ").append(css).append("\">").append(escape(verdict[0]))
                .append(" — ").append(escape(verdict[1])).append("</div>");

        html.append("<div class=\"cards\">");
        card(html, "total", total, false);
        card(html, "passed", summary.passed, false);
        card(html, "failed", summary.failed, summary.failed > 0);
        card(html, "skipped", summary.skipped, false);
        card(html, "failed config", summary.failedConfig, summary.failedConfig > 0);
        html.append("<div class=\"card\"><b>").append(String.format(Locale.ROOT, "%.2fs", seconds))
                .append("</b><span>method time</span></div></div>");

        if (summary.cases.isEmpty()) {
            html.append("<p>Every test method passed.</p>");
        } else {
            html.append("<table><tr><th>Method</th><th>Status</th><th>Message</th></tr>");
            for (Case item : summary.cases) {
                html.append("<tr><td><code>").append(escape(item.className)).append('#')
                        .append(escape(item.name)).append("</code><br><span class=\"meta\">")
                        .append(escape(item.suite)).append(" / ").append(escape(item.test)).append("</span></td><td>")
                        .append(escape(item.status)).append(item.config ? " (config)" : "").append("</td><td>")
                        .append(escape(item.message));
                if (!item.stack.isBlank()) {
                    html.append("<pre>").append(escape(item.stack)).append("</pre>");
                }
                html.append("</td></tr>");
            }
            html.append("</table>");
        }
        return html.append("</body></html>").toString();
    }

    private static void card(StringBuilder html, String label, int value, boolean bad) {
        html.append("<div class=\"card").append(bad ? " bad" : "").append("\"><b>").append(value)
                .append("</b><span>").append(label).append("</span></div>");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private static int toInt(String value) {
        try {
            return value == null || value.isBlank() ? 0 : (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Case(
            String suite, String test, String className, String name, String status, boolean config,
            String description, String message, String stack) {
        String describe() {
            return status + (config ? " (config)" : "") + " " + (className.isEmpty() ? "" : className + "#") + name
                    + (message.isBlank() ? "" : " — " + message);
        }
    }

    private static final class Summary {
        int passed;
        int failed;
        int skipped;
        int failedConfig;
        int skippedConfig;
        int unreadable;
        String firstParseError;
        long millis;
        final List<Case> cases = new ArrayList<>();

        /** Method durations added up — not wall-clock time; the plugin derives its duration from started/finished stamps. */
        double seconds() {
            return millis / 1000.0;
        }
    }
}

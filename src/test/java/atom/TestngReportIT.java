package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The testng-report atom against testng-results.xml files written here: no Jenkins, no TestNG.
 *
 * <p>The counts come from the {@code <test-method>} elements, never from the root attributes; configuration methods
 * are counted apart; the threshold decision uses the plugin's strict {@code >}; and the HTML report is read back.
 */
class TestngReportIT {

    /** The plugin's own fixture: the root attributes lie (total=99), the methods do not. */
    private static final String MIXED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testng-results ignored="9" total="99" passed="88" failed="7" skipped="4">
              <reporter-output/>
              <suite name="S1" duration-ms="900" started-at="2026-10-31T10:00:00" finished-at="2026-10-31T10:00:01">
                <groups/>
                <test name="T1" duration-ms="800" started-at="2026-10-31T10:00:00" finished-at="2026-10-31T10:00:01">
                  <class name="demo.Alpha">
                    <test-method status="PASS" signature="pass1()" name="pass1" duration-ms="10" started-at="2026-10-31T10:00:00">
                      <params/>
                    </test-method>
                    <test-method status="FAIL" signature="fail1()" name="fail1" duration-ms="20" started-at="2026-10-31T10:00:00">
                      <exception class="java.lang.AssertionError">
                        <message><![CDATA[expected 1 but was 2]]></message>
                        <full-stacktrace><![CDATA[java.lang.AssertionError: expected 1 but was 2
            \\tat demo.Alpha.fail1(Alpha.java:13)
            \\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)]]></full-stacktrace>
                      </exception>
                    </test-method>
                    <test-method status="SKIP" signature="skip1()" name="skip1" duration-ms="0" started-at="2026-10-31T10:00:00">
                      <exception class="java.lang.RuntimeException">
                        <message><![CDATA[Method skip1() depends on not-yet-failed method]]></message>
                      </exception>
                    </test-method>
                    <test-method status="FAIL" is-config="true" signature="setUp()" name="setUp" duration-ms="5" started-at="2026-10-31T10:00:00">
                      <exception class="java.lang.IllegalStateException">
                        <message><![CDATA[cannot reach wiremock on port 8089]]></message>
                        <full-stacktrace><![CDATA[java.lang.IllegalStateException: cannot reach wiremock on port 8089
            \\tat demo.Alpha.setUp(Alpha.java:7)]]></full-stacktrace>
                      </exception>
                    </test-method>
                  </class>
                  <class name="demo.Beta">
                    <test-method status="PASS" signature="pass2()" name="pass2" duration-ms="15" started-at="2026-10-31T10:00:00">
                      <params/>
                    </test-method>
                  </class>
                </test>
              </suite>
            </testng-results>
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    private Atom mixed() throws Exception {
        return Atom.of().file("target/testng-results.xml", MIXED);
    }

    @Test
    @DisplayName("plugin defaults: counts come from the methods, 25% failures make the build UNSTABLE, report read back")
    void pluginDefaults() throws Exception {
        AtomResult run = mixed().input("{}").run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("\"verdict\":{\"type\":\"string\",\"value\":\"UNSTABLE\"}"),
                "plugin defaults must make 25% failures UNSTABLE: " + run.file("output.json"));
        assertTrue(run.file("output.json").contains("\"build.result\":{\"type\":\"string\",\"value\":\"UNSTABLE\"}"),
                "UNSTABLE not requested");
        assertTrue(run.file("output.json").contains("\"total\":{\"type\":\"string\",\"value\":\"4\"}"),
                "counts must come from the methods, not the root attributes: " + run.file("output.json"));
        assertTrue(run.file("output.json").contains("\"failedConfigMethods\":{\"type\":\"string\",\"value\":\"1\"}"),
                "config method not counted apart");

        String report = run.file("out/testng/index.html");
        assertTrue(report != null, "no HTML report written");
        assertTrue(report.contains("demo.Alpha#fail1"), "failing method missing from report");
        assertTrue(report.contains("Alpha.java:13"), "stack trace missing from report");
    }

    @Test
    @DisplayName("failureOnFailedTestConfig fails the step before any threshold is looked at")
    void failedConfigTrumpsEverything() throws Exception {
        AtomResult run = mixed().input("{\"failureOnFailedTestConfig\":\"true\"}").run();

        assertEquals("failure", run.status(), "failureOnFailedTestConfig must fail the step");
        run.assertRefused();
    }

    @Test
    @DisplayName("count mode: one failure over failedFails=0 is FAILURE")
    void countMode() throws Exception {
        AtomResult run = mixed().input("{\"thresholdMode\":\"1\",\"failedFails\":\"0\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("\"verdict\":{\"type\":\"string\",\"value\":\"FAILURE\"}"),
                "verdict not FAILURE");
    }

    @Test
    @DisplayName("the plugin compares with a strict >: exactly at the threshold does not trip")
    void atTheThreshold() throws Exception {
        AtomResult run = mixed()
                .input("{\"thresholdMode\":\"1\",\"failedFails\":\"1\",\"unstableFails\":\"5\",\"unstableSkips\":\"100\"}")
                .run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("\"verdict\":{\"type\":\"string\",\"value\":\"NONE\"}"),
                "1 failure with failedFails=1 must not trip (the plugin compares with '>'): " + run.file("output.json"));
        assertFalse(run.file("output.json").contains("build.result"), "nothing to flag, yet build.result was written");
    }

    @Test
    @DisplayName("percentage mode: 1/3 = 33.3% trips a 33% threshold, not a 34% one")
    void percentageBoundary() throws Exception {
        Atom pct = Atom.of().file("r/testng-results.xml", """
                <testng-results total="3" passed="2" failed="1" skipped="0">
                  <suite name="P"><test name="T"><class name="demo.Pct">
                    <test-method status="PASS" name="a" duration-ms="1"/>
                    <test-method status="PASS" name="b" duration-ms="1"/>
                    <test-method status="FAIL" name="c" duration-ms="1">
                      <exception class="java.lang.AssertionError"><message>nope</message></exception>
                    </test-method>
                  </class></test></suite>
                </testng-results>
                """);

        AtomResult below = pct.input("{\"thresholdMode\":\"2\",\"failedFails\":\"34\"}").run();
        below.assertOk();
        assertTrue(below.file("output.json").contains("\"verdict\":{\"type\":\"string\",\"value\":\"UNSTABLE\"}"),
                "33.3% must not exceed a 34% failure threshold: " + below.file("output.json"));

        AtomResult above = pct.input("{\"thresholdMode\":\"2\",\"failedFails\":\"33\"}").run();
        above.assertRefused();
        assertTrue(above.file("output.json").contains("\"verdict\":{\"type\":\"string\",\"value\":\"FAILURE\"}"),
                "33.3% must exceed a 33% failure threshold");
    }

    @Test
    @DisplayName("a semicolon list of patterns is summed; an unreadable report is reported, and only opt-in lets it pass")
    void patternListAndUnreadableReport() throws Exception {
        Atom multi = Atom.of()
                .file("a/x.xml", """
                        <testng-results total="2" passed="2" failed="0" skipped="0">
                          <suite name="SX"><test name="TX"><class name="demo.X">
                            <test-method status="PASS" name="x1" duration-ms="3"/>
                            <test-method status="PASS" name="x2" duration-ms="4"/>
                          </class></test></suite>
                        </testng-results>
                        """)
                .file("b/y.xml", """
                        <testng-results total="2" passed="1" failed="0" skipped="1">
                          <suite name="SY"><test name="TY"><class name="demo.Y">
                            <test-method status="PASS" name="y1" duration-ms="3"/>
                            <test-method status="SKIP" name="y2" duration-ms="0"/>
                          </class></test></suite>
                        </testng-results>
                        """)
                .file("b/broken.xml", "not xml at all\n");

        AtomResult lenient = multi
                .input("{\"reportFilenamePattern\":\"a/*.xml;b/*.xml\",\"thresholdMode\":\"1\",\"failedFails\":\"0\","
                        + "\"unstableSkips\":\"100\",\"allowUnreadableReports\":\"true\"}")
                .run();
        lenient.assertOk();
        assertTrue(lenient.file("output.json").contains("\"total\":{\"type\":\"string\",\"value\":\"4\"}"),
                "both patterns must be summed: " + lenient.file("output.json"));
        assertTrue(lenient.file("output.json").contains("\"verdict\":{\"type\":\"string\",\"value\":\"NONE\"}"),
                "skips inside threshold must stay NONE");
        assertTrue(lenient.console().toLowerCase().contains("cannot parse"),
                "an unreadable report must be reported, not swallowed");

        AtomResult strict = multi
                .input("{\"reportFilenamePattern\":\"a/*.xml;b/*.xml\",\"thresholdMode\":\"1\",\"failedFails\":\"0\","
                        + "\"unstableSkips\":\"100\"}")
                .run();
        strict.assertRefused();
        assertTrue(strict.file("output.json").contains("\"errorCode\":40003"),
                "expected errorCode 40003: " + strict.file("output.json"));
        assertTrue(strict.file("output.json").contains("broken.xml"), "the failure must name the file");
    }

    @Test
    @DisplayName("a matched report with no test methods does not fail the step (the plugin continues)")
    void reportWithoutMethods() throws Exception {
        AtomResult run = Atom.of()
                .file("e/testng-results.xml", "<testng-results total=\"0\" passed=\"0\" failed=\"0\" skipped=\"0\">"
                        + "<suite name=\"E\"><test name=\"E\"/></suite></testng-results>\n")
                .input("{}")
                .run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("\"verdict\":{\"type\":\"string\",\"value\":\"NONE\"}"),
                "empty report verdict");
    }

    @Test
    @DisplayName("no report at all is a zero, and failIfNoReports is what turns that into an error")
    void missingReport() throws Exception {
        AtomResult silent = Atom.of().input("{}").run();
        silent.assertOk();
        assertTrue(silent.file("output.json").contains("\"total\":{\"type\":\"string\",\"value\":\"0\"}"),
                "missing report must report zeros");

        AtomResult loud = Atom.of().input("{\"failIfNoReports\":\"true\"}").run();
        loud.assertRefused();
        assertTrue(loud.file("output.json").contains("\"errorCode\":40001"),
                "expected errorCode 40001: " + loud.file("output.json"));
    }

    @Test
    @DisplayName("an unknown thresholdMode is refused by number, not guessed")
    void badThresholdMode() throws Exception {
        AtomResult run = mixed().input("{\"thresholdMode\":\"3\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("\"errorCode\":40002"),
                "expected errorCode 40002: " + run.file("output.json"));
    }
}

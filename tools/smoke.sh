#!/usr/bin/env bash
# Run the atom the way the runner does, against testng-results.xml files written here: no Jenkins, no TestNG.
#
# What is actually being verified
#   · counts come from the <test-method> elements, not from the lying total/passed/failed attributes on
#     <testng-results> (that is what the plugin does — ResultsParser walks methods and ClassResult.tally() counts them);
#   · configuration methods are counted apart and only matter with failureOnFailedTestConfig;
#   · the threshold decision is re-computed here independently (tools/check.py, Python + ElementTree, the rules written
#     out again from the plugin's perform()) and compared field by field with output.json;
#   · every failure path has an assertion of its own (no report, unreadable report, invalid thresholdMode);
#   · the HTML report is read back from the workspace.
set -euo pipefail
cd "$(dirname "$0")/.."

export BUILD_NUMBER=7 JOB_NAME="$(basename "$PWD")-job"
./build.sh >/dev/null
jar="$(cd dist/testng && pwd)/testng.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
fail() { echo "smoke failed: $*" >&2; exit 1; }

run() { # run <dir> — exit code is the atom's, which we assert on
  local dir="$1"
  ( cd "$dir" && env atom_data_dir="$dir" atom_data_input=input.json atom_data_output=output.json \
      atom_workspace="$dir" "$java_bin" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner )
}

# check <dir> <files…>: independent recomputation, compared with output.json
check() {
  local dir="$1"; shift
  python3 tools/check.py --input "$dir/input.json" --output "$dir/output.json" --workspace "$dir" -- "$@" \
    || fail "independent check disagreed in $dir"
}

#################################################################### 1. mixed fixture, plugin defaults
mixed=$(mktemp -d); mkdir -p "$mixed/target"
cat >"$mixed/target/testng-results.xml" <<'XML'
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
	at demo.Alpha.fail1(Alpha.java:13)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)]]></full-stacktrace>
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
	at demo.Alpha.setUp(Alpha.java:7)]]></full-stacktrace>
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
XML
printf '{}' >"$mixed/input.json"
run "$mixed" >"$mixed/log.txt" 2>&1 || fail "default run failed: $(cat "$mixed/log.txt")"
check "$mixed" "$mixed/target/testng-results.xml" || true
grep -q '"verdict":{"type":"string","value":"UNSTABLE"}' "$mixed/output.json" \
  || fail "plugin defaults must make 25% failures UNSTABLE: $(cat "$mixed/output.json")"
grep -q '"build.result":{"type":"string","value":"UNSTABLE"}' "$mixed/output.json" || fail "UNSTABLE not requested"
grep -q '"total":{"type":"string","value":"4"}' "$mixed/output.json" \
  || fail "counts must come from the methods, not the root attributes: $(cat "$mixed/output.json")"
grep -q '"failedConfigMethods":{"type":"string","value":"1"}' "$mixed/output.json" || fail "config method not counted apart"
# the report is a product, so read it back
[ -f "$mixed/out/testng/index.html" ] || fail "no HTML report written"
grep -q 'demo.Alpha#fail1' "$mixed/out/testng/index.html" || fail "failing method missing from report"
grep -q 'Alpha.java:13' "$mixed/out/testng/index.html" || fail "stack trace missing from report"

#################################################################### 2. failureOnFailedTestConfig trumps everything
cp -r "$mixed" "${mixed}-cfg" 2>/dev/null || true
cfg="$(mktemp -d)"; cp -r "$mixed/target" "$cfg/"
printf '{"failureOnFailedTestConfig":"true"}' >"$cfg/input.json"
set +e; run "$cfg" >"$cfg/log.txt" 2>&1; code=$?; set -e
[ "$code" -ne 0 ] || fail "failureOnFailedTestConfig must fail the step"
grep -q '"status":"failure"' "$cfg/output.json" || fail "expected failure status: $(cat "$cfg/output.json")"
check "$cfg" "$cfg/target/testng-results.xml"

#################################################################### 3. count mode: any failure over 0 → FAILURE
cnt="$(mktemp -d)"; cp -r "$mixed/target" "$cnt/"
printf '{"thresholdMode":"1","failedFails":"0"}' >"$cnt/input.json"
set +e; run "$cnt" >"$cnt/log.txt" 2>&1; code=$?; set -e
[ "$code" -ne 0 ] || fail "thresholdMode=1 failedFails=0 with 1 failure must FAILURE"
grep -q '"verdict":{"type":"string","value":"FAILURE"}' "$cnt/output.json" || fail "verdict not FAILURE"
check "$cnt" "$cnt/target/testng-results.xml"

#################################################################### 4. strict ">": exactly at the threshold does not trip
edge="$(mktemp -d)"; cp -r "$mixed/target" "$edge/"
printf '{"thresholdMode":"1","failedFails":"1","unstableFails":"5","unstableSkips":"100"}' >"$edge/input.json"
run "$edge" >"$edge/log.txt" 2>&1 || fail "at-threshold run failed"
grep -q '"verdict":{"type":"string","value":"NONE"}' "$edge/output.json" \
  || fail "1 failure with failedFails=1 must not trip (the plugin compares with '>'): $(cat "$edge/output.json")"
grep -q 'build.result' "$edge/output.json" && fail "nothing to flag, yet build.result was written"
check "$edge" "$edge/target/testng-results.xml"

#################################################################### 5. percentage boundary: 1/3 = 33.3%
pct="$(mktemp -d)"; mkdir -p "$pct/r"
cat >"$pct/r/testng-results.xml" <<'XML'
<testng-results total="3" passed="2" failed="1" skipped="0">
  <suite name="P"><test name="T"><class name="demo.Pct">
    <test-method status="PASS" name="a" duration-ms="1"/>
    <test-method status="PASS" name="b" duration-ms="1"/>
    <test-method status="FAIL" name="c" duration-ms="1">
      <exception class="java.lang.AssertionError"><message>nope</message></exception>
    </test-method>
  </class></test></suite>
</testng-results>
XML
printf '{"thresholdMode":"2","failedFails":"34"}' >"$pct/input.json"
run "$pct" >"$pct/log.txt" 2>&1 || fail "pct run failed"
grep -q '"verdict":{"type":"string","value":"UNSTABLE"}' "$pct/output.json" \
  || fail "33.3% must not exceed a 34% failure threshold: $(cat "$pct/output.json")"
check "$pct" "$pct/r/testng-results.xml"
printf '{"thresholdMode":"2","failedFails":"33"}' >"$pct/input.json"
set +e; run "$pct" >"$pct/log2.txt" 2>&1; code=$?; set -e
[ "$code" -ne 0 ] || fail "33.3% must exceed a 33% failure threshold"
grep -q '"verdict":{"type":"string","value":"FAILURE"}' "$pct/output.json" || fail "pct verdict not FAILURE"
check "$pct" "$pct/r/testng-results.xml"

#################################################################### 6. pattern list + unreadable file + no test methods
multi="$(mktemp -d)"; mkdir -p "$multi/a" "$multi/b"
cat >"$multi/a/x.xml" <<'XML'
<testng-results total="2" passed="2" failed="0" skipped="0">
  <suite name="SX"><test name="TX"><class name="demo.X">
    <test-method status="PASS" name="x1" duration-ms="3"/>
    <test-method status="PASS" name="x2" duration-ms="4"/>
  </class></test></suite>
</testng-results>
XML
cat >"$multi/b/y.xml" <<'XML'
<testng-results total="2" passed="1" failed="0" skipped="1">
  <suite name="SY"><test name="TY"><class name="demo.Y">
    <test-method status="PASS" name="y1" duration-ms="3"/>
    <test-method status="SKIP" name="y2" duration-ms="0"/>
  </class></test></suite>
</testng-results>
XML
printf 'not xml at all\n' >"$multi/b/broken.xml"
printf '{"reportFilenamePattern":"a/*.xml;b/*.xml","thresholdMode":"1","failedFails":"0","unstableSkips":"100","allowUnreadableReports":"true"}' >"$multi/input.json"
run "$multi" >"$multi/log.txt" 2>&1 || fail "list-pattern run failed: $(cat "$multi/log.txt")"
grep -q '"total":{"type":"string","value":"4"}' "$multi/output.json" \
  || fail "both patterns must be summed: $(cat "$multi/output.json")"
grep -q '"verdict":{"type":"string","value":"NONE"}' "$multi/output.json" || fail "skips inside threshold must stay NONE"
grep -qi 'cannot parse' "$multi/log.txt" || fail "an unreadable report must be reported, not swallowed"
check "$multi" "$multi/a/x.xml" "$multi/b/y.xml"

# the same inputs without the opt-in: a report that never parsed is not "0 tests and all good"
printf '{"reportFilenamePattern":"a/*.xml;b/*.xml","thresholdMode":"1","failedFails":"0","unstableSkips":"100"}' >"$multi/input.json"
set +e; run "$multi" >"$multi/strict.log" 2>&1; strict=$?; set -e
[ "$strict" -ne 0 ] || fail "an unparseable report must fail the step unless allowUnreadableReports=true"
grep -q '"errorCode":40003' "$multi/output.json" || fail "expected errorCode 40003: $(cat "$multi/output.json")"
grep -q "broken.xml" "$multi/output.json" || fail "the failure must name the file: $(cat "$multi/output.json")"
printf '{"reportFilenamePattern":"a/*.xml;b/*.xml","thresholdMode":"1","failedFails":"0","unstableSkips":"100","allowUnreadableReports":"true"}' >"$multi/input.json"

# a matched file with no test methods must not fail the step (plugin: "did not find any TestNG results")
empty="$(mktemp -d)"; mkdir -p "$empty/e"
printf '<testng-results total="0" passed="0" failed="0" skipped="0"><suite name="E"><test name="E"/></suite></testng-results>\n' >"$empty/e/testng-results.xml"
printf '{}' >"$empty/input.json"
run "$empty" >"$empty/log.txt" 2>&1 || fail "report without methods must not fail the step"
grep -q '"verdict":{"type":"string","value":"NONE"}' "$empty/output.json" || fail "empty report verdict"

#################################################################### 7. failure paths
none="$(mktemp -d)"; printf '{}' >"$none/input.json"
run "$none" >"$none/log.txt" 2>&1 || fail "a missing report must not fail the step by default (the plugin continues)"
grep -q '"total":{"type":"string","value":"0"}' "$none/output.json" || fail "missing report must report zeros"
printf '{"failIfNoReports":"true"}' >"$none/input.json"
set +e; run "$none" >"$none/log2.txt" 2>&1; code=$?; set -e
[ "$code" -ne 0 ] || fail "failIfNoReports must fail the step"
grep -q '"errorCode":40001' "$none/output.json" || fail "expected errorCode 40001: $(cat "$none/output.json")"

bad="$(mktemp -d)"; cp -r "$mixed/target" "$bad/"
printf '{"thresholdMode":"3"}' >"$bad/input.json"
set +e; run "$bad" >"$bad/log.txt" 2>&1; code=$?; set -e
[ "$code" -ne 0 ] || fail "thresholdMode=3 must be rejected"
grep -q '"errorCode":40002' "$bad/output.json" || fail "expected errorCode 40002: $(cat "$bad/output.json")"

echo "smoke ok: defaults/threshold ordering/strict >/percentage/list patterns/unreadable report fails by default/opt-in keeps going/empty/missing/bad mode/report read-back"

# jenkins-atom-testng

The [testng](https://plugins.jenkins.io/testng-plugin/) plugin (about 5.9k installations) as an **atom**: read the
`testng-results.xml` your build already produced, count the methods, and apply the plugin's own threshold rules.

* `reportFilenamePattern` — workspace glob (`**/testng-results.xml` by default; several patterns separated by `,` or `;`)
* `thresholdMode` — `1` = number of tests, `2` = percentage of tests (**default 2, as in the plugin**)
* `failedFails` / `failedSkips` (default `100`/`100`), `unstableFails` / `unstableSkips` (default `0`/`100`)
* `failureOnFailedTestConfig` — a failed `@Before`/`@After` method makes the build FAILURE, trumps every threshold
* outputs — `total`, `passed`, `failed`, `skipped`, `failedConfigMethods`, `skippedConfigMethods`, `durationSeconds`,
  `verdict` (`NONE`/`UNSTABLE`/`FAILURE`)
* `report` — HTML written to `out/testng/index.html`, archived and embedded in the build page, with the failure message
  and full stack trace per method
* `failIfNoReports` — off by default: the plugin logs "Did not find any matching files" and lets the build continue,
  and so does this atom

The decision order is the plugin's, from `Publisher.perform()`: failed configuration methods (when asked for), then
failed-fails, failed-skips, unstable-fails, unstable-skips — each with a strict `>`, which is why a build with exactly
`failedFails` failures stays green.

Counts come from the `<test-method>` elements, never from the `total`/`passed`/`failed` attributes on
`<testng-results>` — those are whatever the reporter felt like writing, and `ResultsParser` + `ClassResult.tally()`
recount for the same reason. Configuration methods (`is-config`) are counted apart and are not part of `total`.

**What it does not do**: the build page's per-class/per-package drill-down and the trend graph
(`TestNGTestResultBuildAction`). That is a UI host, not another atom.

## Build and test

```bash
mvn verify          # -> dist/testng/{testng.jar,task.json}, SDK inside; 9 cases in TestngReportIT
```

`tools/check.py` stays as a second implementation (Python + ElementTree) of the counting and threshold rules;
`TestngReportIT` writes the same fixtures, runs the atom through `AtomRunner` and asserts
`total/passed/failed/skipped/verdict` directly. Two mutations were run against the rules to prove the assertions bite:
`>` to `>=` in the failed-fails comparison (caught by the at-threshold scenario) and `is-config` ignored (caught by the
`total`/`failedConfigMethods` mismatch).

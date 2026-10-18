#!/usr/bin/env python3
"""Static checks for the Arabic upstream-sync workflow.

Run: python3 .github/scripts/test-sync-config.py

These are *config* checks (no network, no Android build): they lock the invariants that
must never regress in the sync/build pipeline, and exercise the documented conflict
classification rule against representative paths.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WF = ROOT / ".github/workflows/sync-and-release.yml"
STATE = ROOT / ".github/upstream-sync.json"

failures = []


def check(name, cond, detail=""):
    print(f"{'PASS' if cond else 'FAIL'}  {name}" + (f"  [{detail}]" if detail and not cond else ""))
    if not cond:
        failures.append(name)


def main():
    try:
        import yaml
    except ImportError:
        sys.exit("PyYAML is required to run this check (pip install pyyaml)")

    text = WF.read_text()
    wf = yaml.safe_load(text)
    env = wf.get("env", {})
    triggers = wf.get(True, wf.get("on", {}))

    # 1. YAML parses and has the expected structure.
    check("workflow parses", isinstance(wf, dict))
    check("triggers on push/schedule/workflow_dispatch",
          all(k in triggers for k in ("push", "schedule", "workflow_dispatch")))

    steps = wf["jobs"]["pipeline"]["steps"]
    names = [s.get("name", "") for s in steps]

    # 2. Phase isolation: fetch, decide, merge, verify, build, tests, push, optional release.
    for phase in ("A) Fetch upstream", "B) Decide phase", "C) Attempt upstream merge",
                  "C2) Gate build/release",
                  "D) Verify official upstream is contained", "E) Determine Release Version",
                  "F) Validate Arabic resource completeness", "G) Validate Arabic integration hooks",
                  "I) Run app JVM unit tests", "I2) Run lintRelease",
                  "I3) Run Room instrumentation tests", "J) Restore signing key", "K) Build signed universal APK",
                  "L) Verify generated APK integrity", "M) Push validated upstream merge",
                  "N) Publish GitHub Release", "O) Outcome"):
        check(f"phase present: {phase}", any(n.startswith(phase) for n in names))

    # 3. The merge step must run on an isolated branch and must never hard-fail on conflicts.
    merge = next(s for s in steps if s.get("name", "").startswith("C) Attempt upstream merge"))
    merge_run = merge["run"]
    check("merge uses an isolated branch", "git switch -c sync/upstream-" in merge_run)
    check("merge aborts cleanly on conflict (no exit 1)", "exit 1" not in merge_run)
    check("merge records blocked SHA state", "blockedUpstream" in merge_run)
    check("state commit is [skip ci]", "[skip ci]" in merge_run)
    check("blocked-state push is restricted to master ref",
          'github.ref }}" = "refs/heads/master"' in merge_run and "git push origin HEAD:master" in merge_run)

    # 4. Push/release happen only after the build+verify phases.
    def phase_index(prefix):
        return next(i for i, name in enumerate(names) if name.startswith(prefix))

    check("push after all checks and APK verification",
          phase_index("M) Push validated upstream merge") > phase_index("L) Verify generated APK integrity") >
          phase_index("K) Build signed universal APK") > phase_index("I3) Run Room instrumentation tests") >
          phase_index("I2) Run lintRelease") > phase_index("I) Run app JVM unit tests"))
    check("release after push",
          phase_index("N) Publish GitHub Release") > phase_index("M) Push validated upstream merge"))

    # 4b. Every build/release step must be gated by the computed do_build output.
    build_phases = [s for s in steps if re.match(r"^([E-N])\)", s.get("name", ""))]
    unconditional = [s.get("name") for s in build_phases
                     if "steps.gate.outputs.do_build == 'true'" not in (s.get("if") or "")]
    check("all build/release steps gated by do_build", not unconditional, str(unconditional))
    gate = next((s for s in steps if s.get("name", "").startswith("C2) Gate")), None)
    check("gate computes do_build", gate is not None and "do_build=" in gate.get("run", ""))
    check("gate blocks sync-only conflict",
          gate is not None and '$BLOCKED" = "true"' in gate.get("run", "") and '$CURRENT" != "true"' in gate.get("run", ""))
    check("gate makes sync-only a dry-run",
          gate is not None and '"sync-only"' in gate.get("run", "") and 'DO_BUILD="false"' in gate.get("run", ""))
    outcome = next((s for s in steps if s.get("name", "").startswith("O) Outcome")), None)
    check("outcome distinguishes staged from released",
          outcome is not None and "built and staged" in outcome.get("run", "") and
          "built and released" in outcome.get("run", ""))
    check("outcome reports job failure honestly",
          outcome is not None and 'job.status' in outcome.get("run", "") and
          "APK file present" in outcome.get("run", "") and "No claim is made" in outcome.get("run", ""))

    # 4d. A CI-only push must not publish an app release.
    check("CI-only push does not trigger a build",
          "APP_CHANGED" in text and 'APP_CHANGED" = "true"' in text)

    def plan_build(is_push, app_changed, force, mode):
        if force or mode == "build-only":
            return True
        return bool(is_push and app_changed)

    for name, args, exp in [
        ("CI-only push", (True, False, False, "auto"), False),
        ("app-code push", (True, True, False, "auto"), True),
        ("build-only dispatch", (False, False, False, "build-only"), True),
        ("force_build", (False, False, True, "auto"), True),
        ("scheduled run (no push)", (False, False, False, "auto"), False),
    ]:
        got = plan_build(*args)
        check(f"plan build: {name}", got == exp, f"got={got}")

    # 4c. Previously-existing protections must still be present.
    check("Arabic hooks validation present", "AppLanguage.ARABIC" in text and "AppLocale.wrap" in text)
    check("conversation-scoped validation present",
          "getConversationAssistant" in text and "resolveChatModel" in text)
    check("APK artifact upload present", "actions/upload-artifact@v4" in text)
    release = next((s for s in steps if s.get("name", "").startswith("N) Publish GitHub Release")), None)
    release_if = release.get("if", "") if release else ""
    check("publish_release input defaults false",
          triggers["workflow_dispatch"]["inputs"]["publish_release"].get("default") is False)
    check("public release requires explicit dispatch on master",
          release is not None and "steps.gate.outputs.publish_release == 'true'" in release_if and
          "steps.gate.outputs.do_build == 'true'" in release_if and
          "github.event_name == 'workflow_dispatch'" in text and
          "inputs.publish_release == true" in text and "github.ref == 'refs/heads/master'" in text)
    check("release has no clobber fallback and refuses tag reuse",
          "gh release create" in text and "--clobber" not in text and
          "gh release upload" not in text and "Refusing to reuse existing tag" in text)
    checkout = next((s for s in steps if s.get("name", "").startswith("Checkout Arabic fork")), None)
    check("build-only dispatch checks out selected ref",
          checkout is not None and "inputs.mode == 'build-only'" in checkout["with"].get("ref", "") and
          "github.ref_name" in checkout["with"].get("ref", ""))
    push = next((s for s in steps if s.get("name", "").startswith("M) Push validated")), None)
    check("validated merge push is restricted to master ref",
          push is not None and "github.ref == 'refs/heads/master'" in push.get("if", ""))
    check("latest release gates a monotonic versionCode",
          "gh release list" in text and "LAST_RELEASE_CODE" in text and "LAST_RELEASE_CODE + 1" in text)
    check("official APK tools validate signature and manifest metadata",
          "apksigner verify --print-certs" in text and "aapt dump badging" in text and
          "EXPECTED_VERSION_NAME" in text and "EXPECTED_VERSION_CODE" in text)
    check("APK verification uses configured release certificate",
          "27326144bdee8eefa9cd7a39e80c3a5322e8332419b86d4b13cfecbbe9eb7b73" in text)
    check("app JVM tests target available debug test task",
          "./gradlew :app:testDebugUnitTest --no-daemon" in text and "./gradlew test --no-daemon" not in text)
    check("lint and Room emulator instrumentation checks are configured",
          ":app:lintRelease" in text and "reactivecircus/android-emulator-runner@v2" in text and
          "api-level: 35" in text and "arch: x86_64" in text and "/dev/kvm" in text and
          ":app:connectedDebugAndroidTest" in text)
    report_upload = next((s for s in steps if s.get("name", "").startswith("Upload test and lint reports")), None)
    check("test and lint reports upload always",
          report_upload is not None and report_upload.get("if") == "always()" and
          "**/build/test-results/" in report_upload.get("with", {}).get("path", "") and
          "app/build/reports/lint-results-release.html" in report_upload.get("with", {}).get("path", ""))
    check("library unit tests remain covered", all(f":{module}:test" in text for module in
          ("ai", "common", "document", "highlight", "material3", "mediagen", "oauth", "search", "speech", "ui", "web", "workspace")))
    check("signed in-place upgrade is exercised", "verify-apk-upgrade.sh" in text and
          "Fetch previous Arabic APK" in text and "upgrade-verification/" in text)

    # 5. Protected rules & invariants.
    check("ours-on-conflict rule present", bool(env.get("OURS_ON_CONFLICT")))
    check("protected patterns present", bool(env.get("PROTECTED_PATTERNS")))
    check("signing SHA locked", "27326144bdee8eefa9cd7a39e80c3a5322e8332419b86d4b13cfecbbe9eb7b73" in text)
    check("applicationId locked", "me.rerere.rikkahub.arabic.dev" in text)
    check("no destructive git commands",
          not any(bad in text for bad in ("reset --hard", "push --force", "push -f")))

    # 6. State file is valid JSON.
    state = json.loads(STATE.read_text())
    check("state file valid json", "blockedUpstream" in state)

    # 7. Documented conflict rule: .github/workflows/** always resolves to ours.
    ours = env["OURS_ON_CONFLICT"]
    check("CI files resolve to ours", bool(re.search(ours, ".github/workflows/daily-build.yml")))
    check("app code is NOT auto-resolved", not re.search(ours, "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"))

    print()
    if failures:
        print(f"{len(failures)} check(s) FAILED: {failures}")
        sys.exit(1)
    print("All sync-config checks passed.")


if __name__ == "__main__":
    main()

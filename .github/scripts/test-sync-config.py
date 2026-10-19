#!/usr/bin/env python3
"""Static invariants for the Arabic upstream-sync workflow.

Run: python3 .github/scripts/test-sync-config.py
Behavioral shell-step tests live in test-sync-behavior.py.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WF = ROOT / ".github/workflows/sync-and-release.yml"
STATE = ROOT / ".github/upstream-sync.json"
failures = []


def check(name, condition, detail=""):
    print(f"{'PASS' if condition else 'FAIL'}  {name}" + (f" [{detail}]" if detail and not condition else ""))
    if not condition:
        failures.append(name)


def main():
    try:
        import yaml
    except ImportError:
        sys.exit("PyYAML is required; the workflow installs it before running this check")

    text = WF.read_text()
    wf = yaml.safe_load(text)
    env = wf.get("env", {})
    triggers = wf.get(True, wf.get("on", {}))
    steps = wf["jobs"]["pipeline"]["steps"]
    names = [s.get("name", "") for s in steps]
    by_prefix = lambda prefix: next((s for s in steps if s.get("name", "").startswith(prefix)), None)

    check("workflow parses", isinstance(wf, dict))
    check("push/schedule/workflow_dispatch triggers exist",
          all(k in triggers for k in ("push", "schedule", "workflow_dispatch")))

    # Preserve actual APK certificate parser behavior, not just its spelling.
    integrity = by_prefix("L) Verify generated APK integrity")
    parser_line = next(line.strip() for line in integrity["run"].splitlines()
                       if line.strip().startswith("CERT_SHA=$("))
    parser = parser_line[len("CERT_SHA=$("):-1]
    cert = "27326144bdee8eefa9cd7a39e80c3a5322e8332419b86d4b13cfecbbe9eb7b73"
    for label, report in {
        "legacy": f"Signer #1 certificate SHA-256 digest: {cert}",
        "modern": f"V2 Signer: certificate SHA-256 digest: {cert}",
        "same signer in two schemes": f"V2 Signer: certificate SHA-256 digest: {cert}\nV3 Signer: certificate SHA-256 digest: {cert}",
    }.items():
        actual = subprocess.check_output(
            ["bash", "-euo", "pipefail", "-c", parser],
            env={"SIGNATURE_REPORT": report, "PATH": "/usr/bin:/bin"}, text=True,
        ).strip()
        check(f"certificate parser supports {label}", actual == cert)

    for phase in (
        "Set up Python for sync safety tests", "Install sync test parser dependency",
        "Run static and real-git sync safety tests", "A) Fetch upstream", "B) Decide phase",
        "C) Attempt upstream merge", "C1) Record blocked upstream state", "C2) Gate build/release",
        "D) Verify official upstream is contained", "E) Determine Release Version",
        "F) Validate Arabic resource completeness", "G) Validate Arabic integration hooks",
        "I) Run app JVM unit tests", "I2) Run lintRelease", "I3) Run Room instrumentation tests",
        "J) Restore signing key", "K) Build signed universal APK", "L) Verify generated APK integrity",
        "P3) Record validation failure", "M) Push validated upstream merge",
        "N) Publish GitHub Release", "O) Outcome",
    ):
        check(f"phase present: {phase}", any(name.startswith(phase) for name in names))

    checkout = by_prefix("Checkout Arabic fork")
    checkout_ref = checkout["with"]["ref"]
    check("all dispatch modes check out selected dispatch ref",
          "github.event_name == 'workflow_dispatch'" in checkout_ref and "github.ref_name" in checkout_ref and
          "inputs.mode == 'build-only'" not in checkout_ref)

    # The workflow runs both test suites before the first network fetch/merge.
    test_step = by_prefix("Run static and real-git sync safety tests")
    fetch_step = by_prefix("A) Fetch upstream")
    merge_step = by_prefix("C) Attempt upstream merge")
    check("static and behavioral suites run in workflow", test_step is not None and
          "test-sync-config.py" in test_step["run"] and "test-sync-behavior.py" in test_step["run"])
    check("safety tests precede upstream fetch and merge",
          names.index(test_step["name"]) < names.index(fetch_step["name"]) < names.index(merge_step["name"]))
    check("workflow installs PyYAML before static test",
          by_prefix("Install sync test parser dependency") is not None and
          "pip install" in by_prefix("Install sync test parser dependency")["run"])

    # Build-only never fetches or merges upstream; it derives base version from the selected checkout.
    check("build-only skips upstream fetch", fetch_step.get("if", "") ==
          "github.event_name != 'workflow_dispatch' || inputs.mode != 'build-only'")
    check("build-only skips upstream verification",
          "steps.plan.outputs.mode != 'build-only'" in by_prefix("D) Verify official upstream is contained")["if"])
    version = by_prefix("E) Determine Release Version")["run"]
    check("build-only version derives from checked-out app version",
          'mode }}" = build-only' in version and 'app/build.gradle.kts' in version and
          'git show upstream/master:app/build.gradle.kts' in version)

    # Merge step must isolate conflicts and never record state or commit on a dry run.
    merge_run = merge_step["run"]
    check("merge uses an isolated branch", 'git switch -c "sync/upstream-$UPSTREAM_SHORT"' in merge_run)
    check("merge itself does not write state", "blockedUpstream" not in merge_run and '"$STATE_FILE"' not in merge_run)
    check("sync-only aborts clean candidate without commit",
          'if [ "$MODE" = "sync-only" ]; then' in merge_run and "git merge --abort" in merge_run and
          'git commit -m "chore(upstream)' in merge_run)
    record = by_prefix("C1) Record blocked upstream state")
    check("blocked state recording is a separate production auto/master step",
          record is not None and "steps.plan.outputs.mode == 'auto'" in record["if"] and
          "github.ref == 'refs/heads/master'" in record["if"] and "success()" in record["if"] and
          'blockedUpstream' in record["run"])

    gate = by_prefix("C2) Gate build/release")
    check("build gate is fail closed after prior failures", gate is not None and gate.get("if") == "success()")
    check("gate blocks sync-only and unresolved auto even when forced",
          'MODE" = sync-only' in gate["run"] and 'BLOCKED" = true' in gate["run"] and
          'elif [ "$BLOCKED" = true ]; then\n  # Never build or publish an unresolved automatic sync, even when forced.\n  DO_BUILD=false' in gate["run"] and
          "steps.plan.outputs.upstream_blocked == 'true'" in gate.get("env", {}).get("BLOCKED", "") and
          "upstream_blocked=$BLOCKED_CURRENT" in by_prefix("B) Decide phase")["run"])
    check("CI-only push is excluded from sync and build planning",
          'IS_PUSH" = true ] && [ "$APP_CHANGED" != true' in by_prefix("B) Decide phase")["run"] and
          'CI/docs-only push: skip sync, build, and release.' in by_prefix("B) Decide phase")["run"])
    fetch_run = fetch_step["run"]
    plan_run = by_prefix("B) Decide phase")["run"]
    classifier_text = (ROOT / ".github/scripts/classify-app-changes.py").read_text()
    check("upstream app-change classification uses merge-base and shared path policy",
          'git merge-base upstream/master HEAD' in fetch_run and
          'classify-app-changes.py "$MERGE_BASE" "$UPSTREAM_SHA"' in fetch_run and
          'has_upstream_app_changes=$UPSTREAM_APP_CHANGED' in fetch_run and
          'path.startswith(b".github/")' in classifier_text and
          'path.startswith(b"docs/")' in classifier_text and 'path.endswith(b".md")' in classifier_text and
          by_prefix("B) Decide phase")["env"].get("FETCH_HAS_UPSTREAM_APP_CHANGES") ==
          "${{ steps.fetch_upstream.outputs.has_upstream_app_changes }}")
    check("auto sync skips upstream CI/docs-only unless force_sync is explicit",
          'UPSTREAM_APP_CHANGED="${FETCH_HAS_UPSTREAM_APP_CHANGES:-false}"' in plan_run and
          '[ "$UPSTREAM_APP_CHANGED" != true ] && [ "$FORCE_SYNC" != true' in plan_run and
          "Upstream changes are CI/docs-only; skip automatic sync and build." in plan_run)
    check("upstream and push filters use the fail-closed real-Git classifier, not grep -q",
          "classify-app-changes.py" in fetch_run and "classify-app-changes.py" in plan_run and
          "--no-renames" in plan_run and "grep -q" not in fetch_run + plan_run)
    check("push classifier rejects unavailable before/after commits",
          'Cannot classify push: AFTER commit is unavailable; failing closed.' in plan_run and
          'Cannot classify push: BEFORE commit is unavailable; failing closed.' in plan_run)

    pr_path = ROOT / ".github/workflows/pr-python-tests.yml"
    pr_text = pr_path.read_text()
    pr_wf = yaml.safe_load(pr_text)
    pr_triggers = pr_wf.get(True, pr_wf.get("on", {}))
    pr_steps = pr_wf["jobs"]["python-tests"]["steps"]
    pr_runs = "\n".join(s.get("run", "") for s in pr_steps)
    pr_checkout = next((s for s in pr_steps if s.get("uses", "").startswith("actions/checkout@")), {})
    check("PR Python workflow triggers only for PRs to master and manual dispatch",
          pr_triggers.get("pull_request", {}).get("branches") == ["master"] and
          "workflow_dispatch" in pr_triggers)
    check("PR Python workflow has read-only permissions and no persisted checkout token",
          pr_wf.get("permissions") == {"contents": "read"} and
          pr_checkout.get("with", {}).get("persist-credentials") is False)
    check("PR Python workflow runs existing Python tests only (no APK/release/push actions)",
          all(cmd in pr_runs for cmd in (
              "test-sync-config.py", "test-sync-behavior.py", "read-app-version.py --self-test",
              "verify-apk-upgrade_data.py self-test")) and
          not any(forbidden in pr_text.lower() for forbidden in
                  ("gradle", "apksigner", "upload-artifact", "gh release", "git push", "secrets.")))

    # Normal state/merge pushes are success-only; the third push is intentionally failure-only.
    for prefix in ("C1) Record blocked upstream state", "M) Push validated upstream merge"):
        push_step = by_prefix(prefix)
        if push_step and "git push" in push_step["run"]:
            check(f"{prefix} push has shell auto/master/success guards",
                  'test "$MODE" = auto' in push_step["run"] and
                  'test "$WORKFLOW_REF" = refs/heads/master' in push_step["run"] and
                  'test "$JOB_STATUS" = success' in push_step["run"])
    push = by_prefix("M) Push validated upstream merge")
    check("validated merge push is expression-guarded by success/auto/master",
          push is not None and "success()" in push["if"] and "steps.plan.outputs.mode == 'auto'" in push["if"] and
          "github.ref == 'refs/heads/master'" in push["if"])
    failure_record = by_prefix("P3) Record validation failure")
    check("P3 state-only push is intentionally failure-only and production guarded",
          failure_record is not None and "failure()" in failure_record["if"] and
          "steps.merge.outputs.merged_upstream == 'true'" in failure_record["if"] and
          "steps.gate.outputs.do_build == 'true'" in failure_record["if"] and
          "steps.plan.outputs.mode == 'auto'" in failure_record["if"] and
          "github.ref == 'refs/heads/master'" in failure_record["if"] and
          'test "$JOB_STATUS" = failure' in failure_record["run"] and
          'test "$MERGED_UPSTREAM" = true' in failure_record["run"] and
          'test "$DO_BUILD" = true' in failure_record["run"])
    failure_run = failure_record["run"] if failure_record else ""
    check("P3 fetches origin/master, skips already-contained upstream, and uses an isolated worktree",
          "git fetch origin master:refs/remotes/origin/master" in failure_run and
          'git merge-base --is-ancestor "$UPSTREAM_SHA" "$MASTER_SHA"' in failure_run and
          'git worktree add --detach "$STATE_WT" "$MASTER_SHA"' in failure_run)
    check("P3 state records validation stage without logs and stages only the state file",
          'state["blockedStage"] = "validation"' in failure_run and
          'state["blockedFiles"] = []' in failure_run and 'state["reason"]' in failure_run and
          'git -C "$STATE_WT" add -- "$STATE_FILE"' in failure_run and
          'test "$STAGED" = "$STATE_FILE"' in failure_run and
          'git -C "$STATE_WT" push origin HEAD:master' in failure_run and
          'git push origin HEAD:master' not in failure_run)

    # Every build/release action is gated; publication remains explicitly manual.
    build_steps = [s for s in steps if re.match(r"^([E-N])\)", s.get("name", ""))]
    unconditional = [s.get("name") for s in build_steps
                     if "steps.gate.outputs.do_build == 'true'" not in (s.get("if") or "")]
    check("all build/release steps gated by do_build", not unconditional, str(unconditional))
    check("sync-only cannot build or publish", 'MODE" = sync-only' in gate["run"] and
          'DO_BUILD=false' in gate["run"] and 'DO_PUBLISH=false' in gate["run"])
    release = by_prefix("N) Publish GitHub Release")
    check("publish_release defaults false", triggers["workflow_dispatch"]["inputs"]["publish_release"].get("default") is False)
    check("release requires explicit dispatch on master",
          release is not None and "steps.gate.outputs.publish_release == 'true'" in release["if"] and
          "INPUT_PUBLISH_RELEASE: ${{ inputs.publish_release }}" in text and
          "github.ref == 'refs/heads/master'" in text)
    check("release refuses existing tags and has no clobber fallback",
          "gh release create" in text and "--clobber" not in text and "Refusing to reuse existing tag" in text)

    check("upgrade baseline is v3.0.0.178", "gh release download v3.0.0.178" in text)
    check("Arabic hooks and conversation protections remain", all(x in text for x in
          ("AppLanguage.ARABIC", "AppLocale.wrap", "getConversationAssistant", "resolveChatModel")))
    check("APK artifact upload remains", "actions/upload-artifact@v4" in text)
    check("APK signature, app identity, and version are checked", all(x in text for x in
          ("apksigner verify --print-certs", "aapt dump badging", "EXPECTED_VERSION_NAME",
           "EXPECTED_VERSION_CODE", cert, "me.rerere.rikkahub.arabic.dev")))
    check("unit/lint/Room tests remain configured", all(x in text for x in
          (":app:testDebugUnitTest", ":app:lintRelease", "reactivecircus/android-emulator-runner@v2",
           "api-level: 35", "arch: x86_64", "/dev/kvm", ":app:connectedDebugAndroidTest")))
    check("test/lint reports upload always", by_prefix("Upload test and lint reports") is not None and
          by_prefix("Upload test and lint reports").get("if") == "always()")
    check("library unit tests remain covered", all(f":{module}:test" in text for module in
          ("ai", "common", "document", "highlight", "material3", "mediagen", "oauth", "search", "speech", "ui", "web", "workspace")))
    check("signed in-place upgrade verification remains", "verify-apk-upgrade.sh" in text and
          "Fetch previous Arabic APK" in text and "upgrade-verification/" in text)

    check("conflict rules are documented in workflow env", bool(env.get("OURS_ON_CONFLICT")) and
          bool(env.get("PROTECTED_PATTERNS")))
    check("no destructive git commands", not any(bad in text for bad in
          ("reset --hard", "push --force", "push -f")))
    state = json.loads(STATE.read_text())
    check("state file valid JSON", "blockedUpstream" in state)
    reviewed_sha = state.get("lastReviewedUpstream", "")
    check("reviewed upstream SHA is a full commit contained in the checkout",
          bool(re.fullmatch(r"[0-9a-f]{40}", reviewed_sha)) and
          subprocess.run(["git", "merge-base", "--is-ancestor", reviewed_sha, "HEAD"],
                         cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0)

    docs = (ROOT / ".github/SYNC.md").read_text()
    check("docs describe sync-only no commit/state/push", "لا ينشئ commit محليًا" in docs and
          "لا يكتب ملف الحالة" in docs and "لا يدفع" in docs)
    check("docs describe selected dispatch ref and build-only independence",
          "كل وضع" in docs and "دون جلب upstream" in docs)

    print()
    if failures:
        print(f"{len(failures)} check(s) FAILED: {failures}")
        raise SystemExit(1)
    print("All sync-config checks passed.")


if __name__ == "__main__":
    main()

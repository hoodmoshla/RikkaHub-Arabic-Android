#!/usr/bin/env python3
"""Static checks for the Arabic upstream-sync workflow.

Run: python3 .github/scripts/test-sync-config.py

These are *config* checks (no network, no Android build): they lock the invariants that
must never regress in the sync/release pipeline, and they exercise the documented conflict
classification rule against a representative set of paths.
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

    # 1. YAML parses and has the expected structure.
    check("workflow parses", isinstance(wf, dict))
    check("triggers on push/schedule/workflow_dispatch",
          all(k in wf.get(True, wf.get("on", {})) for k in ("push", "schedule", "workflow_dispatch")))

    steps = wf["jobs"]["pipeline"]["steps"]
    names = [s.get("name", "") for s in steps]

    # 2. Phase isolation: fetch, decide, merge, verify, build, push, release.
    for phase in ("A) Fetch upstream", "B) Decide phase", "C) Attempt upstream merge",
                  "C2) Gate build/release",
                  "D) Verify official upstream is contained", "E) Determine Release Version",
                  "F) Validate Arabic resource completeness", "G) Validate Arabic integration hooks",
                  "I) Run JVM unit tests", "J) Restore signing key", "K) Build signed universal APK",
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

    # 4. Push/release happen only after the build+verify phases.
    idx = {n: i for i, n in enumerate(names)}
    check("push after build+verify",
          idx["M) Push validated upstream merge"] > idx["K) Build signed universal APK"] > idx["I) Run JVM unit tests"])
    check("release after push",
          idx["N) Publish GitHub Release"] > idx["M) Push validated upstream merge"])

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
    check("outcome distinguishes blocked vs built",
          outcome is not None and "Sync blocked" in outcome.get("run", "") and "built and released" in outcome.get("run", ""))
    check("outcome reports job failure honestly",
          outcome is not None and 'job.status' in outcome.get("run", "") and "FAILED" in outcome.get("run", ""))

    # 4c. Previously-existing protections must still be present.
    check("Arabic hooks validation present", "AppLanguage.ARABIC" in text and "AppLocale.wrap" in text)
    check("conversation-scoped validation present",
          "getConversationAssistant" in text and "resolveChatModel" in text)
    check("APK artifact upload present", "actions/upload-artifact@v4" in text)
    check("release publish + clobber present", "gh release create" in text and "--clobber" in text)

    # 5. Protected rules & invariants.
    check("ours-on-conflict rule present", bool(env.get("OURS_ON_CONFLICT")))
    check("protected patterns present", bool(env.get("PROTECTED_PATTERNS")))
    check("signing SHA locked", "27:32:61:44:BD:EE:8E:EF:A9:CD:7A:39:E8:0C:3A:53:22:E8:33:24:19:B8:6D:4B:13:CF:EC:BB:E9:EB:7B:73" in text)
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

#!/usr/bin/env python3
"""Behavioral tests for the real shell steps in sync-and-release.yml.

Each tested shell block is extracted verbatim from the parsed workflow and run in
throwaway repositories with bare origin/upstream remotes. No behavior is reimplemented.
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/sync-and-release.yml"
STEPS = yaml.safe_load(WORKFLOW.read_text())["jobs"]["pipeline"]["steps"]


def step(prefix: str):
    matches = [s for s in STEPS if s.get("name", "").startswith(prefix)]
    assert len(matches) == 1, f"expected exactly one workflow step starting {prefix!r}"
    return matches[0]


def git(cwd: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=cwd, text=True, stderr=subprocess.DEVNULL).strip()


def is_ancestor(cwd: Path, commit: str, ref: str) -> bool:
    return subprocess.run(
        ["git", "-C", str(cwd), "merge-base", "--is-ancestor", commit, ref],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


def run_step(
    prefix: str, cwd: Path, env: dict[str, str], *, expect_failure: bool = False
) -> tuple[subprocess.CompletedProcess, dict[str, str]]:
    block = step(prefix)
    with tempfile.TemporaryDirectory(prefix="sync-step-") as td:
        outputs = Path(td) / "outputs"
        summary = Path(td) / "summary"
        outputs.touch()
        summary.touch()
        runtime_env = os.environ.copy()
        runtime_env.update(env)
        runtime_env["GITHUB_OUTPUT"] = str(outputs)
        runtime_env["GITHUB_STEP_SUMMARY"] = str(summary)
        result = subprocess.run(
            ["bash", "--noprofile", "--norc", "-euo", "pipefail", "-c", block["run"]],
            cwd=cwd,
            env=runtime_env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        parsed = {}
        for line in outputs.read_text().splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                parsed[key] = value
        if result.returncode and not expect_failure:
            raise AssertionError(f"workflow step {prefix!r} failed ({result.returncode}):\n{result.stdout}")
        if not result.returncode and expect_failure:
            raise AssertionError(f"workflow step {prefix!r} unexpectedly succeeded:\n{result.stdout}")
        return result, parsed


def fixture(root: Path, conflict: bool = False) -> tuple[Path, Path, Path, str]:
    seed = root / "seed"
    seed.mkdir()
    git(seed, "init", "-b", "master")
    git(seed, "config", "user.name", "Sync test")
    git(seed, "config", "user.email", "sync-test@example.invalid")
    (seed / "shared.txt").write_text("common\n")
    (seed / ".gitignore").write_text("sync-report/\n")
    git(seed, "add", ".")
    git(seed, "commit", "-m", "common base")
    base_sha = git(seed, "rev-parse", "HEAD")

    origin_bare = root / "origin.git"
    upstream_bare = root / "upstream.git"
    subprocess.run(["git", "clone", "--bare", str(seed), str(origin_bare)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["git", "clone", "--bare", str(seed), str(upstream_bare)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    work = root / "work"
    upstream_work = root / "upstream-work"
    subprocess.run(["git", "clone", str(origin_bare), str(work)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["git", "clone", str(upstream_bare), str(upstream_work)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for repo in (work, upstream_work):
        git(repo, "config", "user.name", "Sync test")
        git(repo, "config", "user.email", "sync-test@example.invalid")
    git(work, "remote", "set-url", "origin", str(origin_bare))
    git(upstream_work, "remote", "set-url", "origin", str(upstream_bare))

    if conflict:
        (work / "shared.txt").write_text("arabic fork version\n")
    else:
        (work / "arabic.txt").write_text("fork-specific\n")
    git(work, "add", ".")
    git(work, "commit", "-m", "Arabic fork change")
    git(work, "push", "origin", "master")

    if conflict:
        (upstream_work / "shared.txt").write_text("official upstream version\n")
    else:
        (upstream_work / "official.txt").write_text("official upstream change\n")
    git(upstream_work, "add", ".")
    git(upstream_work, "commit", "-m", "upstream change")
    git(upstream_work, "push", "origin", "master")
    git(work, "remote", "add", "origin", str(origin_bare)) if git(work, "remote", "get-url", "origin") != str(origin_bare) else None
    return work, origin_bare, upstream_bare, base_sha


def base_env(work: Path, upstream: Path, mode: str = "auto", event: str = "workflow_dispatch") -> dict[str, str]:
    return {
        "UPSTREAM_REPO": str(upstream),
        "STATE_FILE": ".github/upstream-sync.json",
        "OURS_ON_CONFLICT": r"^\.github/workflows/",
        "PROTECTED_PATTERNS": r"shared\.txt",
        "EVENT_NAME": event,
        "INPUT_MODE": mode,
        "INPUT_FORCE_BUILD": "false",
        "INPUT_FORCE_SYNC": "false",
        "FETCH_HAS_NEW_UPSTREAM": "false",
        "FETCH_UPSTREAM_SHA": "",
        "PUSH_BEFORE": "",
        "PUSH_AFTER": "",
        "WORKFLOW_REF": "refs/heads/master",
        "GITHUB_REF_NAME": "master",
        "GITHUB_JOB_STATUS": "success",
    }


def fetch(work: Path, env: dict[str, str]) -> dict[str, str]:
    _, output = run_step("A) Fetch upstream", work, env)
    env["FETCH_HAS_NEW_UPSTREAM"] = output["has_new_upstream"]
    env["FETCH_UPSTREAM_SHA"] = output["upstream_sha"]
    return output


def plan(work: Path, env: dict[str, str]) -> dict[str, str]:
    _, output = run_step("B) Decide phase", work, env)
    env["PLAN_BUILD"] = output["should_build"]
    env["PLAN_APP_CHANGED"] = output["app_changed"]
    env["PLAN_BLOCKED"] = output["upstream_blocked"]
    return output


def merge(work: Path, env: dict[str, str]) -> dict[str, str]:
    env["UPSTREAM_SHA"] = env["FETCH_UPSTREAM_SHA"]
    env["MODE"] = env["INPUT_MODE"]
    _, output = run_step("C) Attempt upstream merge", work, env)
    return output


def gate(work: Path, env: dict[str, str], merge_output: dict[str, str] | None = None) -> dict[str, str]:
    merge_output = merge_output or {}
    env.update({
        "BLOCKED": "true" if merge_output.get("blocked") == "true" or env.get("PLAN_BLOCKED") == "true" else "false",
        "MERGED": merge_output.get("merged_upstream", "false"),
        "CURRENT": env.get("PLAN_BUILD", "false"),
        "MODE": env["INPUT_MODE"],
        "FORCE_BUILD": env["INPUT_FORCE_BUILD"],
        "APP_CHANGED": env.get("PLAN_APP_CHANGED", "false"),
        "INPUT_PUBLISH_RELEASE": env.get("INPUT_PUBLISH_RELEASE", "false"),
    })
    _, output = run_step("C2) Gate build/release", work, env)
    return output


def push_merge(work: Path, env: dict[str, str]) -> None:
    env.update({"MODE": "auto", "WORKFLOW_REF": "refs/heads/master", "JOB_STATUS": "success"})
    run_step("M) Push validated upstream merge", work, env)


def test_clean_auto_and_push(root: Path) -> None:
    work, origin, upstream, _ = fixture(root)
    env = base_env(work, upstream)
    fetch(work, env)
    p = plan(work, env)
    assert p["should_sync"] == "true" and p["should_build"] == "false"
    m = merge(work, env)
    assert m["merged_upstream"] == "true" and m["blocked"] == "false"
    g = gate(work, env, m)
    assert g["do_build"] == "true"
    push_merge(work, env)
    assert git(work, "rev-parse", "HEAD") == git(origin, "rev-parse", "refs/heads/master")
    assert git(work, "merge-base", "--is-ancestor", env["FETCH_UPSTREAM_SHA"], "HEAD") == ""


def test_sync_only_clean_merge_is_a_true_dry_run(root: Path) -> None:
    work, origin, upstream, _ = fixture(root)
    env = base_env(work, upstream, mode="sync-only")
    fetch(work, env)
    before_head = git(work, "rev-parse", "HEAD")
    before_remote = git(origin, "rev-parse", "refs/heads/master")
    p = plan(work, env)
    assert p["should_sync"] == "true" and p["should_build"] == "false"
    m = merge(work, env)
    assert m["merged_upstream"] == "false" and m["blocked"] == "false"
    assert git(work, "rev-parse", "HEAD") == before_head, "clean sync-only must not create a local commit"
    assert not (work / env["STATE_FILE"]).exists(), "clean sync-only must not write state"
    assert git(origin, "rev-parse", "refs/heads/master") == before_remote, "clean sync-only must not push"
    g = gate(work, env, m)
    assert g["do_build"] == "false" and g["publish_release"] == "false"


def test_sync_only_conflict_is_a_true_dry_run(root: Path) -> None:
    work, origin, upstream, _ = fixture(root, conflict=True)
    env = base_env(work, upstream, mode="sync-only")
    fetch(work, env)
    before_head = git(work, "rev-parse", "HEAD")
    before_remote = git(origin, "rev-parse", "refs/heads/master")
    state = work / env["STATE_FILE"]
    state.parent.mkdir(parents=True, exist_ok=True)
    state.write_text('{"blockedUpstream":"older-sha","blockedFiles":[]}\n')
    before_state = state.read_bytes()
    p = plan(work, env)
    assert p["should_sync"] == "true" and p["should_build"] == "false"
    m = merge(work, env)
    assert m["blocked"] == "true" and m["merged_upstream"] == "false"
    assert git(work, "rev-parse", "HEAD") == before_head, "sync-only must not create a local commit"
    assert state.read_bytes() == before_state, "sync-only must not write blocked SHA state"
    assert git(origin, "rev-parse", "refs/heads/master") == before_remote, "sync-only must not push"
    g = gate(work, env, m)
    assert g["do_build"] == "false" and g["publish_release"] == "false"


def test_auto_conflict_records_state_then_new_sha_retries(root: Path) -> None:
    work, origin, upstream, _ = fixture(root, conflict=True)
    env = base_env(work, upstream)
    fetch(work, env)
    first_sha = env["FETCH_UPSTREAM_SHA"]
    p = plan(work, env)
    assert p["should_sync"] == "true"
    m = merge(work, env)
    assert m["blocked"] == "true"
    before_head = git(work, "rev-parse", "HEAD")
    env.update({"INPUT_FORCE_BUILD": "true", "INPUT_PUBLISH_RELEASE": "true"})
    plan(work, env)
    denied = gate(work, env, m)
    assert denied["do_build"] == "false" and denied["publish_release"] == "false", "fresh conflict cannot be forced into a build/release"
    env.update({"INPUT_FORCE_BUILD": "false", "INPUT_PUBLISH_RELEASE": "false"})
    # The merge itself aborts without committing. State recording is a distinct, guarded step.
    assert before_head == git(origin, "rev-parse", "refs/heads/master")
    (work / ".github").mkdir(exist_ok=True)
    env.update({
        "MODE": "auto", "WORKFLOW_REF": "refs/heads/master", "JOB_STATUS": "success",
        "UPSTREAM_SHA": first_sha,
    })
    run_step("C1) Record blocked upstream state", work, env)
    state = json.loads((work / env["STATE_FILE"]).read_text())
    assert state["blockedUpstream"] == first_sha and state["blockedFiles"] == ["shared.txt"]
    assert git(work, "rev-parse", "HEAD") == git(origin, "rev-parse", "refs/heads/master")

    # The same SHA is suppressed, while a newer SHA becomes eligible for a retry.
    same = plan(work, env)
    assert same["should_sync"] == "false" and same["upstream_blocked"] == "true"
    before = git(work, "rev-parse", "HEAD")
    (work / "app-change.txt").write_text("application change on blocked upstream\n")
    git(work, "add", "app-change.txt")
    git(work, "commit", "-m", "app change while upstream blocked")
    after = git(work, "rev-parse", "HEAD")
    git(work, "push", "origin", "HEAD:master")
    env.update({"EVENT_NAME": "push", "PUSH_BEFORE": before, "PUSH_AFTER": after})
    app_push = plan(work, env)
    assert app_push["app_changed"] == "true" and app_push["should_build"] == "true"
    denied = gate(work, env)
    assert denied["do_build"] == "false", "persistently blocked auto must deny app-triggered build"
    env["INPUT_FORCE_BUILD"] = "true"
    forced_plan = plan(work, env)
    assert forced_plan["should_build"] == "true"
    forced_gate = gate(work, env)
    assert forced_gate["do_build"] == "false", "force_build cannot bypass a recorded conflict"
    env["INPUT_FORCE_BUILD"] = "false"
    env["EVENT_NAME"] = "workflow_dispatch"
    upstream_work = root / "upstream-work"
    (upstream_work / "official-followup.txt").write_text("new official commit\n")
    git(upstream_work, "add", ".")
    git(upstream_work, "commit", "-m", "new upstream SHA")
    git(upstream_work, "push", "origin", "master")
    fetch(work, env)
    assert env["FETCH_UPSTREAM_SHA"] != first_sha
    newer = plan(work, env)
    assert newer["should_sync"] == "true" and newer["upstream_blocked"] == "false"


def test_validation_failure_pushes_only_state_and_suppresses_same_sha(root: Path) -> None:
    work, origin, upstream, _ = fixture(root)
    env = base_env(work, upstream)
    fetch(work, env)
    failed_sha = env["FETCH_UPSTREAM_SHA"]
    p = plan(work, env)
    assert p["should_sync"] == "true" and p["should_build"] == "false"
    m = merge(work, env)
    assert m["merged_upstream"] == "true" and m["blocked"] == "false"
    g = gate(work, env, m)
    assert g["do_build"] == "true"
    candidate_head = git(work, "rev-parse", "HEAD")
    origin_before = git(origin, "rev-parse", "refs/heads/master")
    assert is_ancestor(work, failed_sha, candidate_head)
    assert not is_ancestor(origin, failed_sha, origin_before)

    env.update({
        "MODE": "auto", "WORKFLOW_REF": "refs/heads/master", "JOB_STATUS": "failure",
        "MERGED_UPSTREAM": "true", "DO_BUILD": "true", "UPSTREAM_SHA": failed_sha,
    })
    run_step("P3) Record validation failure", work, env)

    pushed_head = git(origin, "rev-parse", "refs/heads/master")
    assert pushed_head != origin_before and pushed_head != candidate_head
    changed_paths = git(origin, "diff-tree", "--no-commit-id", "--name-only", "-r", pushed_head).splitlines()
    assert changed_paths == [".github/upstream-sync.json"], f"unexpected pushed paths: {changed_paths}"
    assert not is_ancestor(work, failed_sha, pushed_head), \
        "failed upstream SHA must not become an ancestor of origin/master"
    original_fork_file = subprocess.check_output(
        ["git", "--git-dir", str(origin), "show", f"{pushed_head}:arabic.txt"], text=True
    )
    assert original_fork_file == "fork-specific\n", "candidate app changes must not replace the fork version"
    assert subprocess.run(
        ["git", "--git-dir", str(origin), "cat-file", "-e", f"{pushed_head}:official.txt"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode != 0

    state_blob = subprocess.check_output(
        ["git", "--git-dir", str(origin), "show", f"{pushed_head}:.github/upstream-sync.json"], text=True
    )
    state = json.loads(state_blob)
    assert state["blockedUpstream"] == failed_sha
    assert state["blockedStage"] == "validation" and state["blockedFiles"] == []
    assert state["reason"] and "::" not in state["reason"]
    assert git(work, "rev-parse", "HEAD") == candidate_head, "the failed merge candidate must remain local"

    # Model the next scheduled checkout reading the newly pushed state.
    local_state = work / env["STATE_FILE"]
    local_state.parent.mkdir(parents=True, exist_ok=True)
    local_state.write_text(state_blob)
    same = plan(work, env)
    assert same["should_sync"] == "false" and same["upstream_blocked"] == "true"

    upstream_work = root / "upstream-work"
    (upstream_work / "official-followup.txt").write_text("new upstream SHA\n")
    git(upstream_work, "add", "official-followup.txt")
    git(upstream_work, "commit", "-m", "new upstream SHA")
    git(upstream_work, "push", "origin", "master")
    fetch(work, env)
    assert env["FETCH_UPSTREAM_SHA"] != failed_sha
    newer = plan(work, env)
    assert newer["should_sync"] == "true" and newer["upstream_blocked"] == "false"


def test_validation_failure_recorder_refuses_out_of_scope_before_writes(root: Path) -> None:
    work, origin, upstream, _ = fixture(root)
    env = base_env(work, upstream)
    env.update({
        "MODE": "auto", "WORKFLOW_REF": "refs/heads/master", "JOB_STATUS": "failure",
        "MERGED_UPSTREAM": "true", "DO_BUILD": "true", "UPSTREAM_SHA": "a" * 40,
    })
    cases = {
        "sync-only dry run": {"MODE": "sync-only"},
        "build-only": {"MODE": "build-only"},
        "review branch": {"WORKFLOW_REF": "refs/heads/review/test"},
        "successful job": {"JOB_STATUS": "success"},
    }
    for label, overrides in cases.items():
        attempt = {**env, **overrides}
        before_head = git(work, "rev-parse", "HEAD")
        before_remote = git(origin, "rev-parse", "refs/heads/master")
        before_status = git(work, "status", "--porcelain")
        before_worktrees = git(work, "worktree", "list", "--porcelain")
        run_step("P3) Record validation failure", work, attempt, expect_failure=True)
        assert git(work, "rev-parse", "HEAD") == before_head, f"{label} changed HEAD"
        assert git(origin, "rev-parse", "refs/heads/master") == before_remote, f"{label} pushed"
        assert git(work, "status", "--porcelain") == before_status, f"{label} wrote files"
        assert git(work, "worktree", "list", "--porcelain") == before_worktrees, f"{label} created a worktree"
        assert not (work / env["STATE_FILE"]).exists(), f"{label} wrote blocked state"


def test_build_only_does_not_fetch_or_need_upstream(root: Path) -> None:
    work, origin, upstream, _ = fixture(root)
    env = base_env(work, upstream, mode="build-only")
    before = git(work, "rev-parse", "HEAD")
    # Do not execute A: this is what the workflow-level conditional enforces.
    p = plan(work, env)
    assert p["should_sync"] == "false" and p["should_build"] == "true"
    g = gate(work, env)
    assert g["do_build"] == "true"
    assert git(work, "rev-parse", "HEAD") == before
    assert not (work / env["STATE_FILE"]).exists()
    assert git(origin, "rev-parse", "refs/heads/master") == before
    assert not (work / ".git/refs/remotes/upstream/master").exists()


def test_ci_only_push_ignores_available_upstream(root: Path) -> None:
    work, origin, upstream, _ = fixture(root)
    before = git(work, "rev-parse", "HEAD")
    (work / ".github").mkdir()
    (work / ".github/ci-only.yml").write_text("name: CI-only\n")
    git(work, "add", ".github/ci-only.yml")
    git(work, "commit", "-m", "CI-only push")
    after = git(work, "rev-parse", "HEAD")
    git(work, "push", "origin", "master")
    env = base_env(work, upstream, event="push")
    env.update({"PUSH_BEFORE": before, "PUSH_AFTER": after})
    fetch(work, env)
    assert env["FETCH_HAS_NEW_UPSTREAM"] == "true"
    p = plan(work, env)
    assert p["should_sync"] == "false" and p["should_build"] == "false"
    g = gate(work, env)
    assert g["do_build"] == "false" and g["publish_release"] == "false"
    assert git(work, "rev-parse", "HEAD") == git(origin, "rev-parse", "refs/heads/master")


def main() -> None:
    tests = [
        ("clean auto merge is built and pushed only after success", test_clean_auto_and_push),
        ("sync-only clean merge leaves state, local commit, and remotes untouched", test_sync_only_clean_merge_is_a_true_dry_run),
        ("sync-only conflict leaves state, local commit, and remotes untouched", test_sync_only_conflict_is_a_true_dry_run),
        ("auto conflict records blocked SHA separately; newer SHA retries", test_auto_conflict_records_state_then_new_sha_retries),
        ("validation failure pushes only blocked state; same SHA suppressed and newer SHA retries", test_validation_failure_pushes_only_state_and_suppresses_same_sha),
        ("validation failure recorder refuses dry-run/build-only/review/success before writes", test_validation_failure_recorder_refuses_out_of_scope_before_writes),
        ("build-only skips fetch and upstream dependence", test_build_only_does_not_fetch_or_need_upstream),
        ("CI-only push with available upstream does not sync/build/release", test_ci_only_push_ignores_available_upstream),
    ]
    failures = []
    with tempfile.TemporaryDirectory(prefix="rikka-sync-behavior-") as td:
        root = Path(td)
        for name, test in tests:
            try:
                case = root / str(len(list(root.iterdir())))
                case.mkdir()
                test(case)
                print(f"PASS  {name}")
            except Exception as exc:  # show all independent scenario failures
                print(f"FAIL  {name}: {exc}")
                failures.append(name)
    if failures:
        raise SystemExit(f"{len(failures)} behavioral test(s) failed: {failures}")
    print("All real-workflow behavioral tests passed.")


if __name__ == "__main__":
    main()

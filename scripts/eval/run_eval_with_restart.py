#!/usr/bin/env python3
"""Start app (if needed), publish configs, restart, run eval."""
from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BASE = "http://localhost:8080"
READY_URL = f"{BASE}/qa/assistant/runtime-summary"
LOG = REPO / "data/qa_logs/spring_run.log"

CONFIGS = {
    "business-rules": "src/main/resources/qa/business-rules.json",
    "retrieval-catalog": "src/main/resources/qa/retrieval-catalog.json",
    "cdc-graph-sync": "src/main/resources/qa/cdc-graph-sync.json",
    "evidence-schemas": "src/main/resources/qa/evidence-schemas.json",
    "answer-output-contracts": "src/main/resources/qa/answer-output-contracts.json",
    "enterprise-lexicon": "src/main/resources/qa/enterprise-lexicon.json",
    "graph-company-facets": "src/main/resources/qa/graph-company-facets.json",
    "sql-role-columns": "src/main/resources/qa/sql-role-columns.json",
    "enterprise-canonical-facts": "src/main/resources/qa/enterprise-canonical-facts.json",
}


def is_ready() -> bool:
    try:
        with urllib.request.urlopen(READY_URL, timeout=8) as r:
            return r.status == 200
    except Exception:
        return False


def wait_ready(timeout_sec: int = 180) -> bool:
    for i in range(timeout_sec // 5):
        if is_ready():
            print(f"app ready after ~{i * 5}s")
            return True
        if LOG.exists():
            tail = LOG.read_text(encoding="utf-8", errors="replace")[-6000:]
            if "BUILD FAILURE" in tail or "Application run failed" in tail:
                print("startup failed:\n" + "\n".join(tail.splitlines()[-20:]))
                return False
        time.sleep(5)
    return False


def start_app() -> subprocess.Popen:
    LOG.parent.mkdir(parents=True, exist_ok=True)
    mvn = REPO / "mvnw.cmd"
    with LOG.open("w", encoding="utf-8") as out, (LOG.with_suffix(".log.err")).open("w", encoding="utf-8") as err:
        proc = subprocess.Popen(
            [str(mvn), "-q", "spring-boot:run", "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8"],
            cwd=REPO,
            stdout=out,
            stderr=err,
        )
    print(f"started mvn pid={proc.pid}")
    return proc


def stop_app(proc: subprocess.Popen | None) -> None:
    if proc and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
    subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-Command",
            "Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | "
            "ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }",
        ],
        cwd=REPO,
        timeout=60,
    )
    time.sleep(3)


def publish_configs() -> int:
    failed = 0
    for key, rel in CONFIGS.items():
        content = (REPO / rel).read_text(encoding="utf-8")
        payload = json.dumps({"configKey": key, "contentJson": content}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            f"{BASE}/qa/admin/config/publish",
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            ok = body.get("ok")
            print(f"publish {key}: {'OK' if ok else body}")
            if not ok:
                failed += 1
        except urllib.error.HTTPError as ex:
            print(f"publish {key} HTTP {ex.code}: {ex.read().decode('utf-8', errors='replace')}")
            failed += 1
    return failed


def run_eval() -> int:
    proc = subprocess.run(
        [sys.executable, "scripts/eval/run_qa_eval.py", "--fail-under", "100"],
        cwd=REPO,
    )
    return proc.returncode


def main() -> int:
    proc = None
    try:
        if not is_ready():
            proc = start_app()
            if not wait_ready():
                return 5
        else:
            print("app already running")

        if publish_configs():
            return 5

        print("restarting to reload Spring beans...")
        stop_app(proc)
        proc = start_app()
        if not wait_ready(240):
            return 5

        return run_eval()
    finally:
        pass  # leave app running for user


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""
scripts/simulate_webhook.py
---------------------------
Simulates a real GitHub Webhook event (pull_request or push) with HMAC-SHA256
signature verification and delivery-ID headers.

Use this script to test the Sentinel-RAG Java Control Plane and full evaluation
orchestration lifecycle locally without needing a public IP, ngrok tunnel, or
actual GitHub webhook configuration.

Usage:
    python scripts/simulate_webhook.py
    python scripts/simulate_webhook.py --event push --commit abc1234
    python scripts/simulate_webhook.py --secret my-secret --pr 99
"""

import argparse
import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def load_env_secret() -> str:
    env_file = Path(__file__).resolve().parents[1] / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if line.startswith("GITHUB_WEBHOOK_SECRET="):
                return line.split("=", 1)[1].strip("'\"")
    return os.environ.get("GITHUB_WEBHOOK_SECRET", "development-secret")


def main() -> None:
    parser = argparse.ArgumentParser(description="Simulate GitHub Webhook for Sentinel-RAG")
    parser.add_argument("--url", default="http://localhost:8080/webhooks/github", help="Java webhook endpoint URL")
    parser.add_argument("--secret", default=load_env_secret(), help="HMAC webhook secret key")
    parser.add_argument("--event", choices=["pull_request", "push"], default="pull_request", help="GitHub event type")
    parser.add_argument("--repo", default="GodXSpell/SentinentRAG", help="Repository full name (org/repo)")
    parser.add_argument("--commit", default="d8f2b7a9e1c456f0123456789abcdef012345678", help="Commit SHA to evaluate")
    parser.add_argument("--pr", type=int, default=42, help="Pull request number")
    parser.add_argument("--branch", default="feature/rag-eval", help="Git branch name")
    args = parser.parse_args()

    delivery_id = str(uuid.uuid4())

    if args.event == "pull_request":
        payload = {
            "action": "opened",
            "number": args.pr,
            "pull_request": {
                "number": args.pr,
                "head": {
                    "sha": args.commit,
                    "ref": f"refs/heads/{args.branch}",
                    "repo": {
                        "full_name": args.repo,
                        "fork": False,
                    },
                },
                "base": {
                    "repo": {
                        "full_name": args.repo,
                    }
                },
            },
        }
    else:  # push
        payload = {
            "ref": f"refs/heads/{args.branch}",
            "after": args.commit,
            "repository": {
                "full_name": args.repo,
            },
        }

    raw_body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    signature = "sha256=" + hmac.new(args.secret.encode("utf-8"), raw_body, hashlib.sha256).hexdigest()

    headers = {
        "Content-Type": "application/json",
        "X-GitHub-Event": args.event,
        "X-Hub-Signature-256": signature,
        "X-GitHub-Delivery": delivery_id,
        "User-Agent": "GitHub-Hookshot/simulate-cli",
    }

    print("=" * 65)
    print("Sentinel-RAG Webhook Simulation")
    print("=" * 65)
    print(f"Target URL:    {args.url}")
    print(f"Event:         {args.event}")
    print(f"Delivery ID:   {delivery_id}")
    print(f"Repository:    {args.repo}")
    print(f"Commit SHA:    {args.commit[:12]}...")
    if args.event == "pull_request":
        print(f"PR Number:     #{args.pr}")
    print(f"Signature:     {signature[:20]}...")
    print("-" * 65)

    req = urllib.request.Request(args.url, data=raw_body, headers=headers, method="POST")

    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            print(f"✅ Webhook delivered successfully! HTTP Status: {resp.status} {resp.reason}")
            print("\nCheck Java service console to watch the full evaluation pipeline run:")
            print("  1. HMAC signature verified")
            print("  2. Delivery-ID recorded in PostgreSQL")
            print("  3. CiBuildRun created (status = RUNNING)")
            print("  4. Stable canary sample computed (seed: suiteId#prNumber)")
            print("  5. Semantic cache lookup in eval_cache")
            print("  6. gRPC PrepareIndex & RunEvaluation sent to Python plane (port 50051)")
            print("  7. QualityGate thresholds evaluated (Faithfulness, Precision, Refusal, Latency)")
            print("  8. Commit status updated")
    except urllib.error.HTTPError as e:
        print(f"❌ HTTP Error: {e.code} {e.reason}")
        error_body = e.read().decode("utf-8", errors="replace")
        if error_body:
            print(f"Response: {error_body}")
        if e.code == 401:
            print("\nHint: Signature verification failed. Ensure --secret matches GITHUB_WEBHOOK_SECRET in Java.")
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"❌ Connection Failed: {e.reason}")
        print("\nEnsure the Java Control Plane is running on http://localhost:8080:")
        print("  cd sentinelRag_java && mvn spring-boot:run")
        print("Or via Docker Compose:")
        print("  docker compose up -d")
        sys.exit(1)


if __name__ == "__main__":
    main()


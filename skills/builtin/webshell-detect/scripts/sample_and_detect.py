#!/usr/bin/env python3
"""
Step 1: Randomly sample up to 1000 files from each category (benign/webshell × jsp/php/asp).
Step 2: Run full BFAD detection pipeline on each sampled file.
Step 3: Output JSON results for report generation.

Usage:
    python sample_and_detect.py
"""

import json
import os
import random
import sys
from pathlib import Path
from datetime import datetime

# Add skill scripts to path
SKILL_DIR = Path(__file__).parent.parent
sys.path.insert(0, str(SKILL_DIR / "scripts"))

from detect import detect_file
from scan_critical import PHP_FUNCTIONS, PHP_INPUTS, JSP_PATTERNS, JSP_INPUTS, ASP_PATTERNS, ASP_INPUTS

BASE = Path("F:/asainfo-sec/llm-based-webshell-detect/data_storage")
OUT_DIR = Path("F:/asainfo-sec/llm-based-webshell-detect")
MAX_PER_CATEGORY = 1000
SEED = 42

CATEGORIES = [
    ("jsp", "benign"),
    ("jsp", "webshell"),
    ("php", "benign"),
    ("php", "webshell"),
    ("asp", "benign"),
    ("asp", "webshell"),
]


def sample_files():
    """Sample files and write selection record to txt."""
    random.seed(SEED)
    selection = {}

    record_lines = []
    record_lines.append(f"# BFAD Full Pipeline — Sample Selection Record")
    record_lines.append(f"# Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    record_lines.append(f"# Seed: {SEED}, Max per category: {MAX_PER_CATEGORY}")
    record_lines.append("")

    for lang, label in CATEGORIES:
        src_dir = BASE / lang / label
        if not src_dir.exists():
            print(f"SKIP: {src_dir} not found")
            continue

        all_files = sorted(src_dir.iterdir())
        n_total = len(all_files)
        n_sample = min(MAX_PER_CATEGORY, n_total)
        sampled = random.sample(all_files, n_sample)
        selection[(lang, label)] = sampled

        record_lines.append(f"## {lang}/{label} — sampled {n_sample} / {n_total}")
        for f in sorted(sampled, key=lambda x: x.name):
            record_lines.append(f"  {f}")
        record_lines.append("")

    # Write record
    record_path = OUT_DIR / "sample_selection_record.txt"
    with open(record_path, "w", encoding="utf-8") as f:
        f.write("\n".join(record_lines))
    print(f"Selection record written to: {record_path}")

    return selection


def run_detection(selection):
    """Run full BFAD detect_file() on every sampled file."""
    all_results = {}

    for (lang, label), files in selection.items():
        print(f"\n{'='*60}")
        print(f"Running full BFAD on {lang}/{label}: {len(files)} files")
        print(f"{'='*60}")

        results = []
        n = len(files)
        for i, filepath in enumerate(files):
            try:
                r = detect_file(filepath, context=5, top_k=5)
                # Strip full path, keep only filename + relative
                r["file"] = str(filepath)
                r["true_label"] = label
                results.append(r)
            except Exception as e:
                print(f"  ERROR on {filepath.name}: {e}")
                results.append({
                    "file": str(filepath),
                    "true_label": label,
                    "error": str(e),
                })

            if (i + 1) % 100 == 0:
                print(f"  Progress: {i+1}/{n}")

        all_results[f"{lang}/{label}"] = results

        # Save intermediate results
        out_path = OUT_DIR / f"bfad_results_{lang}_{label}.json"
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2, ensure_ascii=False, default=str)
        print(f"  Saved: {out_path}")

    return all_results


def main():
    print("=" * 60)
    print("Step 1: Sampling files...")
    print("=" * 60)
    selection = sample_files()

    total = sum(len(v) for v in selection.values())
    print(f"\nTotal files to process: {total}")

    print("\n" + "=" * 60)
    print("Step 2: Running full BFAD detection pipeline...")
    print("=" * 60)
    all_results = run_detection(selection)

    # Merge all into one file
    merged = {}
    for key, results in all_results.items():
        merged[key] = results

    merged_path = OUT_DIR / "bfad_results_all.json"
    with open(merged_path, "w", encoding="utf-8") as f:
        json.dump(merged, f, indent=2, ensure_ascii=False, default=str)
    print(f"\nAll results merged to: {merged_path}")
    print("Done.")


if __name__ == "__main__":
    main()

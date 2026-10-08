#!/usr/bin/env python3
"""
Stratified sampling for LLM review: 100 files per language×label (600 total).
Stratified by heuristic outcome (TP/TN vs FP/FN) and fingerprint diversity.
Outputs a compact JSON for LLM review.
"""

import json
import random
from pathlib import Path
from collections import defaultdict

BASE = Path("F:/asainfo-sec/llm-based-webshell-detect")
SEED = 123
random.seed(SEED)

CATEGORIES_7 = [
    "Code Execution", "Program Execution", "Obfuscation & Encryption",
    "Information Gathering", "Network Communication", "Callback / Reflection",
    "File Operations"
]


def classify_heuristic(r):
    has_input = r.get("has_input_channel", False)
    counts = r.get("target_counts", {})
    has_exec = counts.get("Code Execution", 0) > 0 or counts.get("Program Execution", 0) > 0
    return "SUS" if (has_input and has_exec) else "BEN"


def select_stratified(data, n_total=100):
    """Select n_total files, ensuring diversity of fingerprints and heuristic outcomes."""
    # Group by heuristic outcome
    sus_list = [r for r in data if classify_heuristic(r) == "SUS"]
    ben_list = [r for r in data if classify_heuristic(r) == "BEN"]

    # Within each group, group by fingerprint
    def fp_groups(items):
        groups = defaultdict(list)
        for r in items:
            fp = r.get("fingerprint", "0000000")
            groups[fp].append(r)
        return groups

    sus_groups = fp_groups(sus_list)
    ben_groups = fp_groups(ben_list)

    # Target: proportional to group sizes, but ensure min representation
    selected = []

    # From SUS: take proportional but cap
    n_sus = min(len(sus_list), n_total * len(sus_list) // len(data))
    n_sus = max(n_sus, min(20, len(sus_list)))  # at least 20 if available
    for fp, items in sorted(sus_groups.items(), key=lambda x: -len(x[1])):
        if len(selected) >= n_sus:
            break
        n_take = max(1, min(len(items), n_sus // max(1, len(sus_groups))))
        selected.extend(random.sample(items, min(n_take, len(items))))

    # From BEN: fill remaining
    remaining = n_total - len(selected)
    for fp, items in sorted(ben_groups.items(), key=lambda x: -len(x[1])):
        if len(selected) >= n_total:
            break
        # Take proportionally
        n_take = max(1, min(len(items), remaining // max(1, len(ben_groups))))
        selected.extend(random.sample(items, min(n_take, len(items))))
        if len(selected) >= n_total:
            break

    # If not enough, fill randomly
    if len(selected) < n_total:
        remaining_items = [r for r in data if r not in selected]
        extra = random.sample(remaining_items, min(n_total - len(selected), len(remaining_items)))
        selected.extend(extra)

    random.shuffle(selected)
    return selected[:n_total]


def prepare_review_card(r, lang, label):
    """Create a compact review card for one file."""
    fp = r.get("fingerprint", "0000000")
    counts = r.get("target_counts", {})

    # Summarize active categories
    active = [(cat, counts.get(cat, 0)) for cat in CATEGORIES_7 if counts.get(cat, 0) > 0]
    active_str = ", ".join(f"{c}={n}" for c, n in active) if active else "(clean)"

    # Trim extracted code
    code = r.get("extracted_code", "") or ""
    code = code[:2000]  # cap at 2000 chars

    # Top WBFP examples
    examples = r.get("top_examples", [])[:3]
    ex_summaries = []
    for ex in examples:
        ex_summaries.append({
            "label": ex.get("label", ""),
            "similarity": round(ex.get("similarity", 0), 4),
            "code_snippet": (ex.get("code", "") or "")[:500],
        })

    # Derive short filename
    fname = Path(r["file"]).name if "file" in r else "unknown"

    return {
        "file": fname,
        "language": lang,
        "true_label": label,
        "fingerprint": fp,
        "total_calls": r.get("total_critical_calls", 0),
        "has_input": r.get("has_input_channel", False),
        "active_categories": active_str,
        "heuristic": classify_heuristic(r),
        "extracted_code": code,
        "top_examples": ex_summaries,
    }


def main():
    all_cards = {}

    for lang in ["jsp", "php", "asp"]:
        for label in ["benign", "webshell"]:
            key = f"{lang}_{label}"
            path = BASE / f"bfad_results_{lang}_{label}.json"
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            data = [r for r in data if "error" not in r]

            selected = select_stratified(data, n_total=100)

            cards = []
            h_outcomes = {"SUS": 0, "BEN": 0}
            for r in selected:
                card = prepare_review_card(r, lang, label)
                cards.append(card)
                h_outcomes[card["heuristic"]] += 1

            all_cards[key] = cards
            print(f"{key}: selected {len(cards)}, SUS={h_outcomes['SUS']}, BEN={h_outcomes['BEN']}")

    # Save
    out_path = BASE / "llm_review_cards.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(all_cards, f, indent=2, ensure_ascii=False)

    print(f"\nSaved {sum(len(v) for v in all_cards.values())} review cards to {out_path}")


if __name__ == "__main__":
    main()

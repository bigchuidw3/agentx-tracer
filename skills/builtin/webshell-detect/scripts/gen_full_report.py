#!/usr/bin/env python3
"""
Generate comprehensive BFAD test report from full-pipeline detection results.
"""

import json
import sys
from pathlib import Path
from collections import Counter

BASE = Path("F:/asainfo-sec/llm-based-webshell-detect")
CATEGORIES_7 = [
    "Code Execution", "Program Execution", "Obfuscation & Encryption",
    "Information Gathering", "Network Communication", "Callback / Reflection",
    "File Operations"
]

def load_results(lang, label):
    path = BASE / f"bfad_results_{lang}_{label}.json"
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def classify_heuristic(r):
    """BFAD heuristic: suspicious = input + execution primitive."""
    has_input = r.get("has_input_channel", False)
    counts = r.get("target_counts", {})
    has_exec = counts.get("Code Execution", 0) > 0 or counts.get("Program Execution", 0) > 0
    return 1 if (has_input and has_exec) else 0


def main():
    print("=" * 80)
    print("  BFAD Full-Pipeline Webshell Detection — Test Report")
    print("=" * 80)
    print("  Method: Full BFAD pipeline (scan → extract → WBFP match → prompt)")
    print("  Sampling: up to 1000 files per category, seed=42")
    print("  Heuristic: suspicious = has_input_channel AND (Code_Exe > 0 OR Prog_Exe > 0)")
    print()

    all_metrics = {}

    for lang in ["jsp", "php", "asp"]:
        print("-" * 80)
        print(f"  [{lang.upper()}]")
        print("-" * 80)

        benign = load_results(lang, "benign")
        webshell = load_results(lang, "webshell")

        # Skip error entries
        benign = [r for r in benign if "error" not in r]
        webshell = [r for r in webshell if "error" not in r]

        n_b = len(benign)
        n_w = len(webshell)

        # ── Classify ──
        b_pred = [classify_heuristic(r) for r in benign]
        w_pred = [classify_heuristic(r) for r in webshell]

        TP = sum(w_pred)
        FN = n_w - TP
        FP = sum(b_pred)
        TN = n_b - FP

        accuracy = (TP + TN) / (TP + TN + FP + FN) if (TP + TN + FP + FN) > 0 else 0
        precision = TP / (TP + FP) if (TP + FP) > 0 else 0
        recall = TP / (TP + FN) if (TP + FN) > 0 else 0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0
        fpr = FP / (FP + TN) if (FP + TN) > 0 else 0
        tnr = TN / (TN + FP) if (TN + FP) > 0 else 0

        # ── Data Overview ──
        print()
        print(f"  Sample size: Benign={n_b}, Webshell={n_w}")
        print()

        for label, data in [("Benign", benign), ("Webshell", webshell)]:
            calls = [r["total_critical_calls"] for r in data]
            has_in = sum(1 for r in data if r["has_input_channel"])
            print(f"  [{label}]")
            print(f"    Avg critical calls:       {sum(calls)/len(calls):.2f}")
            print(f"    Median critical calls:    {sorted(calls)[len(calls)//2]}")
            print(f"    Max critical calls:       {max(calls)}")
            has_any = sum(1 for c in calls if c > 0)
            print(f"    Files with >=1 call:      {has_any} ({has_any/len(data)*100:.1f}%)")
            print(f"    Has input channel:        {has_in} ({has_in/len(data)*100:.1f}%)")
            obf = sum(1 for r in data if r["target_counts"].get("Obfuscation & Encryption", 0) > 0)
            print(f"    Has obfuscation:          {obf} ({obf/len(data)*100:.1f}%)")
            exe = sum(1 for r in data if r["target_counts"].get("Code Execution", 0) > 0 or r["target_counts"].get("Program Execution", 0) > 0)
            print(f"    Has execution primitive:  {exe} ({exe/len(data)*100:.1f}%)")
            sus = sum(1 for r in data if classify_heuristic(r))
            print(f"    Heuristic SUSPICIOUS:     {sus} ({sus/len(data)*100:.1f}%)")
            print()

        # ── Category Hit Rates ──
        print(f"  [Category Hit Rates]")
        print(f"    {'Category':35s} {'Benign':>8s} {'Webshell':>8s}  {'B_avg':>8s} {'W_avg':>8s}")
        print(f"    {'─'*33}  {'─'*8} {'─'*8}  {'─'*8} {'─'*8}")
        for cat in CATEGORIES_7:
            b_hit = sum(1 for r in benign if r["target_counts"].get(cat, 0) > 0)
            w_hit = sum(1 for r in webshell if r["target_counts"].get(cat, 0) > 0)
            b_avg = sum(r["target_counts"].get(cat, 0) for r in benign) / n_b
            w_avg = sum(r["target_counts"].get(cat, 0) for r in webshell) / n_w
            if b_hit > 0 or w_hit > 0:
                print(f"    {cat:35s} {b_hit/n_b*100:7.1f}% {w_hit/n_w*100:7.1f}%  {b_avg:8.4f} {w_avg:8.4f}")
        print()

        # ── WBFP Matching Quality ──
        print(f"  [WBFP Top-1 Example Match]")
        for label, data in [("Benign", benign), ("Webshell", webshell)]:
            same_label = 0
            total_with_examples = 0
            top_sims = []
            for r in data:
                examples = r.get("top_examples", [])
                if examples:
                    total_with_examples += 1
                    top_sims.append(examples[0].get("similarity", 0))
                    if examples[0].get("label") == label.lower():
                        same_label += 1
            if total_with_examples > 0:
                avg_sim = sum(top_sims) / len(top_sims)
                print(f"    {label}: top-1 matches same label = {same_label}/{total_with_examples} "
                      f"({same_label/total_with_examples*100:.1f}%), avg similarity = {avg_sim:.4f}")
        print()

        # ── Confusion Matrix ──
        print(f"  [Classification Results — BFAD Heuristic]")
        print(f"    {'Metric':20s} {'Value':>10s}")
        print(f"    {'─'*20} {'─'*11}")
        print(f"    TP (WS→Sus):      {TP:10d}")
        print(f"    FN (WS→Benign):   {FN:10d}")
        print(f"    FP (Benign→Sus):  {FP:10d}")
        print(f"    TN (Benign→Ben):  {TN:10d}")
        print(f"    {'─'*20} {'─'*11}")
        print(f"    {'Accuracy':20s} {accuracy:10.4f}")
        print(f"    {'Precision':20s} {precision:10.4f}")
        print(f"    {'Recall (TPR)':20s} {recall:10.4f}")
        print(f"    {'F1 Score':20s} {f1:10.4f}")
        print(f"    {'FPR':20s} {fpr:10.4f}")
        print(f"    {'TNR':20s} {tnr:10.4f}")
        print()

        all_metrics[lang] = {
            "n_benign": n_b, "n_webshell": n_w,
            "TP": TP, "FN": FN, "FP": FP, "TN": TN,
            "accuracy": accuracy, "precision": precision,
            "recall": recall, "f1": f1, "fpr": fpr, "tnr": tnr,
        }

    # ── Overall Summary ──
    print("=" * 80)
    print("  Overall Summary")
    print("=" * 80)
    print()
    hdr = f"  {'Language':8s} {'Benign':>8s} {'WS':>8s} {'Accuracy':>10s} {'Precision':>10s} {'Recall':>10s} {'F1':>10s} {'FPR':>10s}"
    print(hdr)
    print("  " + "─" * (len(hdr) - 2))
    for lang in ["jsp", "php", "asp"]:
        m = all_metrics[lang]
        print(f"  {lang.upper():8s} {m['n_benign']:8d} {m['n_webshell']:8d} "
              f"{m['accuracy']:10.4f} {m['precision']:10.4f} {m['recall']:10.4f} "
              f"{m['f1']:10.4f} {m['fpr']:10.4f}")

    avg = {k: sum(all_metrics[l][k] for l in ["jsp","php","asp"]) / 3
           for k in ["accuracy","precision","recall","f1","fpr"]}
    print(f"  {'AVG':8s} {'':>8s} {'':>8s} "
          f"{avg['accuracy']:10.4f} {avg['precision']:10.4f} {avg['recall']:10.4f} "
          f"{avg['f1']:10.4f} {avg['fpr']:10.4f}")
    print()

    # ── Key Insights ──
    print("=" * 80)
    print("  Key Insights")
    print("=" * 80)
    print()

    # Weakness analysis
    for lang in ["jsp", "php", "asp"]:
        benign = load_results(lang, "benign")
        webshell = load_results(lang, "webshell")
        benign = [r for r in benign if "error" not in r]
        webshell = [r for r in webshell if "error" not in r]

        # False positives (benign classified as suspicious)
        fps = [r for r in benign if classify_heuristic(r)]
        # False negatives (webshell classified as benign)
        fns = [r for r in webshell if not classify_heuristic(r)]

        print(f"  [{lang.upper()}]")
        print(f"    False Positives (benign→sus):  {len(fps)} / {len(benign)}")
        if fps:
            fp_fps = Counter()
            for r in fps:
                fp_fps[r["fingerprint"]] += 1
            print(f"    Top FP fingerprints: {fp_fps.most_common(5)}")

        print(f"    False Negatives (WS→benign):   {len(fns)} / {len(webshell)}")
        if fns:
            fn_fps = Counter()
            for r in fns:
                fn_fps[r["fingerprint"]] += 1
            print(f"    Top FN fingerprints: {fn_fps.most_common(5)}")
            # Among FNs, how many have 0 critical calls?
            zero_calls = sum(1 for r in fns if r["total_critical_calls"] == 0)
            print(f"    FN with zero critical calls: {zero_calls} ({zero_calls/len(fns)*100:.1f}%)")
            # Among FNs, how many have input but no execution?
            in_no_exe = sum(1 for r in fns if r["has_input_channel"] and not (
                r["target_counts"].get("Code Execution", 0) > 0 or
                r["target_counts"].get("Program Execution", 0) > 0))
            print(f"    FN with input but no execution: {in_no_exe} ({in_no_exe/len(fns)*100:.1f}%)")
        print()

    print("=" * 80)
    print("  Report Complete")
    print("=" * 80)


if __name__ == "__main__":
    main()

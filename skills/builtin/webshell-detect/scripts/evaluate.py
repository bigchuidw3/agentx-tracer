#!/usr/bin/env python3
"""
BFAD 检测准确性评估。

从 data_storage 中随机采样黑白样本，运行 detect.py 管线，
对比 ground truth 计算准确率、精确率、召回率、F1。

Usage:
    python evaluate.py                        # 全语言评估，每类采样 500
    python evaluate.py --lang php --n 1000    # PHP only，每类 1000
    python evaluate.py --all                  # 全量评估（不采样）
"""

import argparse
import json
import random
import sys
import time
from pathlib import Path
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from detect import detect_file

from config import DATA_DIR as DATA
UNIFIED_7 = [
    "Code Execution", "Program Execution", "Obfuscation & Encryption",
    "Information Gathering", "Network Communication",
    "Callback / Reflection", "File Operations",
]


def collect_samples(lang, label, n=None):
    """收集某语言某标签的样本文件路径。"""
    d = DATA / lang / label
    if not d.exists():
        return []
    files = list(d.iterdir())
    if n and len(files) > n:
        files = random.sample(files, n)
    return files


def bfad_heuristic(result):
    """BFAD 启发式规则：有输入通道 + (Code Exec 或 Program Exec) → SUSPICIOUS。"""
    has_exec = ("Code Execution" in result['active_categories'] or
                "Program Execution" in result['active_categories'])
    return "webshell" if (result['has_input_channel'] and has_exec) else "benign"


def wbfp_top_label(result):
    """WBFP 选出的 top-1 示例标签。"""
    if result['top_examples']:
        return result['top_examples'][0]['label']
    return "unknown"


def evaluate_lang(lang, n=None):
    """评估一种语言。"""
    print(f"\n{'='*65}")
    print(f"  Evaluating {lang.upper()}")
    print(f"{'='*65}")

    benign_files = collect_samples(lang, "benign", n)
    webshell_files = collect_samples(lang, "webshell", n)

    print(f"  Benign samples:   {len(benign_files)}")
    print(f"  Webshell samples: {len(webshell_files)}")

    all_files = [(f, "benign") for f in benign_files] + \
                [(f, "webshell") for f in webshell_files]
    random.shuffle(all_files)

    # 混淆矩阵
    cm = {"TP": 0, "TN": 0, "FP": 0, "FN": 0}  # heuristic
    cm_wbfp = {"TP": 0, "TN": 0, "FP": 0, "FN": 0}  # WBFP top-1
    cm_ml = {"TP": 0, "TN": 0, "FP": 0, "FN": 0}  # multi-layer: has_exec + has_input + obfuscation

    errors = []
    stats_by_fp = defaultdict(lambda: {"total": 0, "webshell": 0})

    t0 = time.time()
    for i, (fp, truth) in enumerate(all_files, 1):
        r = detect_file(str(fp), top_k=3)
        pred_heuristic = bfad_heuristic(r)
        pred_wbfp = wbfp_top_label(r)
        fingerprint = r.get('fingerprint', '0000000')

        # 多层级启发式：input + exec + obfuscation → webshell（更严格）
        has_exec = ("Code Execution" in r['active_categories'] or
                    "Program Execution" in r['active_categories'])
        has_obf = "Obfuscation & Encryption" in r['active_categories']
        pred_ml = "webshell" if (r['has_input_channel'] and has_exec and has_obf) else \
                  "webshell" if (r['has_input_channel'] and has_exec) else "benign"

        # Heuristic 混淆矩阵
        if truth == "webshell" and pred_heuristic == "webshell":
            cm["TP"] += 1
        elif truth == "benign" and pred_heuristic == "benign":
            cm["TN"] += 1
        elif truth == "benign" and pred_heuristic == "webshell":
            cm["FP"] += 1
        elif truth == "webshell" and pred_heuristic == "benign":
            cm["FN"] += 1

        # WBFP top-1 混淆矩阵
        if truth == "webshell" and pred_wbfp == "webshell":
            cm_wbfp["TP"] += 1
        elif truth == "benign" and pred_wbfp == "benign":
            cm_wbfp["TN"] += 1
        elif truth == "benign" and pred_wbfp == "webshell":
            cm_wbfp["FP"] += 1
        elif truth == "webshell" and pred_wbfp == "benign":
            cm_wbfp["FN"] += 1

        # 多层级启发式混淆矩阵
        if truth == "webshell" and pred_ml == "webshell":
            cm_ml["TP"] += 1
        elif truth == "benign" and pred_ml == "benign":
            cm_ml["TN"] += 1
        elif truth == "benign" and pred_ml == "webshell":
            cm_ml["FP"] += 1
        elif truth == "webshell" and pred_ml == "benign":
            cm_ml["FN"] += 1

        if truth != pred_heuristic:
            errors.append({
                "file": str(fp),
                "truth": truth,
                "predicted": pred_heuristic,
                "fingerprint": fingerprint,
                "calls": r['total_critical_calls'],
                "categories": r['active_categories'],
                "has_input": r['has_input_channel'],
            })

        stats_by_fp[fingerprint]["total"] += 1
        if truth == "webshell":
            stats_by_fp[fingerprint]["webshell"] += 1

        if i % 200 == 0:
            elapsed = time.time() - t0
            rate = i / elapsed
            remaining = (len(all_files) - i) / rate
            print(f"  Progress: {i}/{len(all_files)} ({rate:.1f}/s, ETA {remaining:.0f}s)")

    elapsed = time.time() - t0
    print(f"  Done in {elapsed:.1f}s ({len(all_files)/elapsed:.1f} files/s)")

    # 计算指标
    def metrics(cm_dict, name):
        tp, tn, fp, fn = cm_dict["TP"], cm_dict["TN"], cm_dict["FP"], cm_dict["FN"]
        total = tp + tn + fp + fn
        acc = (tp + tn) / total if total else 0
        prec = tp / (tp + fp) if (tp + fp) else 0
        rec = tp / (tp + fn) if (tp + fn) else 0
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0
        print(f"\n  --- {name} ---")
        print(f"  Accuracy:  {acc*100:.2f}%")
        print(f"  Precision: {prec*100:.2f}%")
        print(f"  Recall:    {rec*100:.2f}%")
        print(f"  F1 Score:  {f1*100:.2f}%")
        print(f"  CM: TP={tp} TN={tn} FP={fp} FN={fn}")
        return {"accuracy": acc, "precision": prec, "recall": rec, "f1": f1,
                "tp": tp, "tn": tn, "fp": fp, "fn": fn}

    r_heuristic = metrics(cm, "BFAD Heuristic (input + exec)")
    r_wbfp = metrics(cm_wbfp, "WBFP Top-1 Label")
    r_ml = metrics(cm_ml, "Multi-Layer (input + exec + obfuscation)")

    # 指纹统计
    print(f"\n  --- Fingerprint Distribution (top 15) ---")
    sorted_fps = sorted(stats_by_fp.items(), key=lambda x: -x[1]["total"])
    for fp, s in sorted_fps[:15]:
        ws_pct = s["webshell"] / s["total"] * 100 if s["total"] else 0
        print(f"  {fp}: {s['total']:5d} files, {ws_pct:5.1f}% webshell")

    # 错误样本摘要
    if errors:
        print(f"\n  --- Error Samples (first 10) ---")
        for e in errors[:10]:
            print(f"  [{e['truth']}→{e['predicted']}] fp={e['fingerprint']} "
                  f"calls={e['calls']} input={e['has_input']} "
                  f"cats={e['categories']}")

    return {
        "lang": lang,
        "n_benign": len(benign_files),
        "n_webshell": len(webshell_files),
        "heuristic": r_heuristic,
        "wbfp_top1": r_wbfp,
        "multi_layer": r_ml,
        "errors": errors[:20],
    }


def main():
    parser = argparse.ArgumentParser(description="BFAD 检测准确性评估")
    parser.add_argument("--lang", choices=["php", "jsp", "asp"],
                        help="只评估指定语言")
    parser.add_argument("--n", type=int, default=500,
                        help="每类采样数 (默认 500)")
    parser.add_argument("--all", action="store_true",
                        help="全量评估（不采样）")
    parser.add_argument("--json", action="store_true",
                        help="JSON 输出")
    args = parser.parse_args()

    random.seed(42)
    n = None if args.all else args.n
    langs = [args.lang] if args.lang else ["php", "jsp", "asp"]

    all_results = {}
    for lang in langs:
        all_results[lang] = evaluate_lang(lang, n=n)

    # 汇总
    print(f"\n{'='*65}")
    print(f"  SUMMARY")
    print(f"{'='*65}")
    for method in ["heuristic", "wbfp_top1", "multi_layer"]:
        method_name = {"heuristic": "BFAD Heuristic", "wbfp_top1": "WBFP Top-1",
                       "multi_layer": "Multi-Layer"}[method]
        print(f"\n  {method_name}:")
        print(f"  {'Lang':6s} {'Acc':>8s} {'Prec':>8s} {'Rec':>8s} {'F1':>8s}")
        print(f"  {'-'*45}")
        for lang, r in all_results.items():
            m = r[method]
            print(f"  {lang:6s} {m['accuracy']*100:7.2f}% {m['precision']*100:7.2f}% "
                  f"{m['recall']*100:7.2f}% {m['f1']*100:7.2f}%")

    if args.json:
        print(json.dumps(all_results, indent=2, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()

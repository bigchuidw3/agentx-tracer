#!/usr/bin/env python3
"""
WBFP 示例选择器 — 给定待测文件，从示例库中按加权行为相似度选最佳 Few-shot 示例。

算法 (BFAD Section 3.3):
    对待测文件 x 和示例库中每个候选 y:
        Sim(x,y) = Σ( w_f × cos_sim(e_f(x), e_f(y)) )
    其中 e_f 是第 f 类关键函数的计数向量，w_f 是 WBFP 权重。

Usage:
    python select_demo.py <target_file>                        # 选单个文件的最佳示例
    python select_demo.py <target_file> --top 5 --lang php    # 指定语言和数量
    python select_demo.py <target_dir> --lang php --top 3     # 批量选择
"""

import argparse
import csv
import json
import sys
from pathlib import Path
from collections import defaultdict
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from scan_critical import (
    scan_file, PHP_FUNCTIONS, PHP_INPUTS,
    JSP_PATTERNS,  JSP_INPUTS,
    ASP_PATTERNS,  ASP_INPUTS,
    JS_PATTERNS,   JS_INPUTS,
    ASPX_PATTERNS, ASPX_INPUTS,
)

from config import DATA_DIR as DATA, EXAMPLES_DIR

UNIFIED_7 = [
    "Code Execution",
    "Program Execution",
    "Obfuscation & Encryption",
    "Information Gathering",
    "Network Communication",
    "Callback / Reflection",
    "File Operations",
]

COL_ALIASES = {"Callback / Reflection": "Callback Functions"}

LANG_SCANNERS = {
    "php":  (PHP_FUNCTIONS, PHP_INPUTS),
    "jsp":  (JSP_PATTERNS,  JSP_INPUTS),
    "asp":  (ASP_PATTERNS,  ASP_INPUTS),
    "js":   (JS_PATTERNS,   JS_INPUTS),
    "aspx": (ASPX_PATTERNS, ASPX_INPUTS),
}

# ─── WBFP 权重（来自 compute_wbfp_weights.py） ─────────────────────────

WBFP_WEIGHTS = {
    "php": {
        "Obfuscation & Encryption": 0.7184,
        "Code Execution":            0.1153,
        "Information Gathering":     0.0630,
        "Program Execution":         0.0466,
        "Network Communication":     0.0400,
        "Callback / Reflection":     0.0167,
        "File Operations":           0.0,
    },
    "jsp": {
        "Network Communication":     0.2290,
        "Callback / Reflection":     0.2290,
        "Information Gathering":     0.1886,
        "Program Execution":         0.1513,
        "Obfuscation & Encryption":  0.1360,
        "Code Execution":            0.0662,
        "File Operations":           0.0,
    },
    "asp": {
        "Network Communication":     0.5618,
        "Program Execution":         0.1781,
        "File Operations":           0.0982,
        "Information Gathering":     0.0760,
        "Obfuscation & Encryption":  0.0707,
        "Code Execution":            0.0152,
        "Callback / Reflection":     0.0,
    },
    "js": {
        # 默认等权（缺少白样本，暂无法计算区分度）
        "Code Execution":            0.2,
        "Program Execution":         0.2,
        "Obfuscation & Encryption":  0.1,
        "Information Gathering":     0.1,
        "Network Communication":     0.1,
        "Callback / Reflection":     0.1,
        "File Operations":           0.2,
    },
    "aspx": {
        "Code Execution":            0.2098,
        "Program Execution":         0.2098,
        "Obfuscation & Encryption":  0.1526,
        "Information Gathering":     0.0914,
        "Network Communication":     0.1635,
        "Callback / Reflection":     0.0255,
        "File Operations":           0.1474,
    },
}


def get_category_counts(row):
    counts = {}
    for cat in UNIFIED_7:
        val = row.get(cat)
        if val is None:
            val = row.get(COL_ALIASES.get(cat, ""), 0)
        counts[cat] = int(float(val or 0))
    return counts


_EXAMPLE_INDEX_CACHE = {}

def build_example_index(lang, exclude_file=None):
    """从预构建示例库加载索引（优先用 examples/ 下的 JSON，回退到全量 CSV）。

    Args:
        lang: 语言 (php/jsp/asp/js/aspx)
        exclude_file: 要排除的文件路径，避免自己匹配自己
    """
    cache_key = f"{lang}_{exclude_file}"
    if cache_key in _EXAMPLE_INDEX_CACHE:
        return _EXAMPLE_INDEX_CACHE[cache_key]

    index = {}

    # 优先：加载预构建示例库 JSON
    json_path = EXAMPLES_DIR / f"{lang}_example_index.json"
    if json_path.exists():
        with open(json_path, "r", encoding="utf-8") as f:
            raw = json.load(f)
        exclude_name = Path(exclude_file).name if exclude_file else None
        for fn, info in raw.items():
            if exclude_name and fn == exclude_name:
                continue  # 排除自身
            index[fn] = info
    else:
        # 回退：从全量扫描 CSV 构建（旧行为）
        for label in ("benign", "webshell"):
            csv_path = DATA / f"{lang}_{label}_scan.csv"
            if not csv_path.exists():
                continue
            with open(csv_path, "r", encoding="utf-8-sig") as f:
                for row in csv.DictReader(f):
                    counts = get_category_counts(row)
                    index[row["filename"]] = {
                        "counts": counts,
                        "label": label,
                        "total": int(float(row.get("total_critical_calls", 0))),
                        "has_input": row.get("has_input_channel", "0") == "1",
                    }

    _EXAMPLE_INDEX_CACHE[cache_key] = index
    return index


def get_count_vector(counts):
    """7 维计数向量（归一化到 [0,1]）。"""
    vec = np.array([counts[c] for c in UNIFIED_7], dtype=float)
    norm = np.linalg.norm(vec)
    if norm > 0:
        vec = vec / norm
    return vec


def get_weight_vector(lang):
    """7 维权向量。"""
    w = WBFP_WEIGHTS.get(lang, WBFP_WEIGHTS["php"])
    return np.array([w[c] for c in UNIFIED_7], dtype=float)


def wbfp_similarity(target_counts, candidate_counts, lang,
                    target_total=0, cand_total=0,
                    target_input=False, cand_input=False):
    """
    计算加权行为相似度（三层融合）。

    Layer 1 — 行为指纹匹配 (Dice, 权重 0.5): 7 类命中/未命中模式
    Layer 2 — 计数幅度匹配 (余弦, 权重 0.3): 每类调用次数
    Layer 3 — 全局特征匹配 (Jaccard, 权重 0.2): 总调用数 + 输入通道
    """
    wv = get_weight_vector(lang)

    # Layer 1: 指纹 (presence/absence)
    tp = np.array([1.0 if target_counts[c] > 0 else 0.0 for c in UNIFIED_7])
    cp = np.array([1.0 if candidate_counts[c] > 0 else 0.0 for c in UNIFIED_7])
    fps = np.zeros(len(UNIFIED_7))
    for i in range(len(UNIFIED_7)):
        a, b = tp[i], cp[i]
        if a + b > 0:
            fps[i] = 2 * min(a, b) / (a + b)
        else:
            fps[i] = 1.0
    sim_fingerprint = float(np.dot(wv, fps))

    # Layer 2: 计数幅度
    tv = get_count_vector(target_counts)
    cv = get_count_vector(candidate_counts)
    ct = np.zeros(len(UNIFIED_7))
    for i in range(len(UNIFIED_7)):
        a, b = tv[i], cv[i]
        if a + b > 1e-8:
            ct[i] = 1.0 - abs(a - b) / max(a, b, 0.5)
        else:
            ct[i] = 1.0
    sim_counts = float(np.dot(wv, ct))

    # Layer 3: 全局特征 — 总数接近度 + 输入通道一致
    total_sim = 1.0 - abs(target_total - cand_total) / max(target_total, cand_total, 1)
    input_sim = 1.0 if target_input == cand_input else 0.0
    sim_global = 0.5 * total_sim + 0.5 * input_sim

    return 0.5 * sim_fingerprint + 0.3 * sim_counts + 0.2 * sim_global + np.random.uniform(0, 0.0001)


def select_examples(target_file, lang, example_index, top_k=3):
    """为待测文件选择 top_k 个最佳示例。"""
    funcs, inputs = LANG_SCANNERS[lang]
    r = scan_file(str(target_file), funcs, inputs)

    # 从扫描结果提取 7 类计数
    target_counts = {}
    for cat in UNIFIED_7:
        target_counts[cat] = 0
    for cat_name, info in r["categories"].items():
        # 映射类别名
        mapped = cat_name
        if cat_name == "Callback Functions":
            mapped = "Callback / Reflection"
        if mapped in target_counts:
            target_counts[mapped] = info["count"]

    # 对每个候选算相似度
    scores = []
    target_total = sum(target_counts.values())
    target_input = r["has_input_channel"]
    for fn, info in example_index.items():
        sim = wbfp_similarity(
            target_counts, info["counts"], lang,
            target_total=target_total, cand_total=info["total"],
            target_input=target_input, cand_input=info["has_input"],
        )
        scores.append((sim, fn, info["label"], info["counts"], info["total"]))

    scores.sort(key=lambda x: -x[0])

    # 纯按 WBFP 相似度取 top_k
    result = list(scores[:top_k])

    # 如果全是一种标签，补一个对立标签的最高分示例作为对比参照
    labels = {s[2] for s in result}
    if len(labels) == 1:
        sole_label = list(labels)[0]
        opposite = "webshell" if sole_label == "benign" else "benign"
        for s in scores:
            if s[2] == opposite:
                result.append(s)
                break

    return result, target_counts


def format_result(target_file, lang, results, target_counts):
    """格式化输出结果。"""
    active = [c for c in UNIFIED_7 if target_counts.get(c, 0) > 0]
    fingerprint = "".join("1" if target_counts.get(c, 0) > 0 else "0" for c in UNIFIED_7)

    lines = []
    lines.append(f"\n{'='*65}")
    lines.append(f"  Target: {Path(target_file).name}")
    lines.append(f"  Language: {lang.upper()}")
    lines.append(f"  Fingerprint: {fingerprint}  ({', '.join(active) if active else 'clean'})")
    lines.append(f"  Total critical calls: {sum(target_counts.values())}")
    lines.append(f"\n  Top {len(results)} WBFP Matches:")
    lines.append(f"  {'Sim':>7s}  {'Label':10s}  {'Calls':>5s}  Filename")
    lines.append(f"  {'-'*55}")

    for sim, fn, label, counts, total in results:
        lines.append(f"  {sim:.4f}  {label:10s}  {total:5d}  {fn}")

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="WBFP 示例选择器")
    parser.add_argument("target", help="待测文件或目录")
    parser.add_argument("--lang", choices=["php", "jsp", "asp"],
                        help="语言（默认自动检测扩展名）")
    parser.add_argument("--top", type=int, default=3,
                        help="返回示例数（默认 3）")
    parser.add_argument("--json", action="store_true",
                        help="JSON 输出")
    args = parser.parse_args()

    target = Path(args.target)

    # 收集待测文件
    if target.is_file():
        targets = [target]
    else:
        targets = []
        for ext in (".php", ".jsp", ".jspx", ".asp", ".asa"):
            for f in target.rglob(f"*{ext}"):
                targets.append(f)

    if not targets:
        print("No target files found.", file=sys.stderr)
        sys.exit(1)

    # 对每个目标文件选择示例
    all_results = []
    for t in targets:
        lang = args.lang or Path(t).suffix.lstrip(".").replace("jspx", "jsp")
        if lang not in LANG_SCANNERS:
            continue

        example_index = build_example_index(lang)
        selected, tc = select_examples(t, lang, example_index, args.top)

        if args.json:
            all_results.append({
                "file": str(t),
                "fingerprint": "".join("1" if tc.get(c, 0) > 0 else "0" for c in UNIFIED_7),
                "matches": [
                    {"sim": round(s, 4), "file": fn, "label": lb}
                    for s, fn, lb, _, _ in selected
                ]
            })
        else:
            print(format_result(t, lang, selected, tc))

    if args.json:
        print(json.dumps(all_results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

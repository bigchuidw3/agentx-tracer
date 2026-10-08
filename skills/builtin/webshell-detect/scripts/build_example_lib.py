#!/usr/bin/env python3
"""
从 data_storage 数据集自动构建 Few-shot 示例库。

对每种语言:
1. 读取扫描 CSV，按 7 类行为指纹聚类
2. 每类抽取代表性样本（Webshell + 边界 Benign）
3. 提取关键代码片段（上下文窗口可配置）
4. 输出到 examples/ 目录

Usage:
    python build_example_lib.py                        # 默认 context=5
    python build_example_lib.py --context 10           # 前后各 10 行
    python build_example_lib.py --lang php --context 3 # PHP only, 紧凑窗口
"""

import argparse
import csv
import json
import os
import random
import sys
from pathlib import Path
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).parent))
from scan_critical import (
    scan_file, fingerprint_summary,
    PHP_FUNCTIONS, PHP_INPUTS,
    JSP_PATTERNS,  JSP_INPUTS,
    ASP_PATTERNS,  ASP_INPUTS,
    JS_PATTERNS,   JS_INPUTS,
    ASPX_PATTERNS, ASPX_INPUTS,
)

from config import DATA_DIR as DATA, EXAMPLES_DIR, SKILL_ROOT as BASE

UNIFIED_7 = [
    "Code Execution",
    "Program Execution",
    "Obfuscation & Encryption",
    "Information Gathering",
    "Network Communication",
    "Callback / Reflection",
    "File Operations",
]

# 列名回退映射（旧 CSV 用 Callback Functions 而非 Callback / Reflection）
COL_ALIASES = {
    "Callback / Reflection": "Callback Functions",
}

LANG_SCANNERS = {
    "php":  (PHP_FUNCTIONS, PHP_INPUTS),
    "jsp":  (JSP_PATTERNS,  JSP_INPUTS),
    "asp":  (ASP_PATTERNS,  ASP_INPUTS),
    "js":   (JS_PATTERNS,   JS_INPUTS),
    "aspx": (ASPX_PATTERNS, ASPX_INPUTS),
}


def read_scan_csv(csv_path):
    """读取扫描 CSV，返回 {filename: {col: value}} 字典。"""
    rows = {}
    with open(csv_path, "r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows[row["filename"]] = row
    return rows


def get_category_counts(row):
    """从 CSV 行提取 7 类计数。"""
    counts = {}
    for cat in UNIFIED_7:
        val = row.get(cat)
        if val is None:
            val = row.get(COL_ALIASES.get(cat, ""), 0)
        counts[cat] = int(float(val or 0))
    return counts


def fingerprint_key(counts):
    """生成行为指纹的聚类 key：只关心每类 >0 还是 =0。"""
    bits = "".join("1" if counts[c] > 0 else "0" for c in UNIFIED_7)
    return bits


def extract_critical_code(filepath, lang, context=5, max_output=100, merge_gap=10):
    """提取关键代码片段。

    Args:
        filepath: 源文件路径
        lang: 语言
        context: 每个关键函数前后取多少行（对应 BFAD 的 τ）
        max_output: 输出最大行数
        merge_gap: 相邻命中行间隔不超过此行数时合并为同一 chunk
    """
    funcs, inputs = LANG_SCANNERS[lang]
    r = scan_file(str(filepath), funcs, inputs)

    lines_with_hits = set()
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            source_lines = f.readlines()
    except Exception:
        return "// Unable to read source"

    for cat_info in r["categories"].values():
        for match in cat_info.get("matches", []):
            match_str = str(match)
            for i, line in enumerate(source_lines):
                if match_str in line:
                    start = max(0, i - context)
                    end = min(len(source_lines), i + context + 1)
                    for j in range(start, end):
                        lines_with_hits.add(j)

    if not lines_with_hits:
        lines_with_hits = set(range(min(30, len(source_lines))))

    sorted_lines = sorted(lines_with_hits)

    # 合并相邻行
    chunks = []
    chunk_start = sorted_lines[0]
    chunk_end = sorted_lines[0]
    for ln in sorted_lines[1:]:
        if ln <= chunk_end + merge_gap:
            chunk_end = ln
        else:
            chunks.append((chunk_start, chunk_end))
            chunk_start = chunk_end = ln
    chunks.append((chunk_start, chunk_end))

    # 输出
    result = []
    total = 0
    for cs, ce in chunks:
        if total >= max_output:
            break
        result.append(f"// --- lines {cs+1}-{ce+1} ---")
        for j in range(cs, min(ce + 1, len(source_lines))):
            line = source_lines[j].rstrip()
            if len(line) > 500:
                line = line[:500] + f"... [truncated {len(line)-500} chars]"
            result.append(line)
            total += 1
        result.append("")

    # 如果 chunk 太少（≤2），追加文件尾部代码（可能含伪装垃圾）
    if len(chunks) <= 2 and len(source_lines) > chunks[-1][1] + 5:
        tail_start = chunks[-1][1] + 1
        tail_end = min(len(source_lines), tail_start + 15)
        if tail_end > tail_start:
            result.append(f"// --- tail lines {tail_start+1}-{tail_end} ---")
            for j in range(tail_start, tail_end):
                line = source_lines[j].rstrip()
                if len(line) > 500:
                    line = line[:500] + f"... [truncated {len(line)-500} chars]"
                result.append(line)
                total += 1
            result.append("")

    return "\n".join(result)


def sample_files(file_rows, label, fingerprint_groups, data_dir, lang,
                 max_per_group=5, max_benign_per_group=3, max_total=999):
    """从行为指纹聚类中抽样文件。
    - Webshell: 每种指纹至少 1 个，常见指纹最多 max_per_group 个
    - Benign: 每种有命中的指纹最多 max_benign_per_group 个，再加无命中的
    """
    selected = []

    if label == "webshell":
        # 每种指纹至少 1 个代表，大组多抽几个
        for fp_key, fns in sorted(fingerprint_groups.items(),
                                   key=lambda x: -len(x[1])):
            n = min(max_per_group, len(fns))
            sample = random.sample(fns, n)
            for fn in sample:
                filepath = data_dir / fn
                if filepath.exists():
                    selected.append((fn, fp_key, filepath))
    else:
        # Benign: 每种有命中的指纹抽几个，表示边界案例
        with_hits = {k: v for k, v in fingerprint_groups.items() if k != "0000000"}
        without_hits = fingerprint_groups.get("0000000", [])

        for fp_key, fns in sorted(with_hits.items(), key=lambda x: -len(x[1])):
            n = min(max_benign_per_group, len(fns))
            for fn in random.sample(fns, n):
                filepath = data_dir / fn
                if filepath.exists():
                    selected.append((fn, fp_key, filepath))

        # 补几个无命中的
        if without_hits:
            for fn in random.sample(without_hits, min(3, len(without_hits))):
                filepath = data_dir / fn
                if filepath.exists():
                    selected.append((fn, "0000000", filepath))

    return selected


def build_for_language(lang, context=5):
    """为一种语言构建示例库。"""
    print(f"\n{'='*60}")
    print(f"  构建 {lang.upper()} 示例库 (context={context})")
    print(f"{'='*60}")

    benign_csv = DATA / f"{lang}_benign_scan.csv"
    webshell_csv = DATA / f"{lang}_webshell_scan.csv"
    benign_dir = DATA / lang / "benign"
    webshell_dir = DATA / lang / "webshell"

    if not benign_csv.exists() or not webshell_csv.exists():
        print(f"  [SKIP] CSV 不存在，请先运行 batch_scan.py")
        return

    benign_rows = read_scan_csv(benign_csv)
    webshell_rows = read_scan_csv(webshell_csv)
    print(f"  白样本 CSV: {len(benign_rows)} 条")
    print(f"  黑样本 CSV: {len(webshell_rows)} 条")

    # 按行为指纹聚类
    b_groups = defaultdict(list)
    w_groups = defaultdict(list)
    for fn, row in benign_rows.items():
        b_groups[fingerprint_key(get_category_counts(row))].append(fn)
    for fn, row in webshell_rows.items():
        w_groups[fingerprint_key(get_category_counts(row))].append(fn)

    print(f"  白样本指纹类型: {len(b_groups)} 种 (含命中 {len(b_groups)-1} 种)")
    print(f"  黑样本指纹类型: {len(w_groups)} 种")

    # 抽样
    random.seed(42)
    black_samples = sample_files(webshell_rows, "webshell", w_groups, webshell_dir, lang)
    white_samples = sample_files(benign_rows, "benign", b_groups, benign_dir, lang)

    print(f"  选取 Webshell 示例: {len(black_samples)} 个")
    print(f"  选取 Benign 示例:  {len(white_samples)} 个")

    # 输出 Markdown
    out_path = EXAMPLES_DIR / f"{lang}_auto_examples.md"
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(f"# {lang.upper()} Webshell Detection — Auto-Generated Examples\n\n")
        f.write(f"> 从 data_storage 数据集自动构建\n")
        f.write(f"> 白样本 {len(benign_rows)} 个 / 黑样本 {len(webshell_rows)} 个\n\n")

        # Webshell 示例
        f.write("## Webshell Examples\n\n")
        for i, (fn, fp_key, filepath) in enumerate(black_samples, 1):
            row = webshell_rows.get(fn, {})
            counts = get_category_counts(row)
            active = [c for c in UNIFIED_7 if counts[c] > 0]

            f.write(f"### Webshell {i}: `{fn}`\n\n")
            f.write(f"- **行为指纹**: `{fp_key}`\n")
            f.write(f"- **命中类别**: {', '.join(active) if active else '无'}\n")
            f.write(f"- **关键函数总数**: {row.get('total_critical_calls', '?')}\n")
            f.write(f"- **输入通道**: {'有' if row.get('has_input_channel') == '1' else '无'}\n\n")
            f.write("```\n")
            code = extract_critical_code(filepath, lang, context=context)
            f.write(code[:5000])  # 限制长度
            f.write("\n```\n\n")

        # Benign 示例
        f.write("## Benign Examples\n\n")
        for i, (fn, fp_key, filepath) in enumerate(white_samples, 1):
            row = benign_rows.get(fn, {})
            counts = get_category_counts(row)
            active = [c for c in UNIFIED_7 if counts[c] > 0]

            f.write(f"### Benign {i}: `{fn}`\n\n")
            f.write(f"- **行为指纹**: `{fp_key}`\n")
            f.write(f"- **命中类别**: {', '.join(active) if active else '无'}\n")
            f.write(f"- **关键函数总数**: {row.get('total_critical_calls', '?')}\n")
            f.write(f"- **输入通道**: {'有' if row.get('has_input_channel') == '1' else '无'}\n\n")
            f.write("```\n")
            code = extract_critical_code(filepath, lang, context=context)
            f.write(code[:5000])
            f.write("\n```\n\n")

    print(f"  → 已保存: {out_path}")

    # 同时输出 JSON 索引供 select_demo.py 使用
    json_path = EXAMPLES_DIR / f"{lang}_example_index.json"
    all_examples = black_samples + white_samples
    index_data = {}
    for fn, fp_key, filepath in all_examples:
        label = "webshell" if filepath.parent.name == "webshell" else "benign"
        row = (webshell_rows if label == "webshell" else benign_rows).get(fn, {})
        counts = get_category_counts(row)
        index_data[fn] = {
            "counts": counts,
            "label": label,
            "total": int(float(row.get("total_critical_calls", 0))),
            "has_input": row.get("has_input_channel", "0") == "1",
        }
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(index_data, f, indent=2, ensure_ascii=False)
    print(f"  → JSON 索引: {json_path} ({len(index_data)} entries)")

    return out_path


def main():
    parser = argparse.ArgumentParser(description="构建 Few-shot 示例库")
    parser.add_argument("--lang", choices=["php", "jsp", "asp", "js", "aspx"],
                        help="只构建指定语言 (php/jsp/asp/js/aspx)")
    parser.add_argument("--context", type=int, default=5,
                        help="关键函数前后取多少行（对应 BFAD τ，默认 5）")
    args = parser.parse_args()

    langs = [args.lang] if args.lang else ["php", "jsp", "asp", "js", "aspx"]
    for lang in langs:
        build_for_language(lang, context=args.context)

    print(f"\n完成。示例文件保存在: {EXAMPLES_DIR}")


if __name__ == "__main__":
    main()

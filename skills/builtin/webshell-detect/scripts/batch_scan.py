#!/usr/bin/env python3
"""
批量扫描目录下所有 PHP/JSP/ASP 文件，输出每个文件的关键函数统计 CSV。

支持三种语言的统一 7 类别输出（某些语言在特定类别上为 0）。

Usage:
    python batch_scan.py <target_dir>
    python batch_scan.py <dir> --out result.csv
"""

import argparse
import csv
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from scan_critical import scan_file, PHP_FUNCTIONS, PHP_INPUTS, JSP_PATTERNS, JSP_INPUTS, ASP_PATTERNS, ASP_INPUTS, JS_PATTERNS, JS_INPUTS, ASPX_PATTERNS, ASPX_INPUTS

# ─── 扩展名 → 扫描器映射 ─────────────────────────────────────────────────

EXT_TO_SCANNER = {
    ".php":  ("php", PHP_FUNCTIONS, PHP_INPUTS),
    ".jsp":  ("jsp", JSP_PATTERNS,  JSP_INPUTS),
    ".jspx": ("jsp", JSP_PATTERNS,  JSP_INPUTS),
    ".asp":  ("asp", ASP_PATTERNS,  ASP_INPUTS),
    ".asa":  ("asp", ASP_PATTERNS,  ASP_INPUTS),
    ".js":   ("js",  JS_PATTERNS,   JS_INPUTS),
    ".mjs":  ("js",  JS_PATTERNS,   JS_INPUTS),
    ".aspx": ("aspx", ASPX_PATTERNS, ASPX_INPUTS),
    ".ashx": ("aspx", ASPX_PATTERNS, ASPX_INPUTS),
    ".asmx": ("aspx", ASPX_PATTERNS, ASPX_INPUTS),
}

# ─── 统一 7 类别列名 ─────────────────────────────────────────────────────
# 三种语言各自有 6 类，合并去重后共 7 列。
# 不适用于某语言的列始终为 0。

UNIFIED_CATEGORIES = [
    "Code Execution",
    "Program Execution",
    "Obfuscation & Encryption",
    "Information Gathering",
    "Network Communication",
    "Callback / Reflection",
    "File Operations",
]

# 各语言类别名 → 统一列名的映射
LANG_CAT_MAP = {
    "php": {
        "Code Execution":              "Code Execution",
        "Program Execution":           "Program Execution",
        "Obfuscation & Encryption":    "Obfuscation & Encryption",
        "Information Gathering":       "Information Gathering",
        "Network Communication":       "Network Communication",
        "Callback Functions":          "Callback / Reflection",
    },
    "jsp": {
        "Code Execution":              "Code Execution",
        "Program Execution":           "Program Execution",
        "Obfuscation & Encryption":    "Obfuscation & Encryption",
        "Information Gathering":       "Information Gathering",
        "Network Communication":       "Network Communication",
        "Callback / Reflection":       "Callback / Reflection",
    },
    "asp": {
        "Code Execution":              "Code Execution",
        "Program Execution":           "Program Execution",
        "Obfuscation & Encryption":    "Obfuscation & Encryption",
        "Information Gathering":       "Information Gathering",
        "Network Communication":       "Network Communication",
        "File Operations":             "File Operations",
    },
    "js": {
        "Code Execution":              "Code Execution",
        "Program Execution":           "Program Execution",
        "Obfuscation & Encryption":    "Obfuscation & Encryption",
        "Information Gathering":       "Information Gathering",
        "Network Communication":       "Network Communication",
        "Callback / Reflection":       "Callback / Reflection",
        "File Operations":             "File Operations",
    },
    "aspx": {
        "Code Execution":              "Code Execution",
        "Program Execution":           "Program Execution",
        "Obfuscation & Encryption":    "Obfuscation & Encryption",
        "Information Gathering":       "Information Gathering",
        "Network Communication":       "Network Communication",
        "Callback / Reflection":       "Callback / Reflection",
        "File Operations":             "File Operations",
    },
}


def main():
    parser = argparse.ArgumentParser(description="批量扫描并输出统一 CSV")
    parser.add_argument("target", help="目标目录")
    parser.add_argument("--out", default=None, help="输出 CSV 路径（默认 <target>/../<dirname>_scan.csv）")
    args = parser.parse_args()

    target = Path(args.target)
    if not target.is_dir():
        print(f"Error: {args.target} 不是目录", file=sys.stderr)
        sys.exit(1)

    if args.out:
        out_path = Path(args.out)
    else:
        out_path = target.parent / f"{target.name}_scan.csv"

    # 收集文件
    files = []
    for root, _, filenames in os.walk(target):
        for fn in filenames:
            ext = Path(fn).suffix.lower()
            if ext in EXT_TO_SCANNER:
                files.append((ext, Path(root) / fn))

    print(f"找到 {len(files)} 个文件，开始扫描...")

    # 写 CSV
    with open(out_path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        header = ["filename", "language", "total_critical_calls", "has_input_channel"] + UNIFIED_CATEGORIES
        writer.writerow(header)

        for i, (ext, filepath) in enumerate(files, 1):
            lang, funcs, inputs = EXT_TO_SCANNER[ext]
            r = scan_file(str(filepath), funcs, inputs)
            cat_map = LANG_CAT_MAP[lang]

            # 把语言类别名映射到统一列名
            unified_counts = dict.fromkeys(UNIFIED_CATEGORIES, 0)
            for cat_name, info in r["categories"].items():
                uni_name = cat_map.get(cat_name, cat_name)
                if uni_name in unified_counts:
                    unified_counts[uni_name] += info["count"]

            row = [
                filepath.name,
                lang,
                r["total_critical_calls"],
                1 if r["has_input_channel"] else 0,
            ] + [unified_counts[c] for c in UNIFIED_CATEGORIES]
            writer.writerow(row)

            if i % 5000 == 0:
                print(f"  进度: {i}/{len(files)}")

    print(f"完成: {len(files)} 个文件 → {out_path}")

    # 统计摘要
    print_summary(out_path, files)


def print_summary(csv_path, files):
    print("\n" + "=" * 65)
    try:
        import pandas as pd
        df = pd.read_csv(csv_path)
        total = len(df)

        # 按语言统计
        for lang in df["language"].unique():
            dl = df[df["language"] == lang]
            active = UNIFIED_CATEGORIES if lang == "asp" else UNIFIED_CATEGORIES[:6] + []
            print(f"\n--- {lang.upper()} ({len(dl)} files) ---")
            print(f"  平均关键函数: {dl['total_critical_calls'].mean():.2f}")
            print(f"  中位数: {dl['total_critical_calls'].median():.0f}")
            print(f"  含 ≥1 关键函数: {(dl['total_critical_calls'] > 0).sum()} ({(dl['total_critical_calls'] > 0).mean()*100:.1f}%)")
            print(f"  各类别:")
            for cat in UNIFIED_CATEGORIES:
                if dl[cat].sum() > 0:
                    pct = (dl[cat] > 0).mean() * 100
                    print(f"    {cat:30s}  avg={dl[cat].mean():.4f}  hit={pct:.1f}%")

        print(f"\n整体: 平均 {df['total_critical_calls'].mean():.2f}, "
              f"中位数 {df['total_critical_calls'].median():.0f}, "
              f"有调用 {(df['total_critical_calls'] > 0).mean()*100:.1f}%")
    except ImportError:
        print("(pip install pandas 查看统计)")


if __name__ == "__main__":
    main()

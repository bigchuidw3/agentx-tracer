#!/usr/bin/env python3
"""
用扫描 CSV 数据计算三种语言各自的 7 类 WBFP 权重。

公式 (BFAD Section 3.3):
    Score_f = r_c + r_f + r_u
    w_f = Score_f / Sigma(Score_f')

其中:
    r_c = (黑样本中包含 f 的文件比例) / (白样本中包含 f 的文件比例)
    r_f = (黑样本中 f 的平均出现次数)   / (白样本中 f 的平均出现次数)
    r_u = (黑样本中 f 的总出现次数)     / (白样本中 f 的总出现次数)

对某语言不适用的类别，权重直接设为 0。

Usage:
    python compute_wbfp_weights.py
"""

import pandas as pd
import numpy as np
from pathlib import Path

from config import DATA_DIR as DATA

UNIFIED_7 = [
    "Code Execution",
    "Program Execution",
    "Obfuscation & Encryption",
    "Information Gathering",
    "Network Communication",
    "Callback / Reflection",
    "File Operations",
]

# 哪些语言不适用哪些类
ZERO_WEIGHT = {
    "php": {"File Operations"},
    "jsp": {"File Operations"},
    "asp": {"Callback / Reflection"},
}

EPS = 1e-8
CAP = 200.0  # 截断上限，防止干净白样本导致比值爆炸


def safe_ratio(num, den):
    """安全除法，分母极小时截断上限。"""
    if den < EPS:
        return CAP
    return min(num / den, CAP)


# 各语言的 CSV 列名映射（统一列名 → CSV 实际列名）
COLUMN_ALIASES = {
    "Callback / Reflection": ["Callback / Reflection", "Callback Functions"],
}


def _get_column(df, cat):
    """获取 CSV 中某统一类别的实际列名。"""
    if cat in df.columns:
        return cat
    for alias in COLUMN_ALIASES.get(cat, []):
        if alias in df.columns:
            return alias
    return None


def compute_weights(benign_csv, webshell_csv, lang):
    """对一种语言计算 7 类 WBFP 权重"""
    df_b = pd.read_csv(benign_csv)
    df_w = pd.read_csv(webshell_csv)

    rows = []
    for cat in UNIFIED_7:
        col = _get_column(df_b, cat)
        if col is None or cat in ZERO_WEIGHT.get(lang, set()):
            rows.append({"category": cat, "r_c": 0, "r_f": 0, "r_u": 0,
                         "score": 0, "weight": 0,
                         "b_pct": 0, "w_pct": 0, "b_avg": 0, "w_avg": 0})
            continue

        b_total = df_b[col].sum()
        w_total = df_w[col].sum()
        b_pct = (df_b[col] > 0).mean()
        w_pct = (df_w[col] > 0).mean()
        b_avg = df_b[col].mean()
        w_avg = df_w[col].mean()

        r_c = safe_ratio(w_pct, b_pct)
        r_f = safe_ratio(w_avg, b_avg)
        r_u = safe_ratio(w_total, b_total)

        score = r_c + r_f + r_u
        rows.append({
            "category": cat,
            "r_c": r_c, "r_f": r_f, "r_u": r_u,
            "score": score,
            "weight": 0,  # 稍后归一化
            "b_pct": b_pct * 100, "w_pct": w_pct * 100,
            "b_avg": b_avg, "w_avg": w_avg,
        })

    df = pd.DataFrame(rows)
    # 归一化（仅对有效类别）
    total_score = df["score"].sum()
    if total_score > 0:
        df["weight"] = df["score"] / total_score
    return df


def main():
    configs = [
        ("php",  DATA / "php_benign_scan.csv",  DATA / "php_webshell_scan.csv"),
        ("jsp",  DATA / "jsp_benign_scan.csv",   DATA / "jsp_webshell_scan.csv"),
        ("asp",  DATA / "asp_benign_scan.csv",   DATA / "asp_webshell_scan.csv"),
        ("aspx", DATA / "aspx_benign_scan.csv",  DATA / "aspx_webshell_scan.csv"),
    ]

    # 论文参考值
    paper_weights = {
        "Code Execution":              0.2081,
        "Program Execution":           0.2068,
        "Information Gathering":       0.1861,
        "Obfuscation & Encryption":    0.1702,
        "Network Communication":       0.1498,
        "Callback Functions":          0.0790,
    }

    for lang, benign_csv, webshell_csv in configs:
        print(f"\n{'='*70}")
        print(f"  {lang.upper()} — WBFP 7 类权重")
        print(f"{'='*70}")

        df = compute_weights(benign_csv, webshell_csv, lang)

        # 打印表格
        header = f"{'Category':30s} {'白%':>6s} {'黑%':>6s} {'白avg':>8s} {'黑avg':>8s} {'r_c':>8s} {'r_f':>8s} {'r_u':>8s} {'Weight':>8s} {'论文':>8s}"
        print(header)
        print("-" * len(header))

        for _, row in df.iterrows():
            pw = paper_weights.get(row["category"], None)
            pw_str = f"{pw:.4f}" if pw else "—"
            # 映射类别名到论文名
            mapped_cat = row["category"]
            if row["category"] == "Callback / Reflection":
                mapped_cat = "Callback Functions"
            pw = paper_weights.get(mapped_cat, None)
            pw_str = f"{pw:.4f}" if pw else "—"

            print(f"{row['category']:30s} {row['b_pct']:5.1f}% {row['w_pct']:5.1f}% "
                  f"{row['b_avg']:8.4f} {row['w_avg']:8.4f} "
                  f"{row['r_c']:8.2f} {row['r_f']:8.2f} {row['r_u']:8.2f} "
                  f"{row['weight']:8.4f} {pw_str:>8s}")


if __name__ == "__main__":
    main()

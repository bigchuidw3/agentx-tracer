#!/usr/bin/env python3
"""Build a comprehensive test report from batch scan CSVs."""

import pandas as pd
import os
import sys

BASE = 'F:/asainfo-sec/llm-based-webshell-detect/data_storage'
CATEGORIES = [
    'Code Execution', 'Program Execution', 'Obfuscation & Encryption',
    'Information Gathering', 'Network Communication', 'Callback / Reflection',
    'File Operations'
]


def load_csv(path):
    df = pd.read_csv(path)
    df.columns = df.columns.str.lstrip('﻿')
    return df


def compute_metrics(b_df, w_df):
    """Compute classification metrics from benign and webshell dataframes."""
    # Heuristic: suspicious if (has_input_channel) AND (Code Execution > 0 OR Program Execution > 0)
    b_df = b_df.copy()
    w_df = w_df.copy()

    b_df['pred_sus'] = (b_df['has_input_channel'] == 1) & ((b_df['Code Execution'] > 0) | (b_df['Program Execution'] > 0))
    w_df['pred_sus'] = (w_df['has_input_channel'] == 1) & ((w_df['Code Execution'] > 0) | (w_df['Program Execution'] > 0))

    # True labels
    b_df['true_label'] = 0  # benign
    w_df['true_label'] = 1  # webshell

    # Predicted labels
    b_df['pred_label'] = b_df['pred_sus'].astype(int)
    w_df['pred_label'] = w_df['pred_sus'].astype(int)

    # Confusion matrix
    TP = w_df['pred_label'].sum()
    FN = len(w_df) - TP
    FP = b_df['pred_label'].sum()
    TN = len(b_df) - FP

    total = TP + FN + FP + TN
    accuracy = (TP + TN) / total if total > 0 else 0
    precision = TP / (TP + FP) if (TP + FP) > 0 else 0
    recall = TP / (TP + FN) if (TP + FN) > 0 else 0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0
    fpr = FP / (FP + TN) if (FP + TN) > 0 else 0
    tnr = TN / (TN + FP) if (TN + FP) > 0 else 0

    return {
        'TP': TP, 'FN': FN, 'FP': FP, 'TN': TN,
        'accuracy': accuracy, 'precision': precision, 'recall': recall,
        'f1': f1, 'fpr': fpr, 'tnr': tnr,
        'b_df': b_df, 'w_df': w_df,
    }


def category_stats(b_df, w_df):
    """Per-category hit rate stats."""
    rows = []
    for cat in CATEGORIES:
        if cat in b_df.columns and cat in w_df.columns:
            b_hit = (b_df[cat] > 0).mean() * 100
            w_hit = (w_df[cat] > 0).mean() * 100
            b_avg = b_df[cat].mean()
            w_avg = w_df[cat].mean()
            rows.append((cat, b_hit, w_hit, b_avg, w_avg))
    return rows


def safe_col(df, col, default=0):
    """Get column value safely, returning default if column missing."""
    if col in df.columns:
        return df[col]
    return default


def safe_sum(df, col):
    """Sum column safely."""
    if col in df.columns:
        return df[col].sum()
    return 0


def safe_mean(df, col):
    """Mean of column safely."""
    if col in df.columns:
        return df[col].mean()
    return 0.0


def main():
    results = {}
    for lang in ['jsp', 'php', 'asp']:
        benign_path = os.path.join(BASE, f'{lang}_benign_scan.csv')
        ws_path = os.path.join(BASE, f'{lang}_webshell_scan.csv')

        b_df = load_csv(benign_path)
        w_df = load_csv(ws_path)

        metrics = compute_metrics(b_df, w_df)
        metrics['cat_stats'] = category_stats(b_df, w_df)
        metrics['b_df'] = b_df
        metrics['w_df'] = w_df
        results[lang] = metrics

    # ─────────────────────────────────────────────────
    # Generate Report
    # ─────────────────────────────────────────────────
    print("=" * 80)
    print("  BFAD Webshell Detection — Full Test Report")
    print("=" * 80)
    print(f"  Date: 2026-06-28")
    print(f"  Heuristic: suspicious = has_input_channel AND (Code_Execution > 0 OR Program_Execution > 0)")
    print()

    # ── Per-Language Summary ──
    for lang in ['jsp', 'php', 'asp']:
        m = results[lang]
        b = m['b_df']
        w = m['w_df']
        print("-" * 80)
        print(f"  [{lang.upper()}]  Benign: {len(b)}  |  Webshell: {len(w)}")
        print("-" * 80)
        print()

        # Data overview
        print(f"  ── Data Overview ──")
        obf_col = 'Obfuscation & Encryption'
        has_obf_col = obf_col in b.columns
        for label, df in [('Benign', b), ('Webshell', w)]:
            print(f"    {label}:")
            print(f"      Avg critical calls:        {df['total_critical_calls'].mean():.2f}")
            print(f"      Median critical calls:     {df['total_critical_calls'].median():.0f}")
            print(f"      Max critical calls:        {df['total_critical_calls'].max():.0f}")
            has_any = (df['total_critical_calls'] > 0).mean() * 100
            print(f"      Files with >=1 call:       {(df['total_critical_calls'] > 0).sum()} ({has_any:.1f}%)")
            has_in = (df['has_input_channel'] == 1).mean() * 100
            print(f"      Has input channel:         {(df['has_input_channel'] == 1).sum()} ({has_in:.1f}%)")
            if has_obf_col:
                obf_sum = (df[obf_col] > 0).sum()
                obf_pct = (df[obf_col] > 0).mean() * 100
                print(f"      Has obfuscation:           {obf_sum} ({obf_pct:.1f}%)")
            has_exe = ((df['Code Execution'] > 0) | (df['Program Execution'] > 0)).mean() * 100
            exe_count = ((df['Code Execution'] > 0) | (df['Program Execution'] > 0)).sum()
            print(f"      Has execution primitive:   {exe_count} ({has_exe:.1f}%)")
            in_exe = ((df['has_input_channel'] == 1) & ((df['Code Execution'] > 0) | (df['Program Execution'] > 0))).sum()
            in_exe_pct = ((df['has_input_channel'] == 1) & ((df['Code Execution'] > 0) | (df['Program Execution'] > 0))).mean() * 100
            print(f"      Input + Execution (heuristic suspicious): {in_exe} ({in_exe_pct:.1f}%)")
            if has_obf_col:
                in_obf_exe = ((df['has_input_channel'] == 1) & (df[obf_col] > 0) & ((df['Code Execution'] > 0) | (df['Program Execution'] > 0))).sum()
                in_obf_exe_pct = ((df['has_input_channel'] == 1) & (df[obf_col] > 0) & ((df['Code Execution'] > 0) | (df['Program Execution'] > 0))).mean() * 100
                print(f"      Full chain (In+Obf+Exe):   {in_obf_exe} ({in_obf_exe_pct:.1f}%)")
            print()

        # Category breakdown
        print(f"  ── Category Hit Rates ──")
        print(f"    {'Category':35s} {'Benign':>8s} {'Webshell':>8s}  {'B_avg':>8s} {'W_avg':>8s}")
        print(f"    {'─'*33}  {'─'*8} {'─'*8}  {'─'*8} {'─'*8}")
        for cat, b_hit, w_hit, b_avg, w_avg in m['cat_stats']:
            print(f"    {cat:35s} {b_hit:7.1f}% {w_hit:7.1f}%  {b_avg:8.4f} {w_avg:8.4f}")
        print()

        # Fingerprint distribution
        print(f"  ── Fingerprint Distribution (top 10) ──")
        # Build fingerprint using only columns that exist
        avail_cats = [c for c in CATEGORIES if c in b.columns]
        for label, df in [('Benign', b), ('Webshell', w)]:
            fps = []
            for _, row in df.iterrows():
                fp = ''.join('1' if row[c] > 0 else '0' for c in avail_cats)
                fps.append(fp)
            fp_counts = pd.Series(fps).value_counts().head(10)
            print(f"    {label}:")
            for fp, cnt in fp_counts.items():
                print(f"      {fp}  →  {cnt:6d}  ({cnt/len(df)*100:.1f}%)")
            print()

        # Classification metrics
        print(f"  ── BFAD Heuristic Classification Metrics ──")
        print(f"    {'Metric':20s} {'Value':>10s}")
        print(f"    {'─'*20} {'─'*11}")
        print(f"    {'TP (Webshell→Sus)':20s} {m['TP']:10d}")
        print(f"    {'FN (Webshell→Benign)':20s} {m['FN']:10d}")
        print(f"    {'FP (Benign→Sus)':20s} {m['FP']:10d}")
        print(f"    {'TN (Benign→Benign)':20s} {m['TN']:10d}")
        print(f"    {'─'*20} {'─'*11}")
        print(f"    {'Accuracy':20s} {m['accuracy']:10.4f}")
        print(f"    {'Precision':20s} {m['precision']:10.4f}")
        print(f"    {'Recall (TPR)':20s} {m['recall']:10.4f}")
        print(f"    {'F1 Score':20s} {m['f1']:10.4f}")
        print(f"    {'FPR (False Pos)':20s} {m['fpr']:10.4f}")
        print(f"    {'TNR (True Neg)':20s} {m['tnr']:10.4f}")
        print()

    # ── Overall Summary ──
    print("=" * 80)
    print("  Overall Summary")
    print("=" * 80)
    print()
    print(f"  {'Language':10s} {'Benign':>8s} {'Webshell':>8s} {'Accuracy':>10s} {'Precision':>10s} {'Recall':>10s} {'F1':>10s} {'FPR':>10s}")
    print(f"  {'─'*10} {'─'*8} {'─'*8} {'─'*10} {'─'*10} {'─'*10} {'─'*10} {'─'*10}")
    for lang in ['jsp', 'php', 'asp']:
        m = results[lang]
        b_cnt = len(m['b_df'])
        w_cnt = len(m['w_df'])
        print(f"  {lang.upper():10s} {b_cnt:8d} {w_cnt:8d} {m['accuracy']:10.4f} {m['precision']:10.4f} {m['recall']:10.4f} {m['f1']:10.4f} {m['fpr']:10.4f}")

    # Macro average
    print(f"  {'─'*10} {'─'*8} {'─'*8} {'─'*10} {'─'*10} {'─'*10} {'─'*10} {'─'*10}")
    avg_acc = sum(results[l]['accuracy'] for l in ['jsp', 'php', 'asp']) / 3
    avg_prec = sum(results[l]['precision'] for l in ['jsp', 'php', 'asp']) / 3
    avg_rec = sum(results[l]['recall'] for l in ['jsp', 'php', 'asp']) / 3
    avg_f1 = sum(results[l]['f1'] for l in ['jsp', 'php', 'asp']) / 3
    avg_fpr = sum(results[l]['fpr'] for l in ['jsp', 'php', 'asp']) / 3
    print(f"  {'AVERAGE':10s} {'':>8s} {'':>8s} {avg_acc:10.4f} {avg_prec:10.4f} {avg_rec:10.4f} {avg_f1:10.4f} {avg_fpr:10.4f}")
    print()
    print("=" * 80)


if __name__ == "__main__":
    main()

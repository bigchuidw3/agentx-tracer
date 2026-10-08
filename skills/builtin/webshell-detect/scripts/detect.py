#!/usr/bin/env python3
"""
端到端 BFAD Webshell 检测。

输入一个文件 → 输出完整检测报告：
  Step 1: 关键函数扫描 → 行为指纹
  Step 2: 上下文感知代码提取
  Step 3: WBFP 选择最佳 ICL 示例
  Step 4: 组装 LLM Prompt

Usage:
    python detect.py <file>                          # 单文件，输出 Markdown 报告
    python detect.py <file> --json                   # JSON 输出
    python detect.py <file> --context 10 --top 5     # 自定义参数
    python detect.py <dir> --lang php --json         # 批量检测
"""

import argparse
import json
import re
import sys
import io
from pathlib import Path

# 修复 Windows GBK 编码问题
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

sys.path.insert(0, str(Path(__file__).parent))

from scan_critical import scan_file, PHP_FUNCTIONS, PHP_INPUTS, \
    JSP_PATTERNS, JSP_INPUTS, ASP_PATTERNS, ASP_INPUTS, \
    JS_PATTERNS, JS_INPUTS, ASPX_PATTERNS, ASPX_INPUTS
from select_demo import (
    build_example_index, select_examples, UNIFIED_7, WBFP_WEIGHTS
)
from build_example_lib import extract_critical_code
from config import DATA_DIR

LANG_SCANNERS = {
    "php":  (PHP_FUNCTIONS, PHP_INPUTS),
    "jsp":  (JSP_PATTERNS,  JSP_INPUTS),
    "asp":  (ASP_PATTERNS,  ASP_INPUTS),
    "js":   (JS_PATTERNS,   JS_INPUTS),
    "aspx": (ASPX_PATTERNS, ASPX_INPUTS),
}

EXT_TO_LANG = {
    ".php": "php", ".jsp": "jsp", ".jspx": "jsp",
    ".asp": "asp", ".asa": "asp",
    ".js": "js", ".mjs": "js",
    ".aspx": "aspx", ".ashx": "aspx", ".asmx": "aspx",
}

# 内容特征：按优先级匹配，先命中的为准（至少命中 2 个模式才确认）
CONTENT_SIGNATURES = [
    ("php", [r'<\?php', r'\$_GET\b', r'\$_POST\b', r'\$_REQUEST\b',
             r'\beval\s*\(', r'\bsystem\s*\(', r'\bfunction\s+\w+\s*\(']),
    ("jsp", [r'<%@\s', r'<%\s', r'<jsp:', r'\brequest\.getParameter\s*\(',
             r'\bRuntime\.getRuntime\(\)', r'\bout\.print', r'\.jspx?']),
    ("asp", [r'<%\s', r'\bServer\.CreateObject\s*\(',
             r'\bRequest\.Form\s*\(', r'\bResponse\.Write\s*\(',
             r'\bScripting\.FileSystemObject\b']),
    ("js", [r'\brequire\s*\(', r'\bmodule\.exports', r'\beval\s*\(',
            r'\bprocess\.env\b', r'\bconsole\.log', r'\bchild_process\b']),
    ("aspx", [r'<%@\s*Page', r'\bSystem\.Web\.UI\.Page\b',
              r'\bRunat\s*=\s*"server"', r'\bSystem\.Diagnostics\b',
              r'\bAssembly\.Load\b']),
]


def detect_language(filepath: Path):
    """检测代码语言：先看扩展名，未知扩展名则读文件内容判断。"""
    ext = filepath.suffix.lower()
    if ext in EXT_TO_LANG:
        return EXT_TO_LANG[ext], f"extension {ext}"

    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            head = f.read(4096)
    except Exception:
        return "php", "fallback (unreadable)"

    for lang, patterns in CONTENT_SIGNATURES:
        score = sum(1 for p in patterns if re.search(p, head, re.IGNORECASE))
        if score >= 2:
            return lang, f"content (matched {score} patterns)"

    return "php", "fallback (default)"


def random_sample_code(filepath, lang, num_blocks=5):
    """指纹全零时，从文件中随机采样几个代码块供 LLM 审查。

    优先采样函数/方法定义，其次采样非空非注释行集中区域。
    每个 block 约 8-15 行。
    """
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()
    except Exception:
        return "// Unable to read source"

    if not lines:
        return "// Empty file"

    n = len(lines)
    # 取 num_blocks 个均匀分布的位置
    step = max(1, n // (num_blocks + 1))
    starts = [min(n - 1, step * i) for i in range(1, num_blocks + 1)]

    blocks = []
    for s in starts:
        # 从 start 向前找最近的函数/方法定义
        block_start = min(s, n - 1)
        for j in range(block_start, max(0, block_start - 30), -1):
            stripped = lines[j].strip()
            if stripped and not stripped.startswith(("//", "#", "/*", "*", "<!--")):
                # 找到非注释行作为起点
                block_start = max(0, j - 2)
                break

        # 取 10-15 行
        block_end = min(n, block_start + 12)
        # 尝试在函数/方法末尾截断
        for j in range(block_start, min(block_end, n)):
            if lines[j].strip() in ("}", "%)>", "end", "next", "loop"):
                block_end = j + 1
                break

        snippet = "".join(lines[block_start:block_end]).rstrip()
        if snippet.strip():
            blocks.append(f"// --- lines {block_start+1}-{block_end} ---\n{snippet}")

    return "\n\n".join(blocks[:num_blocks])


def detect_file(filepath, context=5, top_k=5):
    """对单个文件执行完整 BFAD 检测管线。"""
    path = Path(filepath)
    lang, lang_source = detect_language(path)
    funcs, inputs = LANG_SCANNERS[lang]

    # ── Step 1: 扫描 ──
    r = scan_file(str(path), funcs, inputs)

    target_counts = dict.fromkeys(UNIFIED_7, 0)
    for cat_name, info in r["categories"].items():
        mapped = cat_name
        if cat_name == "Callback Functions":
            mapped = "Callback / Reflection"
        if mapped in target_counts:
            target_counts[mapped] = info["count"]

    total_calls = r["total_critical_calls"]
    has_input = r["has_input_channel"]
    active = [c for c in UNIFIED_7 if target_counts[c] > 0]
    fingerprint = "".join("1" if target_counts[c] > 0 else "0" for c in UNIFIED_7)

    # ── Step 2: 提取代码 ──
    if fingerprint == "0000000":
        # 无关键函数命中 → 随机采样几个函数/代码块给 LLM 看
        extracted_code = random_sample_code(str(path), lang, num_blocks=5)
    else:
        extracted_code = extract_critical_code(str(path), lang, context=context, max_output=200)

    # ── Step 3: 选示例 ──
    example_index = build_example_index(lang)
    selected, _ = select_examples(path, lang, example_index, top_k)

    # ── Step 4: 组装 Prompt ──
    # 取已选示例的文件内容
    demo_entries = []
    data_dir = DATA_DIR
    for sim, fn, label, counts, total in selected:
        demo_path = data_dir / lang / label / fn
        demo_code = ""
        if demo_path.exists():
            demo_code = extract_critical_code(str(demo_path), lang, context=context)
            demo_code = demo_code[:3000]  # 截断
        demo_entries.append({
            "filename": fn,
            "label": label,
            "similarity": round(sim, 4),
            "code": demo_code,
        })

    return {
        "file": str(path),
        "language": lang,
        "fingerprint": fingerprint,
        "active_categories": active,
        "target_counts": target_counts,
        "total_critical_calls": total_calls,
        "has_input_channel": has_input,
        "extracted_code": extracted_code[:15000],
        "top_examples": demo_entries,
        "weights_used": WBFP_WEIGHTS.get(lang, {}),
    }


def format_markdown(result):
    """格式化为 Markdown 报告。"""
    lines = []
    lines.append(f"# BFAD Webshell Detection Report\n")
    lines.append(f"**File**: `{result['file']}`\n")
    lines.append(f"**Language**: {result['language'].upper()}\n")

    # Step 1
    lines.append(f"## Step 1: Behavioral Fingerprint\n")
    lines.append(f"- **Fingerprint**: `{result['fingerprint']}`")
    lines.append(f"- **Total critical calls**: {result['total_critical_calls']}")
    lines.append(f"- **Has input channel**: {'YES' if result['has_input_channel'] else 'NO'}")
    lines.append(f"- **Category breakdown**:")
    counts = result.get('target_counts', {})
    for cat in UNIFIED_7:
        cnt = counts.get(cat, 0)
        if cnt > 0:
            w = result['weights_used'].get(cat, 0)
            lines.append(f"  - {cat}: **{cnt}** calls (weight: {w:.4f})")
    if not any(counts.get(c, 0) > 0 for c in UNIFIED_7):
        lines.append(f"  - (clean)")

    # 初步判断
    has_exec = "Code Execution" in result['active_categories'] or "Program Execution" in result['active_categories']
    has_transform = "Obfuscation & Encryption" in result['active_categories']
    chain = ""
    if result['has_input_channel'] and has_exec:
        chain = "Input → " + ("Transform → " if has_transform else "") + "Execute **(FULL ATTACK CHAIN)**"
    elif result['has_input_channel']:
        chain = "Input only (no execution layer)"
    elif has_exec:
        chain = "Execute only (no input channel)"
    else:
        chain = "No suspicious chain"
    lines.append(f"- **Chain analysis**: {chain}")
    lines.append(f"- **BFAD heuristic**: {'SUSPICIOUS' if result['has_input_channel'] and has_exec else 'LIKELY BENIGN'}")

    # WBFP 权重
    lines.append(f"\n### WBFP Weights Used\n")
    lines.append(f"| Category | Weight |")
    lines.append(f"|----------|--------|")
    for cat in UNIFIED_7:
        w = result['weights_used'].get(cat, 0)
        marker = " ←" if cat in result['active_categories'] else ""
        lines.append(f"| {cat} | {w:.4f}{marker} |")

    # Step 2
    lines.append(f"\n## Step 2: Extracted Critical Code\n")
    lines.append(f"```")
    lines.append(result['extracted_code'] or "(no critical code found)")
    lines.append(f"```")

    # Step 3
    lines.append(f"\n## Step 3: Top ICL Examples (WBFP)\n")
    for i, demo in enumerate(result['top_examples'], 1):
        lines.append(f"### Example {i}: `{demo['filename']}`")
        lines.append(f"- **Label**: `{demo['label']}`")
        lines.append(f"- **Similarity**: {demo['similarity']:.4f}")
        lines.append(f"\n```")
        lines.append(demo['code'] or "(unable to extract)")
        lines.append(f"```\n")

    # Step 4: Ready-to-use Prompt
    lines.append(f"\n## Step 4: LLM Prompt (ready to copy)\n")
    lines.append(f"```")
    lines.append(f"System: You are tasked with analyzing code scripts. Your objective is to")
    lines.append(f"classify the provided code as either a WebShell or a legitimate script.")
    lines.append(f"A WebShell is a malicious script intended to exploit the server by")
    lines.append(f"executing unauthorized commands or providing backdoor access.\n")
    lines.append(f"User: Analyze the provided code to determine whether it constitutes a")
    lines.append(f"WebShell or a benign script.\n")

    # 行为指纹摘要
    lines.append(f"[Behavioral Fingerprint]")
    lines.append(f"Language: {result['language'].upper()}")
    lines.append(f"Input channel detected: {'YES' if result['has_input_channel'] else 'NO'}")
    lines.append(f"Critical function calls by category:")
    counts = result.get('target_counts', {})
    total = result['total_critical_calls']
    for cat in UNIFIED_7:
        cnt = counts.get(cat, 0)
        w = result['weights_used'].get(cat, 0)
        bar = "█" * min(cnt, 20) if cnt > 0 else ""
        lines.append(f"  {cat:30s}  x{cnt:>4d}  (weight: {w:.4f})  {bar}")
    lines.append(f"  {'─'*30}  ─────")
    lines.append(f"  {'TOTAL':30s}  x{total:>4d}")
    lines.append(f"")

    lines.append(f"[Critical Code]")
    lines.append((result['extracted_code'] or "(empty)")[:10000])
    lines.append(f"\n[Examples]")
    for i, demo in enumerate(result['top_examples'], 1):
        lines.append(f"Example {i} ({demo['label']}, sim={demo['similarity']:.4f}):")
        lines.append(demo['code'][:1200] if demo['code'] else "(empty)")
        lines.append("")
    lines.append(f"Output:")
    lines.append(f"- Verdict: [WebShell / Benign]")
    lines.append(f"- Confidence: [1-100]")
    lines.append(f"- Attack Chain: [input → transform → execute]")
    lines.append(f"- Key Evidence: [specific lines/functions]")
    lines.append(f"```")

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="端到端 BFAD Webshell 检测")
    parser.add_argument("target", help="待测文件或目录")
    parser.add_argument("--lang", choices=["php", "jsp", "asp"], help="语言")
    parser.add_argument("--context", type=int, default=5, help="上下文行数 (默认 5)")
    parser.add_argument("--top", type=int, default=5, help="ICL 示例数 (默认 5)")
    parser.add_argument("--json", action="store_true", help="JSON 输出")
    parser.add_argument("--report", action="store_true", help="输出 Markdown 报告")
    args = parser.parse_args()

    target = Path(args.target)

    # 收集文件
    if target.is_file():
        targets = [target]
    else:
        targets = []
        # 收集所有潜在代码文件（含 .txt 等，语言由内容检测判断）
        code_exts = {".php", ".jsp", ".jspx", ".asp", ".asa", ".txt", ".inc", ".phtml",
                      ".js", ".mjs", ".aspx", ".ashx", ".asmx"}
        for f in target.rglob("*"):
            if f.is_file() and f.suffix.lower() in code_exts:
                targets.append(f)
        if not targets:
            print("No code files found.", file=sys.stderr)
            sys.exit(1)

    results = []
    for t in targets:
        lang = args.lang or EXT_TO_LANG.get(t.suffix.lower(), "php")
        if lang not in LANG_SCANNERS:
            continue
        r = detect_file(t, context=args.context, top_k=args.top)
        if args.json:
            results.append(r)
        elif args.report:
            print(format_markdown(r))
        else:
            # 简洁输出
            active = ", ".join(r['active_categories']) or "clean"
            has_exec = "Code Execution" in r['active_categories'] or "Program Execution" in r['active_categories']
            sus = "SUSPICIOUS" if r['has_input_channel'] and has_exec else "likely benign"
            top_label = r['top_examples'][0]['label'] if r['top_examples'] else "N/A"
            top_sim = r['top_examples'][0]['similarity'] if r['top_examples'] else 0
            print(f"{Path(t).name:45s} fp={r['fingerprint']:7s}  calls={r['total_critical_calls']:5d}  "
                  f"top={top_label:8s} sim={top_sim:.4f}  [{sus}]")

    if args.json:
        print(json.dumps(results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

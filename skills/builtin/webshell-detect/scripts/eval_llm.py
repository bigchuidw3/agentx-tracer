#!/usr/bin/env python3
"""
Skill 准确度评估 — 含 LLM 判定。

对每个采样文件：
1. 运行 detect.py --report 生成完整 Prompt
2. 保存到 Markdown 文件，供 Claude 读取并做出最终判定
3. 汇总判定结果与 ground truth 对比

Usage:
    python eval_llm.py --lang php --n 10        # 采样 10 black + 10 white
    python eval_llm.py --lang php --n 10 --out results/  # 指定输出目录
"""

import argparse
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from detect import detect_file

from config import DATA_DIR as DATA, SKILL_ROOT as BASE


def collect_samples(lang, n_per_class):
    """收集黑白样本。"""
    samples = {}
    for label in ("benign", "webshell"):
        d = DATA / lang / label
        if not d.exists():
            print(f"[SKIP] {d} not found")
            continue
        files = list(d.iterdir())
        if len(files) < n_per_class:
            print(f"[WARN] {lang}/{label}: only {len(files)} files, using all")
            samples[label] = files
        else:
            samples[label] = random.sample(files, n_per_class)
    return samples


def generate_eval_prompts(lang, n_per_class, out_dir, context=5):
    """为采样样本生成 LLM 判定用的独立 Markdown 文件。"""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    samples = collect_samples(lang, n_per_class)
    all_tasks = []

    for label, files in samples.items():
        for fp in files:
            r = detect_file(str(fp), context=context, top_k=5)
            fingerprint = r['fingerprint']
            calls = r['total_critical_calls']
            input_ch = r['has_input_channel']
            active = r['active_categories']
            code = r['extracted_code']
            examples = r['top_examples']

            # 生成简洁的判定用 Markdown
            md = f"""# File: {fp.name}
**Ground Truth**: `{label}` (hidden — do not look)
**Language**: {lang.upper()}

## Behavioral Fingerprint
- Fingerprint: `{fingerprint}`
- Total critical calls: **{calls}**
- Input channel: {'**YES**' if input_ch else 'NO'}
- Active: {', '.join(active) if active else '(clean)'}

## Extracted Code
```
{code[:6000]}
```

## WBFP Top Examples
"""
            for i, ex in enumerate(examples[:5], 1):
                ex_code = (ex.get('code', '') or '')[:2000]
                md += f"""
### Example {i}: [{ex['label']}] sim={ex['similarity']:.4f} — `{ex['filename']}`
```
{ex_code}
```
"""

            md += """
---
## Your Verdict

Analyze the code above. Consider:
1. Is there an `input → transform → execute` chain?
2. Does it match the Webshell examples, or the Benign examples?
3. Are the critical functions used in a malicious or legitimate way?

Reply with ONLY this one line:
**VERDICT: webshell|benign | CONFIDENCE: 0-100 | REASON: one sentence**
"""

            # 写文件
            fname = f"{lang}_{label}_{fp.name}.md"
            out_path = out_dir / fname
            out_path.write_text(md, encoding="utf-8")

            all_tasks.append({
                "file": str(fp),
                "truth": label,
                "eval_file": str(out_path),
                "fingerprint": fingerprint,
                "calls": calls,
                "has_input": input_ch,
                "active": active,
            })

    # 写任务索引
    index_path = out_dir / "_INDEX.md"
    with open(index_path, "w", encoding="utf-8") as f:
        f.write(f"# LLM Eval Tasks — {lang.upper()} ({n_per_class}+{n_per_class} samples)\n\n")
        f.write("| # | File | Truth | FP | Calls | Input | Active |\n")
        f.write("|---|------|-------|----|-------|-------|--------|\n")
        for i, t in enumerate(all_tasks, 1):
            fp_short = t['fingerprint']
            f.write(f"| {i} | {Path(t['file']).name} | ? | {fp_short} | "
                    f"{t['calls']} | {'Y' if t['has_input'] else 'N'} | "
                    f"{', '.join(t['active']) if t['active'] else '-'} |\n")

    print(f"Generated {len(all_tasks)} eval prompts → {out_dir}")
    print(f"Index: {index_path}")
    return all_tasks


def main():
    parser = argparse.ArgumentParser(description="LLM Skill 准确度评估")
    parser.add_argument("--lang", choices=["php", "jsp", "asp"], default="php")
    parser.add_argument("--n", type=int, default=10, help="每类采样数")
    parser.add_argument("--out", default=None, help="输出目录")
    parser.add_argument("--context", type=int, default=5)
    args = parser.parse_args()

    random.seed(42)
    out_dir = args.out or str(BASE / f"eval_llm_{args.lang}_{args.n}")
    generate_eval_prompts(args.lang, args.n, out_dir, context=args.context)


if __name__ == "__main__":
    main()

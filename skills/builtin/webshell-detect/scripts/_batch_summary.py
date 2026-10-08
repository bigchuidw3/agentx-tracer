#!/usr/bin/env python3
"""批量读取 eval_llm 生成的 .md 文件，提取关键信息供 Claude 判定。"""
import sys
from pathlib import Path

eval_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("eval_llm_php_10")

for md_file in sorted(eval_dir.glob("php_*.md")):
    content = md_file.read_text(encoding="utf-8")

    # 提取 truth
    truth_line = [l for l in content.split('\n') if 'Ground Truth' in l]
    truth = truth_line[0].split('`')[1] if truth_line else "?"

    # 提取关键行
    fp = "?"
    calls = "?"
    inp = "?"
    active = "?"
    for line in content.split('\n'):
        if 'Fingerprint:' in line and '`' in line:
            fp = line.split('`')[1]
        if 'Total critical calls:' in line:
            calls = line.split('**')[1] if '**' in line else line.split(':')[-1].strip()
        if 'Input channel:' in line:
            inp = 'YES' if 'YES' in line else 'NO'
        if line.startswith('- Active:'):
            active = line.split('Active:')[1].strip()

    # 提取代码段的前 300 字符做预览
    code_start = content.find('## Extracted Code')
    code_preview = ""
    if code_start > 0:
        code_section = content[code_start:]
        code_block_start = code_section.find('```')
        code_block_end = code_section.find('```', code_block_start + 3)
        code = code_section[code_block_start+3:code_block_end].strip()
        # 去掉注释行，取前 200 字符
        code_lines = [l for l in code.split('\n') if l.strip() and not l.strip().startswith('//')]
        code_preview = ' '.join(code_lines)[:200]

    print(f"[{truth:8s}] fp={fp:7s} calls={calls:>4s} input={inp:3s} | {active}")
    print(f"  Code: {code_preview[:180]}")
    print()

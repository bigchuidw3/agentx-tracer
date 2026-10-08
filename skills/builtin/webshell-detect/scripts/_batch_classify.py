#!/usr/bin/env python3
"""批量评估辅助：按复杂度分层，边界案例标出供人工审核。"""
import sys, json
from pathlib import Path
from collections import Counter

eval_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("eval_llm_php_50")

results = {"tp": 0, "tn": 0, "fp": 0, "fn": 0, "borderline": [], "details": []}

# 指纹全零 + 无输入 → 自动判定 Benign（极高置信度）
AUTO_BENIGN_FINGERPRINTS = {"0000000"}

# 明显 Webshell 模式（Code/Program Execution + 有 input + 含 eval/assert/system）
WEBSHELL_KEYWORDS = [
    r"eval\s*\(", r"assert\s*\(", r"system\s*\(",
    r"\$_POST\[", r"\$_GET\[", r"\$_REQUEST\[",
    r"base64_decode", r"call_user_func",
    r"str_replace.*assert", r"strrev",
]

import re

for md_file in sorted(eval_dir.glob("php_*.md")):
    content = md_file.read_text(encoding="utf-8")

    # 提取 truth
    truth_line = [l for l in content.split('\n') if 'Ground Truth' in l]
    truth = truth_line[0].split('`')[1] if truth_line else "?"

    # 提取 FP 和关键信息
    fp = "?"
    calls = 0
    inp = False
    active = ""
    for line in content.split('\n'):
        if 'Fingerprint:' in line and '`' in line:
            fp = line.split('`')[1]
        if 'Total critical calls:' in line:
            try:
                calls = int(line.split('**')[1])
            except:
                pass
        if 'Input channel:' in line:
            inp = 'YES' in line
        if line.startswith('- Active:'):
            active = line.split('Active:')[1].strip()

    # 提取代码
    code_start = content.find('## Extracted Code')
    code = ""
    if code_start > 0:
        code_section = content[code_start:]
        cs = code_section.find('```')
        ce = code_section.find('```', cs + 3)
        code = code_section[cs+3:ce].strip()

    # 分层判定
    has_exec = "Code Execution" in active or "Program Execution" in active
    has_obf = "Obfuscation" in active

    # 检查 Webshell 关键词
    ws_score = sum(1 for kw in WEBSHELL_KEYWORDS if re.search(kw, code, re.IGNORECASE))

    # 自动判定逻辑
    auto_verdict = None
    confidence = 0
    reason = ""

    if fp == "0000000" and calls == 0 and ws_score == 0:
        auto_verdict = "benign"
        confidence = 99
        reason = "clean fingerprint, zero calls, no webshell keywords"
    elif fp == "0000000" and ws_score >= 2:
        # 全零指纹但有 Webshell 关键词 → 边界案例（如 Cookie 动态调用、str_replace 构造）
        auto_verdict = "webshell"
        confidence = 85
        reason = f"zero calls but {ws_score} webshell keywords detected"
    elif has_exec and inp and ws_score >= 2:
        auto_verdict = "webshell"
        confidence = 95
        reason = f"exec + input + {ws_score} WS keywords"
    elif has_exec and ws_score >= 3:
        auto_verdict = "webshell"
        confidence = 90
        reason = f"exec chain + {ws_score} WS keywords (input hidden)"
    elif calls >= 5 and ws_score >= 5:
        auto_verdict = "webshell"
        confidence = 90
        reason = f"high calls ({calls}) + {ws_score} WS keywords"
    elif calls == 0 and ws_score == 0:
        auto_verdict = "benign"
        confidence = 95
        reason = "zero calls, no WS keywords"
    elif has_exec and not inp and calls <= 2:
        # Code Exec 但无 input，少量调用 → 可能是片段或漏检
        if ws_score >= 2:
            auto_verdict = "webshell"
            confidence = 80
            reason = f"code exec, no input, but {ws_score} WS keywords"
        else:
            auto_verdict = None  # 边界
            reason = "code exec, no input, ambiguous"

    if auto_verdict:
        if truth == "webshell" and auto_verdict == "webshell":
            results["tp"] += 1
        elif truth == "benign" and auto_verdict == "benign":
            results["tn"] += 1
        elif truth == "benign" and auto_verdict == "webshell":
            results["fp"] += 1
            results["borderline"].append((md_file.name, truth, auto_verdict, confidence, reason, fp, calls, active, code[:300]))
        elif truth == "webshell" and auto_verdict == "benign":
            results["fn"] += 1
            results["borderline"].append((md_file.name, truth, auto_verdict, confidence, reason, fp, calls, active, code[:300]))
    else:
        results["borderline"].append((md_file.name, truth, "?", 0, reason, fp, calls, active, code[:300]))

# 输出
tp, tn, fp, fn = results["tp"], results["tn"], results["fp"], results["fn"]
total = tp + tn + fp + fn
print(f"=== Auto Classification Results ===")
print(f"Total: {total}")
print(f"TP={tp}, TN={tn}, FP={fp}, FN={fn}")
if total:
    acc = (tp+tn)/total*100
    prec = tp/(tp+fp)*100 if (tp+fp) else 0
    rec = tp/(tp+fn)*100 if (tp+fn) else 0
    f1 = 2*prec*rec/(prec+rec) if (prec+rec) else 0
    print(f"Accuracy: {acc:.1f}% | Precision: {prec:.1f}% | Recall: {rec:.1f}% | F1: {f1:.1f}%")

print(f"\n=== Borderline Cases ({len(results['borderline'])}) ===")
for b in results["borderline"]:
    name, truth, verdict, conf, reason, fp, calls, active, code = b
    print(f"\n[{truth}] → auto={verdict} ({conf}) [{fp}] calls={calls} input={'Y' if 'input' not in active else '?'}")
    print(f"  Reason: {reason}")
    print(f"  Active: {active}")
    print(f"  Code: {code[:250]}")

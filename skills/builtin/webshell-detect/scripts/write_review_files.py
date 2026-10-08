#!/usr/bin/env python3
"""
Generate compact review text files for LLM judgment.
One file per language, with all 200 cards (100 benign + 100 webshell).
"""

import json
import sys
import io
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

with open("F:/asainfo-sec/llm-based-webshell-detect/llm_review_cards.json", "r", encoding="utf-8") as f:
    all_cards = json.load(f)


def write_review_file(lang):
    out_path = Path(f"F:/asainfo-sec/llm-based-webshell-detect/review_{lang}.txt")
    lines = []
    lines.append(f"# LLM Review Cards — {lang.upper()}")
    lines.append(f"# Format: [IDX] HEURISTIC | TRUE_LABEL | fp=FINGERPRINT | calls=N | input=Y/N")
    lines.append(f"# Active categories and extracted code follow.")
    lines.append(f"# After review, add verdict line: VERDICT: <webshell/benign> CONF: <1-100> REASON: <brief>")
    lines.append("=" * 80)
    lines.append("")

    idx = 0
    for label in ["benign", "webshell"]:
        key = f"{lang}_{label}"
        cards = all_cards.get(key, [])
        lines.append(f"## {lang}/{label} — {len(cards)} cards")
        lines.append("")

        for card in cards:
            lines.append(f"[{idx}] HEUR={card['heuristic']} | TRUE={card['true_label']} | "
                         f"fp={card['fingerprint']} | calls={card['total_calls']} | "
                         f"input={'Y' if card['has_input'] else 'N'} | {card['file']}")
            if card['active_categories'] != "(clean)":
                lines.append(f"    Active: {card['active_categories']}")

            code = (card.get('extracted_code') or '').strip()
            if code:
                code_lines = code.split('\n')
                lines.append(f"    --- CODE ({len(code_lines)} lines) ---")
                for cl in code_lines[:20]:
                    # Strip/replace problematic chars
                    clean = cl.encode('utf-8', errors='replace').decode('utf-8', errors='replace')
                    lines.append(f"    | {clean[:130]}")
                if len(code_lines) > 20:
                    lines.append(f"    ... ({len(code_lines)} total lines)")

            top_ex = card.get('top_examples', [])[:3]
            if top_ex:
                for j, ex in enumerate(top_ex):
                    lines.append(f"    EX{j+1}: label={ex.get('label','')} sim={ex.get('similarity',0):.4f}")
                    ex_code = (ex.get('code_snippet', '') or '')[:300]
                    if ex_code:
                        for el in ex_code.split('\n')[:3]:
                            clean_el = el.encode('utf-8', errors='replace').decode('utf-8', errors='replace')
                            lines.append(f"    EX{j+1}> {clean_el[:120]}")

            lines.append(f"    VERDICT: ___ CONF: ___")
            lines.append("")
            idx += 1

    with open(out_path, "w", encoding="utf-8") as f:
        f.write('\n'.join(lines))
    print(f"Written {idx} cards to {out_path}")


if __name__ == "__main__":
    for lang in ["jsp", "php", "asp"]:
        write_review_file(lang)
    print("Done.")

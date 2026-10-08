#!/usr/bin/env python3
"""
Split review cards into "clear" (trivially benign, fp=0000000, calls=0, benign label)
and "needs_review" (everything else that requires LLM judgment).

For the "needs_review" set, write a compact file for LLM review.
"""

import json
from pathlib import Path

with open("F:/asainfo-sec/llm-based-webshell-detect/llm_review_cards.json", "r", encoding="utf-8") as f:
    all_cards = json.load(f)

clear_benign = []  # fingerprint 0000000 + 0 calls + benign true label
needs_review = []

for key, cards in all_cards.items():
    for card in cards:
        fp = card.get("fingerprint", "")
        calls = card.get("total_calls", 0)
        is_clean_fp = all(c == '0' for c in fp) if fp else True

        if is_clean_fp and calls == 0 and card["true_label"] == "benign":
            clear_benign.append(card)
        else:
            needs_review.append(card)

print(f"Clear benign (auto): {len(clear_benign)}")
print(f"Needs LLM review: {len(needs_review)}")

# Breakdown by language/label
from collections import Counter
review_by_key = Counter()
for card in needs_review:
    key = f"{card['language']}/{card['true_label']}"
    review_by_key[key] += 1

for k, v in review_by_key.most_common():
    print(f"  {k}: {v}")

# Write needs_review file
out_path = Path("F:/asainfo-sec/llm-based-webshell-detect/review_needed.txt")
lines = []
lines.append("# LLM Review — Cards Requiring Semantic Judgment")
lines.append(f"# Total: {len(needs_review)} cards")
lines.append(f"# Auto-classified as benign: {len(clear_benign)} cards (fp=0000000, calls=0, benign label)")
lines.append("=" * 80)
lines.append("")

for i, card in enumerate(needs_review):
    lines.append(f"[{i}] HEUR={card['heuristic']} | TRUE={card['true_label']} | "
                 f"LANG={card['language']} | fp={card['fingerprint']} | "
                 f"calls={card['total_calls']} | input={'Y' if card['has_input'] else 'N'} | "
                 f"{card['file']}")
    if card['active_categories'] != "(clean)":
        lines.append(f"    Active: {card['active_categories']}")

    code = (card.get('extracted_code') or '').strip()
    if code:
        code_lines = code.split('\n')
        lines.append(f"    --- CODE ({len(code_lines)} lines) ---")
        for cl in code_lines[:20]:
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

    lines.append(f"    VERDICT: ___ CONF: ___ REASON: ___")
    lines.append("")

with open(out_path, "w", encoding="utf-8") as f:
    f.write('\n'.join(lines))

print(f"\nReview file written to: {out_path}")

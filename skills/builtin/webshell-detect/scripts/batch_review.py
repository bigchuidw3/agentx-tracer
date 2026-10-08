#!/usr/bin/env python3
"""
Compact batch review: prints cards in compact format for LLM judgment.
Usage: python batch_review.py <lang> <label> [--start N] [--count M]
"""

import json
import sys
from pathlib import Path

with open("F:/asainfo-sec/llm-based-webshell-detect/llm_review_cards.json", "r", encoding="utf-8") as f:
    all_cards = json.load(f)

lang = sys.argv[1]
label = sys.argv[2]
start = int(sys.argv[3]) if len(sys.argv) > 3 else 0
count = int(sys.argv[4]) if len(sys.argv) > 4 else 100

key = f"{lang}_{label}"
cards = all_cards[key][start:start+count]

for i, card in enumerate(cards):
    idx = start + i
    print(f"--- [{idx}] {card['language']}/{card['true_label']} | fp={card['fingerprint']} | "
          f"heuristic={card['heuristic']} | calls={card['total_calls']} | "
          f"input={'Y' if card['has_input'] else 'N'} | {card['file']}")
    if card['active_categories'] != "(clean)":
        print(f"    Active: {card['active_categories']}")

    code = card['extracted_code'].strip()
    if code:
        # Show first 15 lines of code
        lines = code.split('\n')[:15]
        for line in lines:
            print(f"    | {line[:120]}")
        if len(code.split('\n')) > 15:
            print(f"    ... ({len(code.split('\n'))} total lines)")

    top_ex = card['top_examples'][:3] if card['top_examples'] else []
    if top_ex:
        for j, ex in enumerate(top_ex):
            ex_code = (ex.get('code_snippet', '') or '')[:200]
            print(f"    EX{j+1}: {ex['label']} sim={ex['similarity']:.4f}")
            if ex_code:
                for el in ex_code.split('\n')[:3]:
                    print(f"    EX{j+1}> {el[:120]}")
    print()

print(f"\n=== BATCH END: {key} [{start}-{start+len(cards)}] ===\n")
print("For each card, output: <idx> <VERDICT: webshell/benign> <confidence: 1-100> <brief reason>")

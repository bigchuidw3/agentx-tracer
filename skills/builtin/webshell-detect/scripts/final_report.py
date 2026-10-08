#!/usr/bin/env python3
"""
Generate final comparison report: Heuristic-only vs Heuristic+LLM.
"""

import json
import re
from pathlib import Path
from collections import defaultdict

BASE = Path("F:/asainfo-sec/llm-based-webshell-detect")

# Load review cards
with open(BASE / "llm_review_cards.json", "r", encoding="utf-8") as f:
    all_cards = json.load(f)

# LLM judgments extracted from agent outputs
# Each: (card_idx, verdict, confidence, reason)

llm_judgments = {}

# === JSP judgments (from agent output) ===
jsp_raw = """[0] benign 95 | [1] benign 98 | [2] benign 95 | [3] benign 95 | [4] benign 98 | [5] benign 90 | [6] benign 98 | [7] benign 95 | [8] benign 98 | [9] benign 90 | [10] webshell 85 | [11] benign 95 | [12] webshell 80 | [13] webshell 95 | [14] webshell 98 | [15] webshell 98 | [16] webshell 85 | [17] benign 98 | [18] webshell 100 | [19] webshell 98 | [20] benign 90 | [21] webshell 90 | [22] webshell 98 | [23] webshell 80 | [24] webshell 100 | [25] webshell 75 | [26] benign 99 | [27] benign 90 | [28] webshell 98 | [29] webshell 95 | [30] webshell 90 | [31] webshell 98 | [32] benign 98 | [33] webshell 98 | [34] webshell 95 | [35] webshell 98 | [36] webshell 100 | [37] webshell 98 | [38] webshell 98 | [39] webshell 95 | [40] webshell 98 | [41] webshell 95 | [42] webshell 85 | [43] webshell 85 | [44] webshell 98 | [45] webshell 85 | [46] benign 90 | [47] webshell 98 | [48] webshell 85 | [49] webshell 98 | [50] webshell 95 | [51] webshell 98 | [52] webshell 98 | [53] webshell 98 | [54] benign 80 | [55] webshell 80 | [56] benign 90 | [57] webshell 98 | [58] webshell 80 | [59] webshell 85 | [60] webshell 90 | [61] webshell 98 | [62] benign 99 | [63] webshell 95 | [64] webshell 85 | [65] benign 95 | [66] webshell 98 | [67] benign 90 | [68] webshell 98 | [69] webshell 90 | [70] webshell 90 | [71] webshell 90 | [72] webshell 98 | [73] webshell 95 | [74] benign 90 | [75] webshell 98 | [76] benign 98 | [77] webshell 80 | [78] benign 98 | [79] benign 90 | [80] webshell 98 | [81] webshell 95 | [82] webshell 85 | [83] webshell 98 | [84] webshell 95 | [85] webshell 90 | [86] webshell 90 | [87] webshell 98 | [88] webshell 98 | [89] webshell 85 | [90] webshell 90 | [91] benign 85 | [92] webshell 85 | [93] webshell 90 | [94] webshell 90 | [95] webshell 90 | [96] benign 90 | [97] webshell 95 | [98] webshell 90 | [99] webshell 90 | [100] benign 98 | [101] webshell 85 | [102] webshell 90 | [103] webshell 98 | [104] webshell 90 | [105] webshell 98 | [106] webshell 98 | [107] webshell 90 | [108] webshell 85 | [109] webshell 98"""

php_raw = """[110] benign 95 | [111] benign 85 | [112] benign 98 | [113] benign 95 | [114] benign 98 | [115] benign 90 | [116] benign 98 | [117] benign 92 | [118] benign 95 | [119] benign 90 | [120] benign 92 | [121] benign 90 | [122] benign 98 | [123] benign 95 | [124] benign 85 | [125] benign 95 | [126] benign 88 | [127] benign 98 | [128] benign 95 | [129] benign 92 | [130] benign 95 | [131] benign 98 | [132] benign 95 | [133] benign 98 | [134] benign 92 | [135] benign 95 | [136] benign 92 | [137] benign 98 | [138] benign 90 | [139] benign 95 | [140] benign 98 | [141] benign 95 | [142] benign 95 | [143] benign 98 | [144] benign 98 | [145] benign 90 | [146] benign 98 | [147] benign 90 | [148] benign 95 | [149] benign 92 | [150] benign 98 | [151] benign 95 | [152] benign 95 | [153] benign 98 | [154] benign 92 | [155] benign 95 | [156] benign 95 | [157] benign 98 | [158] benign 98 | [159] benign 95 | [160] benign 95 | [161] benign 98 | [162] benign 98 | [163] benign 90 | [164] benign 92 | [165] benign 95 | [166] benign 95 | [167] benign 92 | [168] benign 90 | [169] benign 92 | [170] benign 98 | [171] benign 95 | [172] benign 92 | [173] benign 95 | [174] benign 90 | [175] benign 90 | [176] benign 98 | [177] benign 95 | [178] benign 98 | [179] webshell 98 | [180] webshell 99 | [181] webshell 95 | [182] webshell 99 | [183] benign 85 | [184] webshell 98 | [185] webshell 85 | [186] webshell 90 | [187] webshell 92 | [188] webshell 98 | [189] webshell 99 | [190] webshell 98 | [191] webshell 90 | [192] benign 90 | [193] benign 92 | [194] benign 92 | [195] benign 92 | [196] webshell 85 | [197] webshell 99 | [198] webshell 98 | [199] webshell 99 | [200] webshell 85 | [201] webshell 99 | [202] webshell 88 | [203] webshell 99 | [204] webshell 95 | [205] webshell 99 | [206] webshell 98 | [207] webshell 98 | [208] benign 95 | [209] webshell 90 | [210] webshell 98 | [211] webshell 99 | [212] webshell 98 | [213] webshell 99 | [214] webshell 98 | [215] webshell 98 | [216] webshell 90 | [217] benign 90 | [218] benign 80 | [219] webshell 95 | [220] webshell 98 | [221] webshell 98 | [222] webshell 99 | [223] webshell 98 | [224] webshell 99 | [225] webshell 98 | [226] webshell 98 | [227] webshell 98 | [228] webshell 99 | [229] webshell 88 | [230] benign 90 | [231] webshell 90 | [232] webshell 85 | [233] webshell 99 | [234] webshell 98 | [235] benign 95 | [236] webshell 98 | [237] webshell 98 | [238] webshell 98 | [239] webshell 90 | [240] webshell 99 | [241] webshell 95 | [242] webshell 98 | [243] webshell 99 | [244] webshell 99 | [245] webshell 99 | [246] benign 95 | [247] webshell 90 | [248] benign 85 | [249] webshell 85 | [250] webshell 98 | [251] webshell 99 | [252] webshell 90 | [253] webshell 98 | [254] webshell 85 | [255] webshell 98 | [256] benign 90 | [257] webshell 90 | [258] webshell 99 | [259] webshell 98 | [260] webshell 90 | [261] webshell 90 | [262] webshell 85 | [263] webshell 98 | [264] webshell 92 | [265] webshell 98 | [266] webshell 98 | [267] webshell 98 | [268] webshell 99 | [269] webshell 99 | [270] webshell 95 | [271] benign 85 | [272] webshell 99 | [273] benign 88 | [274] webshell 90 | [275] webshell 99 | [276] webshell 98 | [277] webshell 99 | [278] webshell 99"""

asp_raw = """[279] benign 90 | [280] benign 95 | [281] benign 90 | [282] benign 85 | [283] benign 90 | [284] benign 85 | [285] benign 90 | [286] benign 95 | [287] benign 85 | [288] benign 85 | [289] benign 95 | [290] benign 90 | [291] benign 90 | [292] benign 95 | [293] benign 90 | [294] benign 95 | [295] benign 75 | [296] benign 90 | [297] benign 80 | [298] benign 95 | [299] benign 95 | [300] benign 95 | [301] benign 90 | [302] benign 85 | [303] benign 90 | [304] benign 90 | [305] benign 95 | [306] benign 90 | [307] benign 95 | [308] benign 90 | [309] benign 85 | [310] benign 95 | [311] benign 95 | [312] benign 95 | [313] benign 95 | [314] benign 95 | [315] benign 90 | [316] benign 95 | [317] benign 90 | [318] benign 95 | [319] benign 90 | [320] benign 90 | [321] benign 85 | [322] benign 90 | [323] benign 75 | [324] benign 95 | [325] benign 90 | [326] benign 95 | [327] benign 95 | [328] benign 70 | [329] benign 90 | [330] benign 95 | [331] benign 85 | [332] benign 90 | [333] benign 90 | [334] benign 85 | [335] benign 95 | [336] benign 95 | [337] benign 98 | [338] benign 90 | [339] benign 90 | [340] benign 95 | [341] benign 95 | [342] benign 90 | [343] benign 90 | [344] benign 95 | [345] benign 85 | [346] benign 98 | [347] benign 95 | [348] webshell 90 | [349] webshell 92 | [350] webshell 75 | [351] webshell 85 | [352] webshell 92 | [353] webshell 60 | [354] webshell 75 | [355] webshell 90 | [356] benign 70 | [357] benign 98 | [358] webshell 85 | [359] webshell 98 | [360] webshell 95 | [361] benign 80 | [362] webshell 92 | [363] webshell 98 | [364] webshell 100 | [365] webshell 75 | [366] webshell 90 | [367] benign 75 | [368] benign 70 | [369] benign 90 | [370] webshell 90 | [371] webshell 98 | [372] benign 90 | [373] webshell 95 | [374] benign 65 | [375] webshell 90 | [376] webshell 55 | [377] benign 90 | [378] benign 80 | [379] webshell 100 | [380] webshell 98 | [381] webshell 100 | [382] webshell 85 | [383] webshell 65 | [384] benign 85 | [385] benign 85 | [386] webshell 90 | [387] webshell 85 | [388] webshell 88 | [389] benign 95 | [390] benign 95 | [391] webshell 95 | [392] webshell 75 | [393] webshell 55 | [394] webshell 80 | [395] benign 70 | [396] webshell 95 | [397] webshell 100 | [398] webshell 80 | [399] webshell 80 | [400] benign 95 | [401] benign 75 | [402] webshell 98 | [403] webshell 95 | [404] webshell 95 | [405] webshell 85 | [406] benign 75 | [407] benign 98 | [408] webshell 90 | [409] webshell 70 | [410] webshell 80 | [411] benign 98 | [412] webshell 95 | [413] webshell 92 | [414] benign 85 | [415] webshell 100 | [416] webshell 90 | [417] webshell 95 | [418] webshell 85 | [419] benign 80 | [420] benign 90 | [421] webshell 75 | [422] webshell 70 | [423] benign 70 | [424] webshell 95 | [425] webshell 95 | [426] webshell 90 | [427] benign 90 | [428] webshell 65 | [429] webshell 95 | [430] benign 95 | [431] webshell 100 | [432] webshell 100 | [433] benign 95 | [434] webshell 95 | [435] webshell 90 | [436] webshell 100 | [437] webshell 100 | [438] webshell 95 | [439] webshell 100 | [440] webshell 55 | [441] benign 80 | [442] benign 90 | [443] webshell 95 | [444] benign 85 | [445] benign 70 | [446] benign 80 | [447] webshell 55"""

def parse_judgments(raw, llm_dict):
    pattern = r'\[(\d+)\]\s+(webshell|benign)\s+(\d+)'
    for m in re.finditer(pattern, raw):
        idx = int(m.group(1))
        verdict = m.group(2)
        conf = int(m.group(3))
        llm_dict[idx] = (verdict, conf)

parse_judgments(jsp_raw, llm_judgments)
parse_judgments(php_raw, llm_judgments)
parse_judgments(asp_raw, llm_judgments)

print(f"Parsed {len(llm_judgments)} LLM judgments")

# ── Merge with cards and compute metrics ──
# Build flat list of all cards in ORDER (same order as review_needed.txt generation)
all_flat = []
orig_idx = 0
review_idx_counter = 0
orig_to_review = {}  # orig_idx -> review_idx (only for non-clear cards)
review_to_orig = {}  # review_idx -> orig_idx

for key in ["jsp_benign", "jsp_webshell", "php_benign", "php_webshell", "asp_benign", "asp_webshell"]:
    for card in all_cards[key]:
        card["_orig_idx"] = orig_idx
        fp = card.get("fingerprint", "")
        calls = card.get("total_calls", 0)
        is_clear = all(c == '0' for c in fp) and calls == 0 and card["true_label"] == "benign"

        if not is_clear:
            card["_review_idx"] = review_idx_counter
            orig_to_review[orig_idx] = review_idx_counter
            review_to_orig[review_idx_counter] = orig_idx
            review_idx_counter += 1
        else:
            card["_review_idx"] = None

        all_flat.append(card)
        orig_idx += 1

print(f"Total cards: {orig_idx}, Needs review: {review_idx_counter}")
print(f"LLM judgments: {len(llm_judgments)}")

# Compute per-language metrics
def calc_metrics(cards):
    """Calculate metrics for a list of cards."""
    # Heuristic predictions
    h_TP = h_FN = h_FP = h_TN = 0
    l_TP = l_FN = l_FP = l_TN = 0

    for card in cards:
        orig_idx = card["_orig_idx"]
        review_idx = card.get("_review_idx")
        true_ws = card["true_label"] == "webshell"
        heur_sus = card["heuristic"] == "SUS"

        # Heuristic
        if true_ws and heur_sus:
            h_TP += 1
        elif true_ws and not heur_sus:
            h_FN += 1
        elif not true_ws and heur_sus:
            h_FP += 1
        else:
            h_TN += 1

        # LLM: look up by review_idx (since agents indexed by review file order)
        if review_idx is not None and review_idx in llm_judgments:
            llm_pred = llm_judgments[review_idx]
            llm_ws = llm_pred[0] == "webshell"
        elif review_idx is None:
            # Auto-classified as benign (clear benign)
            llm_ws = False
        else:
            # Fallback to heuristic (shouldn't happen normally)
            llm_ws = heur_sus

        if true_ws and llm_ws:
            l_TP += 1
        elif true_ws and not llm_ws:
            l_FN += 1
        elif not true_ws and llm_ws:
            l_FP += 1
        else:
            l_TN += 1

    def compute(h_TP, h_FN, h_FP, h_TN):
        acc = (h_TP + h_TN) / (h_TP + h_TN + h_FP + h_FN) if (h_TP + h_TN + h_FP + h_FN) > 0 else 0
        prec = h_TP / (h_TP + h_FP) if (h_TP + h_FP) > 0 else 0
        rec = h_TP / (h_TP + h_FN) if (h_TP + h_FN) > 0 else 0
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0
        return acc, prec, rec, f1

    h_metrics = compute(h_TP, h_FN, h_FP, h_TN)
    l_metrics = compute(l_TP, l_FN, l_FP, l_TN)

    return {
        "heur": {"TP": h_TP, "FN": h_FN, "FP": h_FP, "TN": h_TN, "metrics": h_metrics},
        "llm": {"TP": l_TP, "FN": l_FN, "FP": l_FP, "TN": l_TN, "metrics": l_metrics},
    }


# Group by language
print("=" * 80)
print("  BFAD Detection — Heuristic vs LLM Comparison Report")
print("=" * 80)
print()

results_by_lang = {}
for lang in ["jsp", "php", "asp"]:
    lang_cards = [c for c in all_flat if c["language"] == lang]
    m = calc_metrics(lang_cards)
    results_by_lang[lang] = m

    print(f"  [{lang.upper()}]  (reviewed: {len(lang_cards)} cards)")
    print(f"  {'':10s} {'TP':>6s} {'FN':>6s} {'FP':>6s} {'TN':>6s}  {'Acc':>8s} {'Prec':>8s} {'Recall':>8s} {'F1':>8s}")
    print(f"  {'─'*10} {'─'*6} {'─'*6} {'─'*6} {'─'*6}  {'─'*8} {'─'*8} {'─'*8} {'─'*8}")

    for method, d in [("Heuristic", m["heur"]), ("LLM", m["llm"])]:
        acc, prec, rec, f1 = d["metrics"]
        print(f"  {method:10s} {d['TP']:6d} {d['FN']:6d} {d['FP']:6d} {d['TN']:6d}  "
              f"{acc:8.4f} {prec:8.4f} {rec:8.4f} {f1:8.4f}")

    # Delta
    h_f1 = m["heur"]["metrics"][3]
    l_f1 = m["llm"]["metrics"][3]
    delta = l_f1 - h_f1
    print(f"  {'Δ (LLM-Heur)':10s} {'':>6s} {'':>6s} {'':>6s} {'':>6s}  {'':>8s} {'':>8s} {'':>8s} {delta:+8.4f}")
    print()

# Overall average
print("  [OVERALL AVERAGE]")
for metric_idx, metric_name in enumerate(["Accuracy", "Precision", "Recall", "F1"]):
    h_avg = sum(results_by_lang[l]["heur"]["metrics"][metric_idx] for l in ["jsp", "php", "asp"]) / 3
    l_avg = sum(results_by_lang[l]["llm"]["metrics"][metric_idx] for l in ["jsp", "php", "asp"]) / 3
    print(f"    {metric_name}: Heuristic={h_avg:.4f} → LLM={l_avg:.4f}  (Δ={l_avg-h_avg:+.4f})")

print()
print("=" * 80)
print("  Report Complete")
print("=" * 80)

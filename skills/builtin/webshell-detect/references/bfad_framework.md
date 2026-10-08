# BFAD Framework — Theoretical Background

Source: *"Can LLMs Handle WebShell Detection? Overcoming Detection Challenges with Behavioral Function-Aware Framework"* (Han et al., COLM 2025, arXiv:2504.13811)

## Core Insight

All PHP Webshells, regardless of obfuscation level or whether they are human-written or LLM-generated, share an **irreducible three-layer semantic skeleton**:

```
Input Channel → Decode/Transform → Execution Primitive
```

LLM paraphrase can change variable names, add comments, decompose into helper functions, and use modern PHP idioms. But it cannot eliminate these three layers without losing the ability to function as a Webshell.

## The Six Behavioral Categories

| Category | Weight | Examples | Role in Attack Chain |
|----------|--------|----------|---------------------|
| Code Execution | 0.2081 | eval, assert, preg_replace | Execution primitive (core) |
| Program Execution | 0.2068 | exec, system, passthru, shell_exec | Execution primitive (shell) |
| Information Gathering | 0.1861 | phpinfo, getenv | Reconnaissance |
| Obfuscation & Encryption | 0.1702 | base64_decode, gzinflate, strrev | Decode/transform layer |
| Network Communication | 0.1498 | fsockopen, curl_init | Data exfiltration / C2 |
| Callback Functions | 0.0790 | array_map, call_user_func | Indirect execution |

Weights come from WBFP scoring on the training set (Table 4, Appendix C).

## Statistical Foundation

Based on 26,594 PHP scripts (21,665 benign + 4,929 Webshells):

| Metric | Webshell | Benign | Ratio |
|--------|----------|--------|-------|
| Avg. critical function calls per file | 22.76 | 0.74 | 30:1 |
| Files with ≥1 critical function | 91.16% | 20.49% | 4.4:1 |
| Files with Code Execution functions | 85.03% | 14.79% | 5.7:1 |
| Files with Program Execution | 53.06% | 1.54% | 34:1 |

## Algorithm 1: Context-Aware Code Extraction

```
Input:  Source code C, critical function list F, context window τ
Output: Extracted critical code regions R

1:  R ← ∅
2:  for each f ∈ F do
3:      Find all positions p where f occurs in C
4:      for each p do
5:          Extract window [p−τ, p+τ]
6:          Add to R
7:  Merge overlapping regions in R
8:  if remaining context budget > 0 then
9:      Add non-overlapping global segments
10: return R
```

Two modes:
- **Critical Regions Only**: Just the merged critical windows (smaller models / longer files)
- **Hybrid**: Critical regions + truncated global segments (best overall)

## WBFP Scoring Formula

For each critical function type f:

```
Score_f = (r_c × α) + (r_f × β) + (r_u × γ)
w_f = Score_f / Σ(Score_f')
```

Where (α=β=γ=1):
- **r_c** (Coverage Difference): ratio of files containing f in Webshell vs benign
- **r_f** (Frequency Ratio): ratio of avg occurrences per file
- **r_u** (Usage Ratio): ratio of total occurrences

Similarity between files x and y:
```
e_f(x) = st-codesearch-distilroberta-base(concat_f(x))
s_f(x,y) = cos_sim(e_f(x), e_f(y))
Sim(x,y) = Σ(w_f × s_f(x,y))
```

## Prompt Design (Appendix A)

BFAD's prompt is intentionally minimal — gains come from preprocessing, not prompt engineering:

- **System**: Role the LLM as a cybersecurity analyst analyzing code
- **User**: Extracted critical regions + 1 behavior-matched ICL demonstration
- **Output**: Verdict as "WebShell" or "benign"

No Chain-of-Thought instructions. No checklists. No multi-step reasoning directives.

## Key Experimental Results

| Model | Baseline F1 | BFAD F1 | Improvement |
|-------|------------|---------|-------------|
| GPT-4 | 92.46% | **99.35%** | +6.89 |
| LLaMA-3.1-70B | 94.77% | 98.40% | +3.63 |
| Qwen-2.5-Coder-14B | 96.39% | 96.75% | +0.36 |
| Qwen-2.5-0.5B | 31.44% | 82.67% | +51.23 |
| **Average** | — | — | **+13.82%** |

## Critical ICL Finding

| Demonstration Selection Method | F1 (Qwen-2.5-3B) |
|-------------------------------|-------------------|
| No ICL (Zero-shot) | 84.37% |
| **Random selection** | **60.83%** (worse than none!) |
| Source-code semantic similarity | 84.36% |
| WBFP equal weights | 92.41% |
| **WBFP weighted (best)** | **93.69%** |

Randomly selected demonstrations can be worse than no demonstration at all.

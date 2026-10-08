---
name: webshell-detect
description: LLM-based Webshell detection using the BFAD (Behavioral Function-Aware Detection) framework. Analyzes PHP, JSP, ASP code files to determine whether they are Webshells. Trigger when user provides suspicious code files, asks to check for Webshell/backdoor, mentions webshell/WebShell/木马/一句话/后门 detection, or wants to analyze PHP/JSP/ASP files for malicious code. Also trigger for batch scanning directories.
---

# Webshell Detection — BFAD Framework

Detect Webshells in PHP, JSP, and ASP files using the BFAD three-step pipeline.

## When User Asks to Check Files

### Single file detection

When the user provides a file path and wants to know if it's a Webshell:

```
"Check if this is a webshell: /path/to/file.php"
"帮我看看这个文件是不是木马"
"Analyze this JSP file"
```

**What you do:**

1. Run the end-to-end detection script:
```bash
python {SKILL_DIR}/scripts/detect.py "<filepath>" --report
```

2. Read the output. It contains:
   - **Step 1**: Behavioral fingerprint (7-category scan result)
   - **Step 2**: Extracted critical code (context-aware code extraction)
   - **Step 3**: Top WBFP-matched ICL examples from the example library
   - **Step 4**: A ready-to-use LLM prompt

3. **Do the final judgment yourself** — you ARE the LLM. Look at the extracted code, compare it with the provided ICL examples, and make a verdict:
   - Is there a complete `input → (transform) → execute` chain?
   - Does the behavioral fingerprint match known Webshell patterns?
   - Are the critical function calls used in a malicious or legitimate way?

4. Present your verdict as:
   - **Verdict**: Webshell / Benign
   - **Confidence**: 1-100
   - **Attack Chain**: Describe the input→transform→execute path found
   - **Key Evidence**: Specific lines, functions, or patterns
   - **Why it's not benign** (if Webshell) or **Why it's not malicious** (if Benign)

### Batch directory scanning

When the user provides a directory:

```bash
python {SKILL_DIR}/scripts/detect.py "<directory>" --json
```

Present a summary table of results. Flag suspicious files for deeper investigation. For any file with `SUSPICIOUS` in the heuristic, offer to do a full --report analysis.

### When user asks for statistics or weights

```bash
# View weights
cat ./references/wbfp_weights.md

# Scan a directory and output CSV
python {SKILL_DIR}/scripts/batch_scan.py "<dir>" --out "<output>.csv"

# Rebuild example library
python {SKILL_DIR}/scripts/build_example_lib.py
```

## Key Reference Files

| File | When to Read |
|------|-------------|
| `{SKILL_DIR}/references/bfad_framework.md` | User asks about BFAD theory or methodology |
| `{SKILL_DIR}/references/wbfp_weights.md` | User asks about 7-category weights per language |
| `{SKILL_DIR}/references/php_functions.md` | Detailed PHP function lists per category |
| `{SKILL_DIR}/references/jsp_patterns.md` | Detailed JSP pattern lists per category |
| `{SKILL_DIR}/references/asp_patterns.md` | Detailed ASP pattern lists per category |

## Critical Detection Knowledge

### The Three-Layer Semantic Skeleton

All Webshells must have: **Input Channel → (Decode/Transform) → Execution Primitive**

### The 7 Behavioral Categories (with PHP example weights)

| Category | PHP Weight | What to Look For |
|----------|:---:|------|
| Code Execution | 0.1153 | `eval`, `assert`, `create_function` |
| Program Execution | 0.0466 | `system`, `exec`, `passthru`, `shell_exec` |
| Obfuscation & Encryption | 0.7184 | `base64_decode`, `gzinflate`, `strrev` |
| Information Gathering | 0.0630 | `phpinfo`, `getenv` |
| Network Communication | 0.0400 | `curl_init`, `fsockopen` |
| Callback / Reflection | 0.0167 | `array_map`, `call_user_func`, `register_shutdown_function` |
| File Operations | 0 | (ASP only: `FileSystemObject`, `ADODB.Stream`) |

### Heuristic (from BFAD statistical findings)

- Webshells average **22-48 critical function calls** vs **0.02-3** for benign files (24x-278x ratio by language)
- A file with 0 critical calls is almost certainly benign — skip LLM analysis
- Code Execution + Input Channel + Obfuscation = strongest Webshell signal
- Random Few-shot selection DESTROYS accuracy (F1 drops from 84% to 61%) — always use WBFP matching

### False Positive Patterns to Watch

- WordPress template engines legitimately use `eval()`
- Cryptography libraries legitimately use `base64_decode()` and `openssl_decrypt()`
- Legitimate admin tools may call `exec()` or `system()` with hardcoded commands
- ASP `Eval()`/`Execute()` are common in normal ASP code (22% of benign files)

### False Negative Patterns to Watch

- XOR-constructed function names (no plaintext keywords)
- Split-across-includes (attack chain spread over multiple files)
- Runtime variable function calls (`$f($a)` where `$f` comes from user input)
- Memory-only Webshells (not on filesystem)

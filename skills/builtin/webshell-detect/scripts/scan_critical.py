#!/usr/bin/env python3
"""
Step 1: Critical Function Scanner for BFAD Webshell Detection.

Scans PHP, JSP, or ASP files for security-sensitive function calls across
the 6 behavioral categories defined by the BFAD framework.
Outputs a behavioral fingerprint for each file.

Based on: Han et al., "Can LLMs Handle WebShell Detection?"
           COLM 2025, arXiv:2504.13811

Usage:
    python scan_critical.py <file_or_directory>
    python scan_critical.py --lang php <file>
    python scan_critical.py --json <file_or_directory>
"""

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

# ─── Critical Function Definitions ─────────────────────────────────────────

PHP_FUNCTIONS = {
    "Code Execution": {
        "patterns": [
            r"\beval\s*\(", r"\bassert\s*\(",
            r"\bpreg_replace\s*\(.+?\/e",  # deprecated /e modifier
            r"\bcreate_function\s*\(",
            r"\bcall_user_func\s*\(", r"\bcall_user_func_array\s*\(",
            r"\bforward_static_call\s*\(", r"\bforward_static_call_array\s*\(",
        ],
        "weight": 0.2081,
    },
    "Program Execution": {
        "patterns": [
            r"\bexec\s*\(", r"\bsystem\s*\(", r"\bpassthru\s*\(",
            r"\bshell_exec\s*\(", r"\bpopen\s*\(", r"\bproc_open\s*\(",
            r"\bpcntl_exec\s*\(", r"`[^`]+`",  # backtick operator
        ],
        "weight": 0.2068,
    },
    "Obfuscation & Encryption": {
        "patterns": [
            r"\bbase64_decode\s*\(", r"\bbase64_encode\s*\(",
            r"\bgzinflate\s*\(", r"\bgzuncompress\s*\(", r"\bgzdecode\s*\(",
            r"\bstr_rot13\s*\(", r"\bstrrev\s*\(",
            r"\bhex2bin\s*\(", r"\burldecode\s*\(",
            r"\bopenssl_decrypt\s*\(", r"\bopenssl_encrypt\s*\(",
        ],
        "weight": 0.1702,
    },
    "Information Gathering": {
        "patterns": [
            r"\bphpinfo\s*\(", r"\bphpversion\s*\(",
            r"\bgetenv\s*\(", r"\bget_current_user\s*\(",
            r"\bposix_getuid\s*\(", r"\bposix_getpwuid\s*\(",
            r"\bini_get\s*\(", r"\bini_get_all\s*\(",
            r"\bdisk_free_space\s*\(", r"\bdisk_total_space\s*\(",
        ],
        "weight": 0.1861,
    },
    "Network Communication": {
        "patterns": [
            r"\bfsockopen\s*\(", r"\bpfsockopen\s*\(",
            r"\bcurl_init\s*\(", r"\bcurl_exec\s*\(", r"\bcurl_setopt\s*\(",
            r"\bfile_get_contents\s*\(", r"\bfopen\s*\(",
            r"\bstream_socket_client\s*\(", r"\bstream_socket_server\s*\(",
        ],
        "weight": 0.1498,
    },
    "Callback Functions": {
        "patterns": [
            r"\barray_map\s*\(", r"\barray_filter\s*\(",
            r"\barray_walk\s*\(", r"\barray_walk_recursive\s*\(",
            r"\barray_reduce\s*\(",
            r"\bregister_shutdown_function\s*\(",
            r"\bregister_tick_function\s*\(",
            r"\bset_error_handler\s*\(", r"\bset_exception_handler\s*\(",
            r"\bob_start\s*\(", r"\bpreg_replace_callback\s*\(",
        ],
        "weight": 0.0790,
    },
}

# Additional: PHP input channels (not in BFAD's 6 categories, but crucial for attack chain)
PHP_INPUTS = [
    r"\$_GET\b", r"\$_POST\b", r"\$_REQUEST\b",
    r"\$_COOKIE\b", r"\$_FILES\b", r"\$_SERVER\b",
    r"\bphp://input\b", r"\bfile_get_contents\s*\(.*php://",
]

JSP_PATTERNS = {
    "Code Execution": {
        "patterns": [
            r"\bClass\.forName\s*\(",
            r"\bClassLoader.*\.defineClass\s*\(",
            r"\bClassLoader.*\.loadClass\s*\(",
            r"\bbcel\b",  # BCEL encoded class bytes
            r'\bBCEL\b',
            r"\bMethod.*\.invoke\s*\(",
            r"\bConstructor.*\.newInstance\s*\(",
            r"\.newInstance\s*\(",
            r"\bScriptEngine.*\.eval\s*\(",
            r"\.getDeclaredMethod\s*\(",
            r"\.getConstructor\s*\(",
        ],
        "weight": 0.2081,
    },
    "Program Execution": {
        "patterns": [
            r"\bRuntime\.getRuntime\(\)\.exec\s*\(",
            r"\bnew\s+ProcessBuilder\s*\(",
            r"\.command\s*\(",
        ],
        "weight": 0.2068,
    },
    "Obfuscation & Encryption": {
        "patterns": [
            r"\bBase64\.getDecoder\(\)\.decode\s*\(",
            r"\bBase64\.getMimeDecoder\(\)\.decode\s*\(",
            r"\bDatatypeConverter\.parseBase64Binary\s*\(",
            r"\bCipher\.getInstance\s*\(",
            r"\bnew\s+String\s*\(.*byte",
        ],
        "weight": 0.1702,
    },
    "Information Gathering": {
        "patterns": [
            r"\bSystem\.getProperty\s*\(",
            r"\bSystem\.getenv\s*\(",
            r"\bInetAddress\.getLocalHost\s*\(",
        ],
        "weight": 0.1861,
    },
    "Network Communication": {
        "patterns": [
            r"\bnew\s+Socket\s*\(", r"\bnew\s+ServerSocket\s*\(",
            r"\bURL\(.*\.openConnection\s*\(",
            r"\bHttpURLConnection\b",
        ],
        "weight": 0.1498,
    },
    "Callback / Reflection": {
        "patterns": [
            r"\.getDeclaredMethod\s*\(",
            r"\.setAccessible\s*\(true\)",
            r"\bInvocationHandler\b",
            r"\bProxy\.newProxyInstance\s*\(",
        ],
        "weight": 0.0790,
    },
}

JSP_INPUTS = [
    r"\brequest\.getParameter\s*\(",
    r"\brequest\.getParameterValues\s*\(",
    r"\brequest\.getParameterMap\s*\(",
    r"\brequest\.getInputStream\s*\(",
    r"\brequest\.getReader\s*\(",
    r"\brequest\.getHeader\s*\(",
    r"\brequest\.getQueryString\s*\(",
    r"\brequest\.getCookies\s*\(",
    r"\bsession\.getAttribute\s*\(",
]

ASP_PATTERNS = {
    "Code Execution": {
        "patterns": [
            r"\bEval\s*\(", r"\bExecute\s*\(.*\)", r"\bExecuteGlobal\s*\(",
            r"\bServer\.Execute\s*\(", r"\bServer\.Transfer\s*\(",
        ],
        "weight": 0.2081,
    },
    "Program Execution": {
        "patterns": [
            r'CreateObject\s*\(\s*"WScript\.Shell"',
            r'CreateObject\s*\(\s*"Shell\.Application"',
            r"\.Run\s*\(", r"\.Exec\s*\(", r"\.ShellExecute\s*\(",
        ],
        "weight": 0.2068,
    },
    "Obfuscation & Encryption": {
        "patterns": [
            r"\bChr\s*\(.*\).*&.*Chr",  # Chr concatenation
            r"\bStrReverse\s*\(", r"\bMid\s*\(",
            r"\bReplace\s*\(.*\).*Replace",  # Nested Replace for decode
        ],
        "weight": 0.1702,
    },
    "Information Gathering": {
        "patterns": [
            r"\bServer\.MapPath\s*\(",
            r"\bRequest\.ServerVariables\s*\(",
            r'CreateObject\s*\(\s*"WScript\.Network"',
        ],
        "weight": 0.1861,
    },
    "Network Communication": {
        "patterns": [
            r'CreateObject\s*\(\s*"MSXML2\.ServerXMLHTTP"',
            r'CreateObject\s*\(\s*"Microsoft\.XMLHTTP"',
            r'CreateObject\s*\(\s*"WinHttp\.WinHttpRequest"',
        ],
        "weight": 0.1498,
    },
    "File Operations": {
        "patterns": [
            r'CreateObject\s*\(\s*"Scripting\.FileSystemObject"',
            r'CreateObject\s*\(\s*"ADODB\.Stream"',
            r"\.CreateTextFile\s*\(", r"\.SaveToFile\s*\(",
            r"\.CopyFile\s*\(", r"\.DeleteFile\s*\(",
        ],
        "weight": 0.1702,
    },
}

ASP_INPUTS = [
    r"\bRequest\.Form\s*\(", r"\bRequest\.QueryString\s*\(",
    r"\bRequest\s*\(.*\)", r"\bRequest\.BinaryRead\s*\(",
    r"\bRequest\.Cookies\s*\(",
    r"\bRequest\.ServerVariables\s*\(.*HTTP_",
]

# ── JS (Node.js) ─────────────────────────────────────────────────────────

JS_PATTERNS = {
    "Code Execution": {
        "patterns": [
            r"\beval\s*\(", r"\bnew\s+Function\s*\(",
            r"\bvm\.runInThisContext\s*\(", r"\bvm\.runInNewContext\s*\(",
            r"\bvm\.Script\s*\(", r"\bvm\.compileFunction\s*\(",
        ],
        "weight": 0.2081,
    },
    "Program Execution": {
        "patterns": [
            r"\bexec\s*\(", r"\bexecSync\s*\(",
            r"\bspawn\s*\(", r"\bspawnSync\s*\(",
            r"\bfork\s*\(",
            r"\bchild_process\.exec\s*\(", r"\bchild_process\.spawn\s*\(",
        ],
        "weight": 0.2068,
    },
    "Obfuscation & Encryption": {
        "patterns": [
            r"\batob\s*\(", r"\bbtoa\s*\(",
            r"\bBuffer\.from\(.*['\"]base64['\"]",
            r"\bBuffer\.from\(.*\)\.toString\(['\"]base64",
            r"\bString\.fromCharCode\s*\(",
            r"\bString\.fromCodePoint\s*\(",
            r"\bunescape\s*\(", r"\bdecodeURIComponent\s*\(",
            r"\bcrypto\.createDecipher",
            r"\bcrypto\.createDecipheriv",
        ],
        "weight": 0.1702,
    },
    "Information Gathering": {
        "patterns": [
            r"\bprocess\.env\b", r"\bprocess\.cwd\s*\(",
            r"\bprocess\.platform\b", r"\bprocess\.arch\b",
            r"\bos\.hostname\s*\(", r"\bos\.userInfo\s*\(",
            r"\bos\.networkInterfaces\s*\(", r"\bos\.cpus\s*\(",
            r"\b__dirname\b", r"\b__filename\b",
        ],
        "weight": 0.1861,
    },
    "Network Communication": {
        "patterns": [
            r"\brequire\(['\"]http['\"]", r"\brequire\(['\"]https['\"]",
            r"\brequire\(['\"]net['\"]", r"\brequire\(['\"]dgram['\"]",
            r"\brequire\(['\"]request['\"]", r"\brequire\(['\"]axios['\"]",
            r"\bfetch\s*\(", r"\bnet\.createConnection\s*\(",
            r"\bnet\.createServer\s*\(",
        ],
        "weight": 0.1498,
    },
    "Callback / Reflection": {
        "patterns": [
            r"\bsetTimeout\s*\(", r"\bsetInterval\s*\(",
            r"\bnew\s+Proxy\s*\(",
            r"\bReflect\.construct\s*\(", r"\bReflect\.apply\s*\(",
            r"\.bind\s*\(.*\)\s*\)", r"\.call\s*\(.*\)\s*\)",
            r"\.then\s*\(",
        ],
        "weight": 0.0790,
    },
    "File Operations": {
        "patterns": [
            r"\brequire\(['\"]fs['\"]", r"\brequire\(['\"]fs-extra['\"]",
            r"\bfs\.writeFileSync\s*\(", r"\bfs\.writeFile\s*\(",
            r"\bfs\.readFileSync\s*\(", r"\bfs\.readFile\s*\(",
            r"\bfs\.appendFileSync\s*\(", r"\bfs\.unlinkSync\s*\(",
            r"\bfs\.mkdirSync\s*\(",
        ],
        "weight": 0.1702,
    },
}

JS_INPUTS = [
    r"\bprocess\.argv\b",
    r"\breq\.query\b", r"\breq\.body\b", r"\breq\.params\b",
    r"\breq\.param\s*\(", r"\breq\.get\s*\(",
    r"\brequest\.body\b", r"\brequest\.query\b", r"\brequest\.params\b",
    r"\brequest\.param\s*\(",
]

# ── ASPX (ASP.NET) ──────────────────────────────────────────────────────

ASPX_PATTERNS = {
    "Code Execution": {
        "patterns": [
            r"\bSystem\.Reflection\.Assembly\.Load\s*\(",
            r"\bAssembly\.Load\s*\(",
            r"\bAssembly\.LoadFile\s*\(",
            r"\bAssembly\.LoadFrom\s*\(",
            r"\.GetMethod\s*\(.*\)\.Invoke\s*\(",
            r"\bSystem\.CodeDom\.Compiler\b",
            r"\bCSharpCodeProvider\b",
            r"\bCodeDomProvider\.CreateCompiler\s*\(",
            r"\bCompileAssemblyFromSource\s*\(",
        ],
        "weight": 0.2081,
    },
    "Program Execution": {
        "patterns": [
            r"\bSystem\.Diagnostics\.Process\.Start\s*\(",
            r"\bProcess\.Start\s*\(",
            r"\bnew\s+ProcessStartInfo\s*\(",
            r"\.FileName\s*=\s*['\"]cmd\.exe",
            r"\.Arguments\s*=\s*['\"]/c",
            r"\.RedirectStandardOutput\s*=\s*true",
            r"\.UseShellExecute\s*=\s*false",
        ],
        "weight": 0.2068,
    },
    "Obfuscation & Encryption": {
        "patterns": [
            r"\bConvert\.FromBase64String\s*\(",
            r"\bConvert\.ToBase64String\s*\(",
            r"\bEncoding\.UTF8\.GetString\s*\(",
            r"\bEncoding\.ASCII\.GetBytes\s*\(",
            r"\bEncoding\.Unicode\.GetString\s*\(",
            r"\bAesManaged\b", r"\bRijndaelManaged\b",
            r"\bDESCryptoServiceProvider\b",
            r"\bTripleDESCryptoServiceProvider\b",
        ],
        "weight": 0.1702,
    },
    "Information Gathering": {
        "patterns": [
            r"\bServer\.MapPath\s*\(",
            r"\bRequest\.ServerVariables\s*\(",
            r"\bRequest\.PhysicalApplicationPath\b",
            r"\bEnvironment\.CurrentDirectory\b",
            r"\bEnvironment\.GetEnvironmentVariable\s*\(",
            r"\bEnvironment\.OSVersion\b",
            r"\bEnvironment\.MachineName\b",
            r"\bEnvironment\.UserName\b",
            r"\bEnvironment\.UserDomainName\b",
            r"\bSystem\.Net\.Dns\.GetHostName\s*\(",
        ],
        "weight": 0.1861,
    },
    "Network Communication": {
        "patterns": [
            r"\bnew\s+WebClient\s*\(",
            r"\.DownloadFile\s*\(", r"\.DownloadString\s*\(",
            r"\.DownloadData\s*\(", r"\.UploadFile\s*\(",
            r"\.UploadString\s*\(",
            r"\bHttpWebRequest\.Create\s*\(",
            r"\bWebRequest\.Create\s*\(",
            r"\bnew\s+TcpClient\s*\(",
            r"\bnew\s+Socket\s*\(",
            r"\bnew\s+HttpClient\s*\(",
        ],
        "weight": 0.1498,
    },
    "Callback / Reflection": {
        "patterns": [
            r"\.GetType\s*\(", r"\.GetMethod\s*\(",
            r"\.GetMethods\s*\(", r"\.GetProperty\s*\(",
            r"\.GetProperties\s*\(", r"\.GetField\s*\(",
            r"\bBindingFlags\.NonPublic\b", r"\bBindingFlags\.Static\b",
            r"\bBindingFlags\.Instance\b",
            r"\bActivator\.CreateInstance\s*\(",
            r"\bDelegate\.CreateDelegate\s*\(",
        ],
        "weight": 0.0790,
    },
    "File Operations": {
        "patterns": [
            r"\bSystem\.IO\.File\.WriteAllText\s*\(",
            r"\bFile\.WriteAllText\s*\(",
            r"\bSystem\.IO\.File\.WriteAllBytes\s*\(",
            r"\bFile\.WriteAllBytes\s*\(",
            r"\bSystem\.IO\.File\.ReadAllText\s*\(",
            r"\bFile\.ReadAllText\s*\(",
            r"\bSystem\.IO\.File\.Delete\s*\(",
            r"\bFile\.Delete\s*\(",
            r"\bSystem\.IO\.File\.Copy\s*\(",
            r"\bSystem\.IO\.File\.Move\s*\(",
            r"\bnew\s+FileStream\s*\(",
            r"\bnew\s+StreamWriter\s*\(",
            r"\bnew\s+StreamReader\s*\(",
            r"\bnew\s+FileInfo\s*\(",
            r"\bDirectory\.GetFiles\s*\(",
            r"\bDirectory\.CreateDirectory\s*\(",
            r"\bDirectory\.Delete\s*\(",
        ],
        "weight": 0.1702,
    },
}

ASPX_INPUTS = [
    r"\bRequest\.Form\[", r"\bRequest\.Form\s*\(",
    r"\bRequest\.QueryString\[", r"\bRequest\.QueryString\s*\(",
    r"\bRequest\[", r"\bRequest\.Params\[",
    r"\bRequest\.BinaryRead\s*\(",
    r"\bRequest\.InputStream\b",
    r"\bRequest\.Headers\[",
    r"\bRequest\.Cookies\[",
]


def scan_file(filepath: str, patterns: dict, inputs: list) -> dict:
    """Scan a single file for critical functions across categories."""
    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    results = {
        "file": filepath,
        "total_critical_calls": 0,
        "categories": {},
        "has_input_channel": False,
        "input_matches": [],
    }

    # Scan input channels
    for pattern in inputs:
        matches = re.findall(pattern, content, re.IGNORECASE)
        if matches:
            results["has_input_channel"] = True
            results["input_matches"].extend(matches)

    # Scan critical functions by category
    for category, info in patterns.items():
        cat_matches = []
        for pattern in info["patterns"]:
            found = re.findall(pattern, content, re.IGNORECASE)
            cat_matches.extend(found)
        results["categories"][category] = {
            "count": len(cat_matches),
            "weight": info["weight"],
            "matches": cat_matches[:10],  # Truncate for readability
        }
        results["total_critical_calls"] += len(cat_matches)

    return results


def fingerprint_summary(results: dict) -> str:
    """Generate a human-readable behavioral fingerprint."""
    lines = []
    lines.append(f"File: {results['file']}")
    lines.append(f"Total critical calls: {results['total_critical_calls']}")
    lines.append(f"Input channel: {'YES' if results['has_input_channel'] else 'NO'}")

    active_categories = []
    for cat, info in results["categories"].items():
        if info["count"] > 0:
            active_categories.append(cat)
            lines.append(f"  [{cat}]: {info['count']} matches (weight: {info['weight']})")

    lines.append(f"\nActive categories: {len(active_categories)}/6")
    if results["has_input_channel"]:
        lines.append(f"Input methods: {', '.join(set(results['input_matches'][:5]))}")

    # Heuristic: complete chain = input + (code_exec OR program_exec) + (obfuscation optional)
    has_exec = any(c in active_categories for c in ["Code Execution", "Program Execution"])
    has_transform = "Obfuscation & Encryption" in active_categories

    if results["has_input_channel"] and has_exec:
        if has_transform:
            lines.append("CHAIN: Input → Transform → Execute (FULL 3-LAYER)")
        else:
            lines.append("CHAIN: Input → Execute (2-LAYER)")
    elif results["has_input_channel"] and not has_exec:
        lines.append("CHAIN: Input only (NO EXECUTION LAYER) — likely benign")
    elif has_exec and not results["has_input_channel"]:
        lines.append("CHAIN: Execute only (NO INPUT CHANNEL) — likely benign")
    else:
        lines.append("CHAIN: No suspicious chain detected")

    lines.append(f"\nBFAD heuristic: {'SUSPICIOUS' if results['total_critical_calls'] > 0 and results['has_input_channel'] and has_exec else 'LIKELY BENIGN'}")

    return "\n".join(lines)


def detect_language(filepath: str) -> str:
    """Detect language from file extension."""
    ext = Path(filepath).suffix.lower()
    if ext in (".php", ".php3", ".php4", ".php5", ".phtml", ".inc"):
        return "php"
    elif ext in (".jsp", ".jspx"):
        return "jsp"
    elif ext in (".asp", ".asa"):
        return "asp"
    return "unknown"


def main():
    parser = argparse.ArgumentParser(
        description="BFAD Critical Function Scanner (Step 1)"
    )
    parser.add_argument("target", help="File or directory to scan")
    parser.add_argument("--lang", choices=["php", "jsp", "asp"],
                        help="Force language (auto-detect by extension if omitted)")
    parser.add_argument("--json", action="store_true",
                        help="Output JSON instead of human-readable text")
    args = parser.parse_args()

    target = Path(args.target)
    if not target.exists():
        print(f"Error: {args.target} not found", file=sys.stderr)
        sys.exit(1)

    # Collect files
    files = []
    if target.is_file():
        files = [str(target)]
    else:
        extensions = {".php", ".php3", ".php4", ".php5", ".phtml", ".inc",
                      ".jsp", ".jspx", ".asp", ".asa"}
        for root, _, filenames in os.walk(target):
            for fn in filenames:
                if Path(fn).suffix.lower() in extensions:
                    files.append(str(Path(root) / fn))

    if not files:
        print("No PHP/JSP/ASP files found.", file=sys.stderr)
        sys.exit(1)

    results = []
    for filepath in files:
        lang = args.lang or detect_language(filepath)
        if lang == "php":
            r = scan_file(filepath, PHP_FUNCTIONS, PHP_INPUTS)
        elif lang == "jsp":
            r = scan_file(filepath, JSP_PATTERNS, JSP_INPUTS)
        elif lang == "asp":
            r = scan_file(filepath, ASP_PATTERNS, ASP_INPUTS)
        else:
            continue
        results.append(r)

    if args.json:
        print(json.dumps(results, indent=2, ensure_ascii=False))
    else:
        for r in results:
            print(fingerprint_summary(r))
            print("-" * 60)

    # Summary statistics
    susp_count = sum(1 for r in results
                     if r["total_critical_calls"] > 0
                     and r["has_input_channel"]
                     and any(c in [cat for cat, info in r["categories"].items() if info["count"] > 0]
                            for c in ["Code Execution", "Program Execution"]))
    print(f"\nTotal files: {len(results)}")
    print(f"Suspicious (input + execution): {susp_count}")
    print(f"Clean (no critical calls): {sum(1 for r in results if r['total_critical_calls'] == 0)}")


if __name__ == "__main__":
    main()

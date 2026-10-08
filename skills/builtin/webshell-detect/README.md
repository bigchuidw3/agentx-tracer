# LLM-Based Webshell Detection (BFAD Framework)

基于 BFAD（Behavioral Function-Aware Detection）框架的 LLM Webshell 检测系统。

**论文**: *"Can LLMs Handle WebShell Detection? Overcoming Detection Challenges with Behavioral Function-Aware Framework"* (Han et al., COLM 2025, arXiv:2504.13811)

## 检测原理

所有 Webshell 无论怎么混淆，都遵循**三层不可约简语义骨架**：

```
输入通道 → 解码/变换 → 执行入口
```

BFAD 框架通过三个步骤利用 LLM 进行检测：
1. **Critical Function Filter**：扫描关键函数，生成行为指纹
2. **Context-Aware Code Extraction**：提取关键代码片段，减少 token 消耗
3. **Weighted Behavioral Function Profiling (WBFP)**：按行为相似度选择最佳 Few-shot 示例

**核心数据**：Webshell 平均 22.76 次关键函数调用，正常文件仅 0.74 次（30 倍差距）。

## 项目结构

```
llm-based-webshell-detect/
├── SKILL.md                         # Claude Code Skill 主文件
├── README.md                        # 项目说明（本文件）
├── references/
│   ├── bfad_framework.md            # BFAD 框架原理速查
│   ├── php_functions.md             # PHP 6类关键函数库
│   ├── jsp_patterns.md              # JSP 危险模式库
│   └── asp_patterns.md              # ASP 危险模式库
├── examples/
│   ├── php_webshell.md              # PHP Few-shot 示例
│   ├── jsp_webshell.md              # JSP Few-shot 示例
│   └── asp_webshell.md              # ASP Few-shot 示例
└── scripts/
    └── scan_critical.py             # Step 1: 关键函数扫描脚本
```

## 快速开始

### 1. 安装 Skill

将 `SKILL.md` 所在目录复制到 Claude Code 的 skills 目录即可使用。

### 2. 使用扫描脚本（Step 1）

```bash
# 扫描单个文件
python scripts/scan_critical.py /path/to/suspicious.php

# 扫描整个目录
python scripts/scan_critical.py /path/to/webroot/

# JSON 输出
python scripts/scan_critical.py --json /path/to/webroot/ > report.json
```

### 3. 使用 Claude Code Skill

在 Claude Code 中直接提供代码文件或片段即可触发检测：

```
帮我检测这个文件是不是 Webshell: /path/to/file.php
```

Skill 会自动执行三个步骤并输出结构化判定结果。

## 支持的语言

| 语言 | 覆盖范围 |
|------|---------|
| PHP | 6 类 50+ 关键函数，含 `eval`/`system`/`base64_decode`/`array_map` 等 |
| JSP | 6 类 30+ 危险模式，含 `Runtime.exec`/`Class.forName`/反射等 |
| ASP | 6 类 30+ 危险模式，含 `WScript.Shell`/`Eval`/`FSO`/`Chr()` 混淆等 |

## 输出格式

```json
{
  "verdict": "WebShell | Benign",
  "confidence": 1-100,
  "attack_chain": "$_POST['cmd'] → base64_decode() → eval()",
  "key_evidence": ["Line 5: eval() call", "Line 3: direct user input"],
  "behavioral_fingerprint": {
    "total_critical_calls": 3,
    "active_categories": ["Input Channel", "Code Execution", "Obfuscation"],
    "chain_type": "Input → Transform → Execute (FULL 3-LAYER)"
  }
}
```

## 与 BFAD 论文的对应关系

| 论文组件 | 本项目实现 |
|---------|-----------|
| Critical Function Filter | `scripts/scan_critical.py` + `references/*_functions.md` |
| Context-Aware Code Extraction | Skill 指令中的 Algorithm 1 手动流程 |
| WBFP 示例选择 | Skill 指令中的行为指纹匹配选择 |
| LLM Prompt (Appendix A) | Skill 指令中的 System/User Prompt 模板 |
| 6 类关键函数定义 | `references/php_functions.md`（PHP）、`jsp_patterns.md`（JSP）、`asp_patterns.md`（ASP） |
| Few-shot 示例库 | `examples/` 目录 |

## 论文关键结论

| 模型 | Baseline F1 | BFAD F1 | 提升 |
|------|------------|---------|------|
| GPT-4 | 92.46% | **99.35%** | +6.89 |
| 全模型平均 | — | — | **+13.82%** |

- 随机选 Few-shot 示例反而比不用差（60.83% vs 84.37%）
- BFAD 的 Prompt 极简——提升主要来自预处理和示例选择，而非 Prompt 技巧
- 不需要 Fine-Tuning，纯 Prompt Engineering 即可超越 SOTA

## 依赖

- Python 3.8+（仅扫描脚本需要）
- Claude Code（Skill 运行环境）

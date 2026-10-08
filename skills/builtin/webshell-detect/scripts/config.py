#!/usr/bin/env python3
"""
全局配置 — 所有路径从 Skill 根目录推导。

Skill 目录结构:
    webshell-detect/
    ├── scripts/     ← 本文件所在
    ├── references/
    ├── examples/
    └── data_storage/  ← 可选，数据集目录

设置环境变量 WEBSHELL_DATA_DIR 可覆盖 data_storage 路径。
"""

import os
from pathlib import Path

# Skill 根目录 = scripts/ 的父目录
SKILL_ROOT = Path(__file__).resolve().parent.parent

# 数据目录（优先级：环境变量 > 实际数据路径 > Skill 默认路径）
_DEFAULT_PATHS = [
    Path("F:/asainfo-sec/llm-based-webshell-detect/data_storage"),
    SKILL_ROOT / "data_storage",
]

_data_dir = os.environ.get("WEBSHELL_DATA_DIR", "")
if _data_dir:
    DATA_DIR = Path(_data_dir)
else:
    DATA_DIR = next((p for p in _DEFAULT_PATHS if p.exists()), _DEFAULT_PATHS[-1])

# 子目录
SCRIPTS_DIR = SKILL_ROOT / "scripts"
REFERENCES_DIR = SKILL_ROOT / "references"
EXAMPLES_DIR = SKILL_ROOT / "examples"

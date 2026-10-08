#!/usr/bin/env python3
"""
按语言和标签整理 Webshell 数据集。

将 data/ 下的原始文件按扩展名分类，复制/移动到统一的 data_storage/ 目录结构下。

输出结构:
    data_storage/
    ├── php/webshell/     # .php 文件
    ├── jsp/webshell/     # .jsp 文件
    └── asp/webshell/     # .asp 文件

Usage:
    python organize_dataset.py                 # 复制模式（默认）
    python organize_dataset.py --move          # 移动模式
    python organize_dataset.py --dry-run       # 预览模式（不实际操作）
"""

import argparse
import os
import shutil
import sys
from pathlib import Path

# ─── 配置 ─────────────────────────────────────────────────────────────────

# (源目录, 标签)
# 标签: "webshell" 或 "benign"
# 语言根据文件扩展名自动检测 (.php, .jsp, .asp)
SOURCE_DIRS = [
    (r"F:\asainfo-sec\llm-based-webshell-detect\data\php\black\black", "webshell"),
    (r"F:\asainfo-sec\llm-based-webshell-detect\data\php\white\white", "benign"),
    # (r"F:\asainfo-sec\llm-based-webshell-detect\data\jsp\black\...", "webshell"),
    # (r"F:\asainfo-sec\llm-based-webshell-detect\data\jsp\white\...", "benign"),
    # (r"F:\asainfo-sec\llm-based-webshell-detect\data\asp\black\...", "webshell"),
    # (r"F:\asainfo-sec\llm-based-webshell-detect\data\asp\white\...", "benign"),
]

from config import SKILL_ROOT as BASE, DATA_DIR as DST_ROOT

EXTENSION_MAP = {
    ".php": "php",
    ".jsp": "jsp",
    ".jspx": "jsp",
    ".asp": "asp",
    ".asa": "asp",
}


def iter_source_files(source_dir: Path):
    """递归遍历源目录下所有相关文件，过滤非代码文件。"""
    if not source_dir.exists():
        print(f"[WARN] 源目录不存在: {source_dir}")
        return

    for root, _, filenames in os.walk(source_dir):
        for fn in filenames:
            ext = Path(fn).suffix.lower()
            if ext in EXTENSION_MAP:
                yield Path(root) / fn


def organize(source_dirs: list, dry_run: bool = False, move: bool = False):
    stats = {"copied": 0, "skipped": 0, "errors": 0}

    for src_str, label in source_dirs:
        src = Path(src_str)

        for filepath in iter_source_files(src):
            # 按文件实际扩展名路由到对应语言目录
            ext = filepath.suffix.lower()
            lang = EXTENSION_MAP.get(ext, "unknown")
            dst = DST_ROOT / lang / label

            if not dry_run:
                dst.mkdir(parents=True, exist_ok=True)

            if not hasattr(organize, "_logged"):
                organize._logged = set()
            src_key = str(src)
            if src_key not in organize._logged:
                organize._logged.add(src_key)
                if dry_run:
                    print(f"\n[DRY-RUN] 源: {src} → {DST_ROOT}/<lang>/{label}")

            target = dst / filepath.name

            if target.exists():
                src_size = filepath.stat().st_size
                dst_size = target.stat().st_size
                if src_size == dst_size:
                    print(f"[SKIP] 已存在(相同): {filepath.name}")
                    stats["skipped"] += 1
                    continue
                else:
                    # 大小不同，加后缀防止覆盖
                    stem = target.stem
                    counter = 1
                    while target.exists():
                        target = dst / f"{stem}_{counter}{filepath.suffix}"
                        counter += 1

            try:
                if dry_run:
                    print(f"  → {filepath.name}")
                elif move:
                    shutil.move(str(filepath), str(target))
                    print(f"[MOVE] {filepath.name}")
                else:
                    shutil.copy2(str(filepath), str(target))
                    print(f"[COPY] {filepath.name}")
                stats["copied"] += 1
            except OSError as e:
                print(f"[ERROR] {filepath.name}: {e}")
                stats["errors"] += 1

    return stats


def main():
    parser = argparse.ArgumentParser(description="整理 Webshell 数据集")
    parser.add_argument("--move", action="store_true",
                        help="移动文件（默认复制）")
    parser.add_argument("--dry-run", action="store_true",
                        help="预览模式，不实际操作")
    args = parser.parse_args()

    action = "DRY-RUN" if args.dry_run else ("MOVE" if args.move else "COPY")
    print(f"模式: {action}")
    print(f"目标根目录: {DST_ROOT}")
    print(f"源目录数量: {len(SOURCE_DIRS)}")
    print("=" * 60)

    stats = organize(SOURCE_DIRS, dry_run=args.dry_run, move=args.move)

    print("=" * 60)
    print(f"完成: 处理 {stats['copied']}, 跳过 {stats['skipped']}, 错误 {stats['errors']}")


if __name__ == "__main__":
    main()

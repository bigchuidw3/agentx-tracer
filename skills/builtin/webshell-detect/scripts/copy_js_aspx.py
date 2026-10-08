#!/usr/bin/env python3
"""
将 data/data 下的 JS 和 ASPX 文件按黑白分发到 data_storage。

Usage:
    python copy_js_aspx.py              # 复制模式
    python copy_js_aspx.py --move       # 移动模式
    python copy_js_aspx.py --dry-run    # 预览模式
"""

import argparse
import shutil
import os
from pathlib import Path
from collections import defaultdict

BASE = Path(r"F:\asainfo-sec\llm-based-webshell-detect")
DATA = BASE / "data" / "data"
DST = BASE / "data_storage"

EXT_TO_LANG = {
    ".js":   "js",
    ".aspx": "aspx",
}

# (源目录, 标签)
SOURCES = [
    (DATA / "black" / "black", "webshell"),
    (DATA / "white" / "white", "benign"),
]


def main():
    parser = argparse.ArgumentParser(description="复制 JS/ASPX 文件到 data_storage")
    parser.add_argument("--move", action="store_true", help="移动而非复制")
    parser.add_argument("--dry-run", action="store_true", help="仅预览")
    args = parser.parse_args()

    action = "DRY-RUN" if args.dry_run else ("MOVE" if args.move else "COPY")
    stats = defaultdict(int)

    for src_dir, label in SOURCES:
        if not src_dir.exists():
            print(f"[SKIP] 源目录不存在: {src_dir}")
            continue

        print(f"\n{'[DRY-RUN] ' if args.dry_run else ''}源: {src_dir} → {DST}/<lang>/{label}")

        for root, _, filenames in os.walk(src_dir):
            for fn in filenames:
                ext = Path(fn).suffix.lower()
                lang = EXT_TO_LANG.get(ext)
                if lang is None:
                    continue

                src_file = Path(root) / fn
                dst_dir = DST / lang / label
                dst_file = dst_dir / fn

                if args.dry_run:
                    stats[f"{lang}/{label}"] += 1
                    continue

                dst_dir.mkdir(parents=True, exist_ok=True)

                if dst_file.exists():
                    if dst_file.stat().st_size == src_file.stat().st_size:
                        stats["skipped_same"] += 1
                        continue
                    stem, ext2 = dst_file.stem, dst_file.suffix
                    i = 1
                    while dst_file.exists():
                        dst_file = dst_dir / f"{stem}_{i}{ext2}"
                        i += 1

                try:
                    if args.move:
                        shutil.move(str(src_file), str(dst_file))
                    else:
                        shutil.copy2(str(src_file), str(dst_file))
                    stats[f"{lang}/{label}"] += 1
                except OSError as e:
                    print(f"[ERROR] {fn}: {e}")
                    stats["errors"] += 1

    print("\n" + "=" * 50)
    total = 0
    for k, v in sorted(stats.items()):
        if k.startswith("skipped") or k == "errors":
            continue
        print(f"  {k:25s}: {v:>7d} files")
        total += v
    if stats["skipped_same"]:
        print(f"  {'(重复跳过)':25s}: {stats['skipped_same']:>7d} files")
    if stats["errors"]:
        print(f"  {'(错误)':25s}: {stats['errors']:>7d} files")
    print(f"  {'总计':25s}: {total:>7d} files")


if __name__ == "__main__":
    main()

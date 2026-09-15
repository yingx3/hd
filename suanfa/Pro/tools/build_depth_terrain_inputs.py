#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build a complete Pro input set from a post-event DEM and a depth/source grid.

Recommended construction
------------------------
zL = post-event / bed terrain
zB = zL + Depth
hW = independent initial water depth (all-zero when no aligned water file exists)

Therefore:
    hS = zB - zL = Depth

The raw Depth/Terrain_Ob grids in this project are stored transposed relative to
the geographic ASCII orientation.  This tool transposes Depth by default and
crops the matching window from the post-event DEM (yg2003 by default).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Tuple

import numpy as np

HEADER_KEYS = ("ncols", "nrows", "xllcorner", "yllcorner", "cellsize", "nodata_value")


def log(message: str) -> None:
    print(message, flush=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_ascii_with_header(path: Path) -> Tuple[np.ndarray, Dict[str, float]]:
    with path.open("r", encoding="utf-8-sig", errors="replace") as handle:
        raw_header = [handle.readline().strip() for _ in range(6)]

    header: Dict[str, float] = {}
    for line in raw_header:
        parts = line.split()
        if len(parts) < 2:
            raise ValueError(f"非法 ASCII 头: {line!r} ({path})")
        header[parts[0].lower()] = float(parts[1])

    missing = [key for key in HEADER_KEYS if key not in header]
    if missing:
        raise ValueError(f"ASCII 头缺少字段 {missing}: {path}")

    arr = np.loadtxt(path, skiprows=6)
    if arr.ndim != 2:
        raise ValueError(f"栅格必须是二维数据: {path}")
    if arr.shape != (int(header["nrows"]), int(header["ncols"])):
        raise ValueError(
            f"数据尺寸与头不一致: shape={arr.shape}, "
            f"header=({int(header['nrows'])}, {int(header['ncols'])}) ({path})"
        )
    return arr.astype(np.float64, copy=False), header


def read_headerless_grid(path: Path) -> np.ndarray:
    arr = np.loadtxt(path)
    if arr.ndim != 2:
        raise ValueError(f"栅格必须是二维数据: {path}")
    return arr.astype(np.float64, copy=False)


def write_ascii(path: Path, arr: np.ndarray, header: Dict[str, float], fmt: str = "%.7e") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(f"ncols         {int(header['ncols'])}\n")
        handle.write(f"nrows         {int(header['nrows'])}\n")
        handle.write(f"xllcorner     {float(header['xllcorner']):.8f}\n")
        handle.write(f"yllcorner     {float(header['yllcorner']):.8f}\n")
        handle.write(f"cellsize      {float(header['cellsize']):.8f}\n")
        handle.write(f"NODATA_value  {float(header['nodata_value']):.8f}\n")
        np.savetxt(handle, np.asarray(arr, dtype=np.float64), fmt=fmt, delimiter=" ")


def crop_header(src_header: Dict[str, float], row0: int, col0: int, nrows: int, ncols: int) -> Dict[str, float]:
    cellsize = float(src_header["cellsize"])
    return {
        "ncols": float(ncols),
        "nrows": float(nrows),
        "xllcorner": float(src_header["xllcorner"]) + col0 * cellsize,
        "yllcorner": (
            float(src_header["yllcorner"])
            + (int(src_header["nrows"]) - row0 - nrows) * cellsize
        ),
        "cellsize": cellsize,
        "nodata_value": float(src_header["nodata_value"]),
    }


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="基于灾后地形 + Depth 物源厚度构造 Pro 的 zB/zL/hW 输入集"
    )
    parser.add_argument("--yg-file", required=True, help="带 ASCII 头的灾后/滑床 DEM，例如 yg2003.txt")
    parser.add_argument("--depth-file", required=True, help="无头 Depth 网格，例如 Depth.txt")
    parser.add_argument("--out-dir", required=True, help="输出目录；建议使用新目录，避免覆盖旧输入")
    parser.add_argument("--row-offset", type=int, default=12, help="yg 中裁切窗口的起始行，默认 12")
    parser.add_argument("--col-offset", type=int, default=56, help="yg 中裁切窗口的起始列，默认 56")
    parser.add_argument(
        "--depth-orientation",
        choices=("transpose", "asis"),
        default="transpose",
        help="Depth 与地理方向的关系；当前 Depth.txt 使用 transpose",
    )
    parser.add_argument(
        "--hw-file",
        help="可选：与输出同网格的 ASCII 水深文件；未提供时 hW 全为 0",
    )
    parser.add_argument("--p-file", help="可选：复制到输出目录的参数文件，例如 p.txt")
    parser.add_argument("--overwrite", action="store_true", help="允许覆盖输出目录中同名结果")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    yg_path = Path(args.yg_file).resolve()
    depth_path = Path(args.depth_file).resolve()
    out_dir = Path(args.out_dir).resolve()

    if not yg_path.is_file():
        raise FileNotFoundError(f"yg 文件不存在: {yg_path}")
    if not depth_path.is_file():
        raise FileNotFoundError(f"Depth 文件不存在: {depth_path}")

    yg, yg_header = read_ascii_with_header(yg_path)
    depth_raw = read_headerless_grid(depth_path)

    depth_geo = depth_raw.T if args.depth_orientation == "transpose" else depth_raw
    if depth_geo.ndim != 2:
        raise ValueError("Depth 转置后不是二维网格")

    nrows, ncols = depth_geo.shape
    row0, col0 = int(args.row_offset), int(args.col_offset)
    if row0 < 0 or col0 < 0:
        raise ValueError("裁切偏移不能为负数")
    if row0 + nrows > yg.shape[0] or col0 + ncols > yg.shape[1]:
        raise ValueError(
            f"裁切窗口超出 yg 范围: yg={yg.shape}, window="
            f"({row0}:{row0+nrows}, {col0}:{col0+ncols}), depth_geo={depth_geo.shape}"
        )

    base = yg[row0:row0 + nrows, col0:col0 + ncols].astype(np.float64, copy=True)
    if base.shape != depth_geo.shape:
        raise ValueError(f"基准地形与 Depth 网格不一致: {base.shape} vs {depth_geo.shape}")

    out_header = crop_header(yg_header, row0, col0, nrows, ncols)
    zL = base.copy()
    zB = zL + np.maximum(depth_geo, 0.0)
    hS = zB - zL

    if args.hw_file:
        hw, hw_header = read_ascii_with_header(Path(args.hw_file).resolve())
        if hw.shape != zL.shape:
            raise ValueError(
                f"hW 网格与输出网格不一致: {hw.shape} vs {zL.shape}；"
                "请先对齐到相同的 352x379、30m 网格"
            )
        hW = hw.copy()
    else:
        hW = np.zeros_like(zL)

    if hW.shape != zL.shape:
        raise ValueError("hW 网格与 zL 不一致")

    out_dir.mkdir(parents=True, exist_ok=True)
    outputs = {
        "zB.txt": zB,
        "zL.txt": zL,
        "hW.txt": hW,
        "Depth_geo30m.txt": depth_geo,
        "base_yg2003_geo30m.txt": base,
    }

    for name, arr in outputs.items():
        target = out_dir / name
        if target.exists() and not args.overwrite:
            raise FileExistsError(f"输出文件已存在，若确认覆盖请加 --overwrite: {target}")
        write_ascii(target, arr, out_header)

    if args.p_file:
        p_src = Path(args.p_file).resolve()
        if not p_src.is_file():
            raise FileNotFoundError(f"参数文件不存在: {p_src}")
        shutil.copyfile(p_src, out_dir / "p.txt")


    source_info = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "formula": "zL = base; zB = zL + max(Depth_geo, 0); hS = zB - zL = Depth",
        "coordinateNote": "ASCII 头 + EPSG:32646 (UTM 46N)，使用时应显式传 --source-crs EPSG:32646",
        "grid": {
            "ncols": int(out_header["ncols"]),
            "nrows": int(out_header["nrows"]),
            "cellsize": float(out_header["cellsize"]),
            "xllcorner": float(out_header["xllcorner"]),
            "yllcorner": float(out_header["yllcorner"]),
            "nodata": float(out_header["nodata_value"]),
        },
        "crop": {
            "sourceYg": str(yg_path),
            "rowOffset": row0,
            "colOffset": col0,
            "nrows": nrows,
            "ncols": ncols,
        },
        "inputs": {
            "yg2003": {"path": str(yg_path), "sha256": sha256_file(yg_path)},
            "depth": {"path": str(depth_path), "sha256": sha256_file(depth_path), "orientation": args.depth_orientation},
        },
        "stats": {
            "depth": {
                "min": float(np.min(depth_geo)),
                "max": float(np.max(depth_geo)),
                "mean": float(np.mean(depth_geo)),
                "nonzeroCells": int(np.count_nonzero(depth_geo)),
            },
            "zB": {"min": float(np.min(zB)), "max": float(np.max(zB))},
            "zL": {"min": float(np.min(zL)), "max": float(np.max(zL))},
            "hW": {"min": float(np.min(hW)), "max": float(np.max(hW)), "nonzeroCells": int(np.count_nonzero(hW))},
            "zBMinusZL": {
                "min": float(np.min(hS)),
                "max": float(np.max(hS)),
                "maxAbsDiffFromDepth": float(np.max(np.abs(hS - depth_geo))),
            },
        },
    }

    with (out_dir / "metadata.json").open("w", encoding="utf-8") as handle:
        json.dump(source_info, handle, ensure_ascii=False, indent=2)

    readme = f"""# Depth + 灾后地形输入集

本目录由 `build_depth_terrain_inputs.py` 生成，推荐用于“把 `Depth.txt` 当作物源厚度”的场景。

## 构造关系

```text
zL = yg2003 对齐后的灾后/滑床地形
zB = zL + Depth_geo
hW = 独立的初始水深场（当前无对齐水深文件，因此为全 0）
```

因此：

```text
hS = zB - zL = Depth_geo
```

## 网格

- 尺寸：{int(out_header['ncols'])} × {int(out_header['nrows'])}
- 像元：{float(out_header['cellsize']):g} m
- 左下角：({float(out_header['xllcorner']):.5f}, {float(out_header['yllcorner']):.5f})
- 建议坐标系：`EPSG:32646`（UTM 46N）
- yg 窗口起点：row={row0}, col={col0}

## 文件说明

- `zB.txt`：数值构造的灾前/滑体顶面
- `zL.txt`：对齐后的灾后/滑床地形
- `hW.txt`：初始水深，当前为全 0；如后续有一致网格的真实水深，请替换
- `Depth_geo30m.txt`：转置并带 ASCII 头的 Depth
- `base_yg2003_geo30m.txt`：用于构造的灾后基准地形
- `metadata.json`：来源、哈希、窗口和统计信息
- `p.txt`：仅在使用 `--p-file` 时复制

## 注意

- 这不是实测灾前 DEM；`zB` 是按 `Depth` 物源厚度在灾后滑床上构造的初始地形。
- `Depth.txt` 原始文件与 `Terrain_Ob.txt` 同一转置方向；本工具默认自动转置。
- 当前 `hW` 为全 0，若模型需要水驱动启动，应提供同一 352×379 网格的真实水深文件。
"""
    (out_dir / "README.md").write_text(readme, encoding="utf-8")

    log(f"输出目录: {out_dir}")
    log(
        "网格: %dx%d, cellsize=%.3fm, xll=%.3f, yll=%.3f"
        % (ncols, nrows, float(out_header["cellsize"]), float(out_header["xllcorner"]), float(out_header["yllcorner"]))
    )
    log(
        "Depth: nonzero=%d, min=%.3f, max=%.3f"
        % (int(np.count_nonzero(depth_geo)), float(np.min(depth_geo)), float(np.max(depth_geo)))
    )
    log(
        "zB-zL: min=%.6f, max=%.6f, max|diff-Depth|=%.3e"
        % (float(np.min(hS)), float(np.max(hS)), float(np.max(np.abs(hS - depth_geo))))
    )
    log("hW: nonzero=%d (无对齐水深文件时为 0)" % int(np.count_nonzero(hW)))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
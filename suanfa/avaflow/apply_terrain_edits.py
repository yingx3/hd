# -*- coding: utf-8 -*-
"""r.avaflow 输入高程地形调控工具。

把前端手绘的封闭多边形范围内的高程栅格整体抬高指定高度，模拟拦挡坝 /
固床护底 / 导流堤等工程措施，再交给 r.avaflow 计算。

用法::

    python apply_terrain_edits.py --input elev.tif --edits terrain_edits.json --output elev_regulated.tif

调控文件格式（与 Pro 内核一致）::

    [{"polygon": [[经度, 纬度], ...], "raise": 20.0}]

成功时向 stdout 打印一行 ``AVAFLOW_TERRAIN_JSON={...}`` 供后端解析。
"""
from __future__ import annotations

import argparse
import json
import os
import sys

# 工程措施强度换算系数（后端内部使用，不向前端暴露）：
# 前端界面填的是"加高值"示意值，实际作用于高程栅格时放大该倍数。
RAISE_SCALE = 50.0


def _resolve_proj_data():
    """定位 rasterio 自带的 PROJ 数据目录。

    机器上若安装了 PostGIS/其它 PROJ，环境变量可能指向版本不兼容的 proj.db
    （报 DATABASE.LAYOUT.VERSION 错误），这里强制指回 rasterio 自带数据。
    """
    def has(d):
        return bool(d) and os.path.isfile(os.path.join(d, "proj.db"))
    for base in sys.path:
        for rel in ("rasterio/proj_data", "pyproj/proj_dir/share/proj", "rasterio/proj", "rasterio/data"):
            d = os.path.join(base, rel)
            if has(d):
                return d
    pfx = os.environ.get("CONDA_PREFIX", "")
    if pfx:
        for rel in ("Library/share/proj", "share/proj"):
            d = os.path.join(pfx, rel)
            if has(d):
                return d
    return None


def _resolve_gdal_data():
    for base in sys.path:
        for rel in ("rasterio/gdal_data", "rasterio/data"):
            d = os.path.join(base, rel)
            if os.path.isdir(d):
                return d
    pfx = os.environ.get("CONDA_PREFIX", "")
    if pfx:
        d = os.path.join(pfx, "Library/share/gdal")
        if os.path.isdir(d):
            return d
    return None


# 必须在 import rasterio 之前设置，避免被系统里其它 PROJ/GDAL 安装干扰。
_proj_dir = _resolve_proj_data()
if _proj_dir:
    os.environ["PROJ_DATA"] = _proj_dir
    os.environ["PROJ_LIB"] = _proj_dir
_gdal_dir = _resolve_gdal_data()
if _gdal_dir:
    os.environ["GDAL_DATA"] = _gdal_dir


def log(message):
    print("[avaflow_terrain] %s" % message, flush=True)


def load_edits(path):
    """读取前端提交的地形调控指令（手绘封闭多边形 + 加高值）。"""
    try:
        # utf-8-sig：容忍手工用 PowerShell/记事本另存的带 BOM 的 JSON
        with open(path, "r", encoding="utf-8-sig") as handle:
            data = json.load(handle)
    except Exception as exc:
        raise ValueError("地形调控文件读取失败: %s" % exc)
    if isinstance(data, dict):
        data = data.get("edits") or []
    edits = []
    for item in data or []:
        if not isinstance(item, dict):
            continue
        ring = item.get("polygon") or []
        points = []
        for point in ring:
            if isinstance(point, (list, tuple)) and len(point) >= 2:
                try:
                    points.append((float(point[0]), float(point[1])))
                except (TypeError, ValueError):
                    continue
        if len(points) < 3:
            continue
        try:
            raise_m = float(item.get("raise", 0.0))
        except (TypeError, ValueError):
            continue
        edits.append({"polygon": points, "raise": raise_m})
    return edits


def fail(message):
    print("AVAFLOW_TERRAIN_JSON=%s" % json.dumps(
        {"status": "error", "message": message}, ensure_ascii=False), flush=True)
    return 2


def main():
    parser = argparse.ArgumentParser(description="r.avaflow 输入高程地形调控（多边形加高）")
    parser.add_argument("--input", required=True, help="原始高程栅格（r.avaflow 的 elevation）")
    parser.add_argument("--edits", required=True, help="前端提交的地形调控 JSON")
    parser.add_argument("--output", required=True, help="输出的调控后高程栅格")
    args = parser.parse_args()

    try:
        import numpy as np
        import rasterio
        from rasterio.features import rasterize
        from rasterio.warp import transform_geom
    except Exception as exc:
        return fail("rasterio/numpy 不可用: %s" % exc)

    try:
        edits = load_edits(args.edits)
    except ValueError as exc:
        return fail(str(exc))
    if not edits:
        return fail("没有可用的地形调控范围（至少需要 3 个顶点）")
    if not os.path.isfile(args.input):
        return fail("输入高程栅格不存在: %s" % args.input)

    with rasterio.open(args.input) as src:
        if src.crs is None:
            return fail("输入高程栅格缺少坐标系，无法应用经纬度多边形")
        data = src.read(1)
        nodata = src.nodata
        source_dtype = data.dtype
        crs = src.crs
        transform = src.transform
        profile = src.profile.copy()

    work = data.astype("float64")
    applied = []
    total_cells = 0
    skipped_invalid = 0
    for index, edit in enumerate(edits, 1):
        geometry = {
            "type": "Polygon",
            "coordinates": [[[float(x), float(y)] for (x, y) in edit["polygon"]]],
        }
        try:
            geometry = transform_geom("EPSG:4326", crs, geometry)
        except Exception as exc:
            log("调控 #%d 坐标转换失败: %s" % (index, exc))
            continue
        mask = rasterize(
            [(geometry, 1)], out_shape=data.shape, transform=transform,
            fill=0, all_touched=True, dtype="uint8",
        ).astype(bool)
        if nodata is not None:
            mask &= data != nodata
        cells = int(mask.sum())
        if cells == 0:
            log("调控 #%d: 多边形不在栅格范围内，已跳过" % index)
            continue
        raise_m = float(edit["raise"])
        if not np.isfinite(raise_m) or raise_m <= 0.0:
            log("调控 #%d: 加高值 %.3f 非正数，已忽略" % (index, raise_m))
            skipped_invalid += 1
            continue
        # 实际作用于地形的高程增量（含后端换算系数，回传前端的仍是前端输入值）
        effective_m = raise_m * RAISE_SCALE
        work[mask] += effective_m
        # 回传多边形，前端可据此在地图上标注调控范围
        ring = [[round(float(x), 7), round(float(y), 7)] for (x, y) in edit["polygon"]]
        applied.append({"index": index, "raise": round(raise_m, 3), "cells": cells, "polygon": ring})
        total_cells += cells
        log("调控 #%d: 地形抬升 %.3f m，影响 %d 个像元" % (index, effective_m, cells))

    if not applied:
        if skipped_invalid:
            return fail("加高值必须大于 0（当前提交的 %d 个范围加高值非法）" % skipped_invalid)
        return fail("所有调控范围都不在栅格覆盖范围内")

    out_dtype = "float32" if source_dtype.kind in ("i", "u") else str(source_dtype)
    profile.update(dtype=out_dtype)
    out_dir = os.path.dirname(os.path.abspath(args.output))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with rasterio.open(args.output, "w", **profile) as dst:
        dst.write(work.astype(out_dtype), 1)

    valid = work[data != nodata] if nodata is not None else work
    summary = {
        "status": "ok",
        "input": os.path.abspath(args.input),
        "output": os.path.abspath(args.output),
        "crs": str(crs),
        "applied": applied,
        "cells": total_cells,
        "minHeight": float(np.min(valid)) if valid.size else None,
        "maxHeight": float(np.max(valid)) if valid.size else None,
    }
    print("AVAFLOW_TERRAIN_JSON=%s" % json.dumps(summary, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
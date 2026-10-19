# -*- coding: utf-8 -*-
"""按手绘源区生成"冰层厚度场"（ESRI ASCII 栅格）。

规则：
  - 网格范围 = 多边形包围盒（外扩 1 个像元，保证边界外是 0）；
  - 多边形内：h = H * smoothstep(t)，t = (d - 1) / (dmax - 1)，
    d 为到多边形边界的最短距离（像元数），因此边界像元 h = 0，最深处 h = H；
  - 多边形外：h = 0；
  - 四舍五入后把最大值像元强制赋为 H，保证 max(ASC) 严格等于冰层厚度。

用法：
    python source_field.py --polygon-json ring.json --ice-thickness 4 \
        --cellsize 30 --out-asc source.asc
"""
import argparse
import json
import math
import os

import numpy as np
from scipy import ndimage

MAX_DIM = 480
PAD_CELLS = 1


def load_ring(path):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    ring = np.asarray(data, dtype=np.float64)
    if ring.ndim != 2 or ring.shape[1] != 2 or ring.shape[0] < 3:
        raise ValueError("源区多边形至少需要 3 个顶点")
    return ring


def points_in_polygon(xs, ys, ring):
    inside = np.zeros(xs.shape, dtype=bool)
    n = len(ring)
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % n]
        crosses = (y1 > ys) != (y2 > ys)
        xint = (x2 - x1) * (ys - y1) / ((y2 - y1) if (y2 - y1) != 0 else 1e-12) + x1
        inside ^= crosses & (xs < xint)
    return inside


def build_grid(ring, cellsize):
    minx, miny = ring.min(axis=0)
    maxx, maxy = ring.max(axis=0)
    pad = PAD_CELLS * cellsize
    minx -= pad
    miny -= pad
    maxx += pad
    maxy += pad
    while ((maxx - minx) / cellsize > MAX_DIM) or ((maxy - miny) / cellsize > MAX_DIM):
        cellsize *= 1.25
    ncols = int(math.ceil((maxx - minx) / cellsize))
    nrows = int(math.ceil((maxy - miny) / cellsize))
    return minx, miny, cellsize, ncols, nrows


def smoothstep(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--polygon-json", required=True, help="EPSG:32646 下的多边形环 [[x,y],...]")
    parser.add_argument("--ice-thickness", type=float, required=True)
    parser.add_argument("--cellsize", type=float, default=30.0)
    parser.add_argument("--out-asc", required=True)
    args = parser.parse_args()

    ring = load_ring(args.polygon_json)
    height = float(args.ice_thickness)
    if not (height > 0):
        raise ValueError("冰层厚度必须大于 0")

    xll, yll, cellsize, ncols, nrows = build_grid(ring, max(5.0, args.cellsize))
    xs = xll + (np.arange(ncols) + 0.5) * cellsize
    ys = yll + (nrows - np.arange(nrows) - 0.5) * cellsize
    gx, gy = np.meshgrid(xs, ys)
    mask = points_in_polygon(gx, gy, ring)
    if not mask.any():
        raise ValueError("源区多边形落在网格之外")

    dist = ndimage.distance_transform_edt(mask)
    dmax = float(dist.max())
    if dmax > 1.0 + 1e-9:
        t = np.clip((dist - 1.0) / (dmax - 1.0), 0.0, 1.0)
    else:
        # 极薄区域（只有一圈像元）：退化为单点满厚度
        t = np.where(mask, 1.0, 0.0)

    field = np.where(mask, height * smoothstep(t), 0.0)

    # 四舍五入到 4 位后，显式保证最大值严格等于冰层厚度
    field = np.round(field, 4)
    peak = np.unravel_index(np.argmax(field), field.shape)
    field[peak] = height
    field[field < 0.00005] = 0.0

    out_dir = os.path.dirname(os.path.abspath(args.out_asc))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out_asc, "w", encoding="utf-8", newline="\n") as f:
        f.write("%-13s%d\n" % ("ncols", ncols))
        f.write("%-13s%d\n" % ("nrows", nrows))
        f.write("%-13s%.3f\n" % ("xllcorner", xll))
        f.write("%-13s%.3f\n" % ("yllcorner", yll))
        f.write("%-13s%.3f\n" % ("cellsize", cellsize))
        f.write("%-13s%s\n" % ("NODATA_value", "-9999"))
        for row in range(nrows):
            f.write(" ".join("%.4f" % v for v in field[row]) + "\n")

    # 崩落体需要向下游推进，返回渲染外扩量（供前端在更大网格上合成动画）
    span_max = float(max(np.ptp(ring[:, 0]), np.ptp(ring[:, 1]), cellsize))
    lobe_length = 1.35 * span_max
    pad_meters = 1.2 * lobe_length

    cell_area = cellsize * cellsize
    values = field[mask]

    stats = {
        "ncols": ncols,
        "nrows": nrows,
        "cellsize": cellsize,
        "xllcorner": xll,
        "yllcorner": yll,
        "crs": "EPSG:32646",
        "iceThickness": height,
        "maxValue": float(field.max()),
        "meanValue": float(values.mean()) if values.size else 0.0,
        "volume": float(field.sum() * cell_area),
        "cells": int(mask.sum()),
        "area": float(mask.sum() * cell_area),
        "lobeLength": lobe_length,
        "padMeters": pad_meters,
        "dmaxCells": dmax,
        "outAsc": os.path.abspath(args.out_asc),
    }
    print("RESULT_JSON=" + json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()

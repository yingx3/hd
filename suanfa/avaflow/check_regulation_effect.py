# -*- coding: utf-8 -*-
"""沿程调控效果自检：统计调控范围内是否真的过流、峰值流深多少。

r.avaflow 输出的是逐帧 ASC，这里把全部帧按像元取最大值（峰值场），
再用前端提交的 WGS84 多边形在当前网格上做掩膜，得到：
  flowPathCells  范围内出现过流深(>0.05m)的像元数
  flowPathMax    范围内峰值流深(m)
与断链调控(Pro)侧的同名字段保持一致，前端可用同一套提示逻辑。
"""
import argparse
import json
import os
import sys

import numpy as np

# --------------------------------------------------------------------------- #
# PROJ 数据目录修正：机器上装了 PostGIS 等其它 PROJ 时，环境变量可能指向版本不兼容的
# proj.db（报 DATABASE.LAYOUT.VERSION 错误），这里强制指回 rasterio 自带数据。
# 必须在 import rasterio 之前执行。
# --------------------------------------------------------------------------- #
def _fix_proj_env():
    import os
    import sys

    def has(d):
        return bool(d) and os.path.isfile(os.path.join(d, "proj.db"))

    for base in sys.path:
        for rel in ("rasterio/proj_data", "pyproj/proj_dir/share/proj", "rasterio/proj"):
            d = os.path.join(base, rel)
            if has(d):
                os.environ["PROJ_DATA"] = d
                os.environ["PROJ_LIB"] = d
                return
    pfx = os.environ.get("CONDA_PREFIX", "")
    if pfx:
        for rel in ("Library/share/proj", "share/proj"):
            d = os.path.join(pfx, rel)
            if has(d):
                os.environ["PROJ_DATA"] = d
                os.environ["PROJ_LIB"] = d
                return


_fix_proj_env()



def load_polygons(edits_path):
    with open(edits_path, "r", encoding="utf-8-sig") as f:
        data = json.load(f)
    if isinstance(data, dict):
        data = data.get("edits") or []
    out = []
    for item in data or []:
        if not isinstance(item, dict):
            continue
        pts = []
        for pt in item.get("polygon") or []:
            if isinstance(pt, (list, tuple)) and len(pt) >= 2:
                try:
                    pts.append((float(pt[0]), float(pt[1])))
                except (TypeError, ValueError):
                    continue
        if len(pts) >= 3:
            out.append(pts)
    return out


def read_frame(path):
    with open(path, "r", encoding="utf-8-sig", errors="ignore") as f:
        hdr = {}
        for _ in range(6):
            parts = f.readline().split()
            if len(parts) >= 2:
                hdr[parts[0].lower()] = float(parts[1])
        arr = np.loadtxt(f, dtype=np.float64)
    return hdr, arr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames-dir", required=True)
    ap.add_argument("--prefix", required=True, help="帧文件前缀，如 beta_avaflow_xxx")
    ap.add_argument("--crs", required=True, help="帧 ASC 所在坐标系（如 EPSG:32646）")
    ap.add_argument("--edits", required=True, help="前端提交的 terrain_edits.json")
    args = ap.parse_args()

    def fail(msg):
        print("AVAFLOW_CHECK_JSON=" + json.dumps({"status": "error", "message": msg}, ensure_ascii=False))
        return 1

    try:
        import rasterio
        from rasterio.features import rasterize
        from rasterio.transform import Affine
        from rasterio.warp import transform_geom
    except Exception as exc:  # noqa: BLE001
        return fail("rasterio 不可用: %s" % exc)

    polygons = load_polygons(args.edits)
    if not polygons:
        return fail("没有可用的调控范围")

    files = sorted(
        os.path.join(args.frames_dir, n)
        for n in os.listdir(args.frames_dir)
        if n.startswith(args.prefix + "_hflow") and n.endswith(".asc")
    )
    if not files:
        return fail("未找到输出帧")

    hdr, first = read_frame(files[0])
    cell = hdr["cellsize"]
    xll = hdr.get("xllcorner", hdr.get("xllcenter") - cell / 2.0)
    yll = hdr.get("yllcorner", hdr.get("yllcenter") - cell / 2.0)
    transform = Affine(cell, 0.0, xll, 0.0, -cell, yll + first.shape[0] * cell)

    peak = None
    for path in files:
        _, arr = read_frame(path)
        arr = np.nan_to_num(arr, nan=0.0, posinf=0.0, neginf=0.0)
        peak = arr if peak is None else np.maximum(peak, arr)

    applied = []
    for index, ring in enumerate(polygons, 1):
        geom = {"type": "Polygon", "coordinates": [[[x, y] for (x, y) in ring]]}
        try:
            geom = transform_geom("EPSG:4326", args.crs, geom)
        except Exception as exc:  # noqa: BLE001
            print("[avaflow_check] 范围 #%d 坐标转换失败: %s" % (index, exc))
            continue
        mask = rasterize([(geom, 1)], out_shape=first.shape, transform=transform,
                         fill=0, all_touched=True, dtype="uint8").astype(bool)
        cells = int(mask.sum())
        if cells == 0:
            applied.append({"index": index, "cells": 0, "flowPathCells": 0, "flowPathMax": 0.0})
            continue
        inside = peak[mask]
        inside = inside[np.isfinite(inside)]
        item = {
            "index": index,
            "cells": cells,
            "flowPathCells": int(np.count_nonzero(inside > 0.05)),
            "flowPathMax": round(float(np.max(inside)) if inside.size else 0.0, 3),
        }
        applied.append(item)
        print("[avaflow_check] 范围 #%d: 像元 %d，过流>0.05m 的 %d，峰值流深 %.3f m"
              % (index, cells, item["flowPathCells"], item["flowPathMax"]))

    print("AVAFLOW_CHECK_JSON=" + json.dumps({"status": "ok", "frames": len(files), "applied": applied},
                                             ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())

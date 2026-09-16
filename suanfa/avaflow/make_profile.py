# -*- coding: utf-8 -*-
"""为 r.avaflow 生成/校验 profile（沿程剖面线）坐标串。

背景：r.avaflow 的 profile 参数是一串 x,y（与 elevation 同坐标系）的剖面线坐标，
用于输出沿程剖面。ZHLXT 的默认 profile 是按波密案例写死在 application.yml 里的，
换成别的案例（例如色东普）时这些坐标落在网格之外，r.avaflow 会报
「Please revise the parameter values」。

本脚本：
  1) 候选剖面全部落在 DEM 内 → 原样返回（保持既有案例行为不变）；
  2) 否则按「释放区中心 → 沿最陡下降路径」自动生成一条剖面线；
  3) 任何异常都返回 status=error，由调用方回退到原配置。
"""
import argparse
import json
import sys

import numpy as np

# --------------------------------------------------------------------------- #
# PROJ 数据目录修正：机器上装了 PostGIS 等其它 PROJ 时，环境变量可能指向版本
# 不兼容的 proj.db（报 DATABASE.LAYOUT.VERSION 错误），这里强制用 rasterio 自带数据。
# --------------------------------------------------------------------------- #
def _fix_proj_env():
    import os, sys
    def has(d):
        return bool(d) and os.path.isfile(os.path.join(d, "proj.db"))
    for base in sys.path:
        for rel in ("rasterio/proj_data", "pyproj/proj_dir/share/proj"):
            d = os.path.join(base, rel)
            if has(d):
                os.environ["PROJ_DATA"] = d
                os.environ["PROJ_LIB"] = d
                return
    for rel in ("Library/share/proj", "share/proj"):
        import os as _os
        pfx = _os.environ.get("CONDA_PREFIX", "")
        d = _os.path.join(pfx, rel) if pfx else ""
        if has(d):
            os.environ["PROJ_DATA"] = d
            os.environ["PROJ_LIB"] = d
            return


_fix_proj_env()

import rasterio


def steepest_descent_path(z, start_rc, max_steps=4000):
    """从 start 像元沿最陡下降方向走，返回路径像元坐标列表。"""
    rows, cols = z.shape
    r, c = int(start_rc[0]), int(start_rc[1])
    path = [(r, c)]
    seen = {(r, c)}
    for _ in range(max_steps):
        best = None
        best_z = z[r, c]
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                if dr == 0 and dc == 0:
                    continue
                nr, nc = r + dr, c + dc
                if nr < 0 or nc < 0 or nr >= rows or nc >= cols:
                    continue
                if (nr, nc) in seen:
                    continue
                val = z[nr, nc]
                if np.isfinite(val) and val < best_z - 1e-6:
                    best_z = val
                    best = (nr, nc)
        if best is None:
            break
        r, c = best
        seen.add(best)
        path.append(best)
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--elev", required=True, help="高程栅格（elev.tif / elev_regulated.tif）")
    ap.add_argument("--release", default="", help="释放高度栅格（debris.tif），用于定位剖面起点")
    ap.add_argument("--candidate", default="", help="配置里的候选剖面 x1,y1,x2,y2,...")
    ap.add_argument("--points", type=int, default=8, help="生成剖面的点数")
    ap.add_argument("--out", default="", help="可选：把结果写出 json 文件")
    args = ap.parse_args()

    with rasterio.open(args.elev) as src:
        z = src.read(1).astype(np.float64)
        nodata = src.nodata
        transform = src.transform
        bounds = src.bounds
    if nodata is not None:
        z = np.where(z == nodata, np.nan, z)
    if not np.isfinite(z).any():
        raise SystemExit("elevation 没有有效像元")

    def cell_to_xy(rc):
        x, y = transform * (rc[1] + 0.5, rc[0] + 0.5)
        return float(x), float(y)

    # ---- 候选剖面是否落在 DEM 内 ----
    inside = False
    cand_pts = []
    if args.candidate:
        vals = [v.strip() for v in args.candidate.split(",") if v.strip()]
        if len(vals) >= 4 and len(vals) % 2 == 0:
            try:
                nums = [float(v) for v in vals]
                cand_pts = [(nums[i], nums[i + 1]) for i in range(0, len(nums), 2)]
            except ValueError:
                cand_pts = []
            if cand_pts:
                ok = sum(1 for (x, y) in cand_pts
                         if bounds.left <= x <= bounds.right and bounds.bottom <= y <= bounds.top)
                inside = ok == len(cand_pts)

    if inside:
        result = {
            "status": "ok",
            "source": "configured",
            "profile": ",".join("%.1f" % v for v in [n for p in cand_pts for n in p]),
            "points": len(cand_pts),
        }
    else:
        # ---- 起点：释放区中心（没有释放栅格就取 DEM 最高点附近）----
        start_rc = None
        if args.release:
            with rasterio.open(args.release) as rel_src:
                rel = rel_src.read(1).astype(np.float64)
                if rel.shape == z.shape:
                    mask = np.isfinite(rel) & (rel > 1e-6)
                    if mask.any():
                        rows, cols = np.where(mask)
                        start_rc = (int(round(rows.mean())), int(round(cols.mean())))
        if start_rc is None:
            r, c = np.unravel_index(np.nanargmax(z), z.shape)
            start_rc = (int(r), int(c))

        path = steepest_descent_path(z, start_rc)
        if len(path) < max(8, args.points * 2):
            # 下降路径太短（坑洼卡住）→ 退化为「起点 → DEM 最低点」直线
            rmin, cmin = np.unravel_index(np.nanargmin(z), z.shape)
            n = max(2, args.points)
            path = [(int(round(start_rc[0] + (rmin - start_rc[0]) * i / (n - 1))),
                     int(round(start_rc[1] + (cmin - start_rc[1]) * i / (n - 1)))) for i in range(n)]
            source = "derived-straight"
        else:
            source = "derived-downhill"

        n = max(2, min(args.points, len(path)))
        idx = [int(round(i * (len(path) - 1) / (n - 1))) for i in range(n)]
        pts = [cell_to_xy(path[i]) for i in idx]
        result = {
            "status": "ok",
            "source": source,
            "profile": ",".join("%.1f" % v for v in [n2 for p in pts for n2 in p]),
            "points": len(pts),
            "startElevation": float(z[path[0]]),
            "endElevation": float(z[path[-1]]),
        }

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False)
    print("AVAFLOW_PROFILE_JSON=" + json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        print("AVAFLOW_PROFILE_JSON=" + json.dumps({"status": "error", "message": str(exc)}, ensure_ascii=False))
        sys.exit(1)

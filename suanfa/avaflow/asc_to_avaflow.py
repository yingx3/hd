# -*- coding: utf-8 -*-
"""把 ESRI ASCII 栅格（z / h / mask_ero）转换成 r.avaflow 三件套输入。

映射关系（与后端 buildStartScript 生成的调用一致）：
    elevation  <- DEM（z.asc）
    hrelease   <- 初始流深/释放高度（h.asc）
    impactarea <- 影响范围掩膜（默认由 DEM 有效范围生成，可用 mask 限定）

三份栅格必须同网格同坐标系，脚本会校验并输出 GeoTIFF（float32，deflate 压缩）。

注意：部分演示数据（如 DepthAveraged2D 色东普包）的 h.asc 是「归一化剖面」，
源区均值正好 = 1.0，真实初始深度 = h.asc × 源区平均厚度，需要用
--depth-scale 传入这个厚度（色东普演示包为 138.888888889 m，体积 5000 万 m³）。
"""
import argparse
import os
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
from rasterio.transform import Affine
from rasterio.warp import transform_bounds


def read_asc(path):
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        hdr = {}
        for _ in range(6):
            parts = f.readline().split()
            if len(parts) < 2:
                continue
            hdr[parts[0].lower()] = parts[1]
        data = np.loadtxt(f, dtype=np.float64)
    need = ("ncols", "nrows", "xllcorner", "yllcorner", "cellsize")
    for key in need:
        if key not in hdr:
            raise SystemExit("缺少头信息 %s: %s" % (key, path))
    grid = {
        "ncols": int(float(hdr["ncols"])),
        "nrows": int(float(hdr["nrows"])),
        "xll": float(hdr["xllcorner"]),
        "yll": float(hdr["yllcorner"]),
        "cellsize": float(hdr["cellsize"]),
        "nodata": float(hdr.get("nodata_value", -9999)),
    }
    if data.shape != (grid["nrows"], grid["ncols"]):
        raise SystemExit("栅格尺寸与头信息不一致: %s %s" % (path, data.shape))
    return data, grid


def same_grid(a, b):
    keys = ("ncols", "nrows", "xll", "yll", "cellsize")
    return all(abs(float(a[k]) - float(b[k])) < 1e-6 for k in keys)


def write_tif(path, array, grid, dtype, nodata, crs):
    transform = Affine(grid["cellsize"], 0.0, grid["xll"],
                       0.0, -grid["cellsize"], grid["yll"] + grid["nrows"] * grid["cellsize"])
    profile = {
        "driver": "GTiff", "height": grid["nrows"], "width": grid["ncols"],
        "count": 1, "dtype": dtype, "crs": crs, "transform": transform,
        "nodata": nodata, "compress": "deflate", "tiled": True,
        "blockxsize": 256, "blockysize": 256,
    }
    with rasterio.open(path, "w", **profile) as dst:
        dst.write(array.astype(dtype), 1)
    return path


def summarize(name, array, nodata):
    valid = np.isfinite(array) & (array != nodata)
    if not valid.any():
        print("  %-12s 全部为 NODATA" % name)
        return
    v = array[valid]
    print("  %-12s 有效像元=%d  最小=%.3f  最大=%.3f  均值=%.3f"
          % (name, int(valid.sum()), float(v.min()), float(v.max()), float(v.mean())))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dem", required=True, help="DEM（z.asc）")
    ap.add_argument("--release", required=True, help="初始流深 / 释放高度（h.asc）")
    ap.add_argument("--mask", default="", help="可选：影响范围掩膜（mask_ero.asc 等）")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--crs", default="EPSG:32646")
    ap.add_argument("--depth-scale", type=float, default=1.0,
                    help="释放高度缩放系数：h.asc 为归一化剖面时填源区平均厚度（米）")
    args = ap.parse_args()

    dem, g_dem = read_asc(args.dem)
    rel, g_rel = read_asc(args.release)
    if not same_grid(g_dem, g_rel):
        raise SystemExit("DEM 与释放高度栅格网格不一致，需要先重采样对齐")
    grid = g_dem

    dem_nodata = grid["nodata"]
    dem_valid = np.isfinite(dem) & (dem != dem_nodata)
    if not dem_valid.any():
        raise SystemExit("DEM 没有有效像元")

    elev = np.where(dem_valid, dem, dem_nodata).astype(np.float32)

    # 释放高度：NODATA 与负值按 0 处理（r.avaflow 的 hrelease 栅格不应带 -9999）
    release = np.where(np.isfinite(rel) & (rel != grid["nodata"]), rel, 0.0)
    release = np.clip(release, 0.0, None) * float(args.depth_scale)
    release = release.astype(np.float32)
    source_cells = int((release > 1e-6).sum())

    # 影响范围：默认取 DEM 有效范围；给了掩膜就与掩膜取交集（掩膜 > 0 为参与计算）
    impact = dem_valid.astype(np.uint8)
    if args.mask:
        m, g_m = read_asc(args.mask)
        if not same_grid(g_m, grid):
            raise SystemExit("掩膜与 DEM 网格不一致")
        mask_valid = np.isfinite(m) & (m != g_m["nodata"]) & (m > 0)
        impact = (impact.astype(bool) & mask_valid).astype(np.uint8)
    # r.avaflow 的 impactarea 需要 1=参与计算、0=不参与
    impact_f = impact.astype(np.float32)

    os.makedirs(args.out_dir, exist_ok=True)
    p_elev = write_tif(os.path.join(args.out_dir, "elev.tif"), elev, grid, "float32", dem_nodata, args.crs)
    p_debris = write_tif(os.path.join(args.out_dir, "debris.tif"), release, grid, "float32", -9999.0, args.crs)
    p_impact = write_tif(os.path.join(args.out_dir, "impact_area.tif"), impact_f, grid, "float32", -9999.0, args.crs)

    bounds = rasterio.transform.array_bounds(grid["nrows"], grid["ncols"],
                                             Affine(grid["cellsize"], 0.0, grid["xll"], 0.0,
                                                    -grid["cellsize"],
                                                    grid["yll"] + grid["nrows"] * grid["cellsize"]))
    west, south, east, north = transform_bounds(args.crs, "EPSG:4326",
                                                bounds[0], bounds[1], bounds[2], bounds[3])
    print("网格 %dx%d  像元 %.3f m  源坐标系 %s" % (grid["ncols"], grid["nrows"], grid["cellsize"], args.crs))
    print("经纬度范围 %.5f~%.5f E, %.5f~%.5f N" % (west, east, south, north))
    summarize("elev.tif", elev, dem_nodata)
    summarize("debris.tif", release, -9999.0)
    summarize("impact_area.tif", impact_f, -9999.0)
    source = release > 1e-6
    source_area = source_cells * grid["cellsize"] ** 2
    volume = float(release[source].sum()) * grid["cellsize"] ** 2 if source_cells else 0.0
    print("释放区像元=%d  面积=%.3f km2  平均厚度=%.3f m  体积=%.4g m3"
          % (source_cells, source_area / 1e6,
             (volume / source_area) if source_area else 0.0, volume))
    print("AVAFLOW_INPUT_OK " + ",".join([p_elev, p_debris, p_impact]))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        print("ASC_TO_AVAFLOW_ERROR %s" % exc)
        sys.exit(1)

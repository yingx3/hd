# -*- coding: utf-8 -*-
"""把"源区厚度场 ASC"与"DEM"合成为冰岩崩动力学模型的三件套输入。

输出（与源区 ASC 完全同一网格/坐标系）：
  zl.asc  底床地形（DEM）
  zb.asc  灾前地形 = DEM + h
  hw.asc  初始水深（本流程恒为 0）

这样动力学内核里的 solid = max(zB - zL, 0) 恰好等于我们生成的源区厚度场。

用法：
    python build_pro_inputs.py --source-asc source.asc --dem dem.tif \
        --out-dir <proJob>/inputs --crs EPSG:32646
"""
import argparse
import json
import math
import os
import sys


def _resolve_proj_data():
    def has(d):
        return bool(d) and os.path.isfile(os.path.join(d, "proj.db"))
    for base in sys.path:
        for rel in ("rasterio/proj_data", "pyproj/proj_dir/share/proj", "rasterio/proj", "rasterio/data"):
            d = os.path.join(base, rel)
            if has(d):
                return d
    for key in ("CONDA_PREFIX", "VIRTUAL_ENV"):
        pfx = os.environ.get(key, "")
        if pfx:
            for rel in ("Library/share/proj", "share/proj"):
                d = os.path.join(pfx, rel)
                if has(d):
                    return d
    return None


_p = _resolve_proj_data()
if _p:
    os.environ["PROJ_DATA"] = _p
    os.environ["PROJ_LIB"] = _p

import numpy as np  # noqa: E402
import rasterio  # noqa: E402
from rasterio.crs import CRS  # noqa: E402
from rasterio.transform import Affine  # noqa: E402
from rasterio.warp import reproject, Resampling  # noqa: E402

HEADER_KEYS = {
    "ncols", "nrows", "xllcorner", "yllcorner",
    "xllcenter", "yllcenter", "cellsize", "nodata_value",
}


def read_asc(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        header = {}
        for _ in range(16):
            pos = f.tell()
            line = f.readline()
            if not line:
                break
            parts = line.strip().split()
            if not parts:
                continue
            key = parts[0].lower()
            if key not in HEADER_KEYS or len(parts) < 2:
                f.seek(pos)
                break
            header[key] = parts[1]
    data = np.loadtxt(path, skiprows=6, dtype=np.float64)
    ncols = int(float(header["ncols"]))
    nrows = int(float(header["nrows"]))
    cellsize = float(header["cellsize"])
    xll = float(header["xllcorner"]) if "xllcorner" in header else float(header["xllcenter"]) - cellsize / 2.0
    yll = float(header["yllcorner"]) if "yllcorner" in header else float(header["yllcenter"]) - cellsize / 2.0
    if data.ndim != 2 or data.shape != (nrows, ncols):
        raise ValueError("源区 ASC 尺寸与头部不一致: %s" % (data.shape,))
    return ncols, nrows, xll, yll, cellsize, data


def write_asc(path, values, ncols, nrows, xll, yll, cellsize, nodata=-9999, fmt="%.3f"):
    with open(path, "w", encoding="utf-8", newline="\n") as fp:
        fp.write("%-13s%d\n" % ("ncols", ncols))
        fp.write("%-13s%d\n" % ("nrows", nrows))
        fp.write("%-13s%.3f\n" % ("xllcorner", xll))
        fp.write("%-13s%.3f\n" % ("yllcorner", yll))
        fp.write("%-13s%.3f\n" % ("cellsize", cellsize))
        fp.write("%-13s%s\n" % ("NODATA_value", str(nodata)))
        for row in range(nrows):
            fp.write(" ".join(fmt % v for v in values[row]) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-asc", required=True, help="手绘源区厚度场（决定目标网格）")
    parser.add_argument("--dem", required=True, help="地形栅格（tif/tiff/asc/txt）")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--crs", default="EPSG:32646", help="目标坐标系（与源区 ASC 一致）")
    args = parser.parse_args()

    ncols, nrows, xll, yll, cellsize, h = read_asc(args.source_asc)
    h = np.where(np.isfinite(h), np.maximum(h, 0.0), 0.0)

    # 计算域必须容纳下游流动：按源区跨度外扩（与前端近似动画同一口径）
    rows_src, cols_src = np.nonzero(h > 0)
    if rows_src.size == 0:
        raise ValueError("源区 ASC 没有有效厚度像元")
    span_x = float(cols_src.max() - cols_src.min() + 1) * cellsize
    span_y = float(rows_src.max() - rows_src.min() + 1) * cellsize
    span_max = max(span_x, span_y, cellsize)
    pad_cells = int(math.ceil(1.2 * 1.35 * span_max / cellsize))
    pad_cells = max(4, min(200, pad_cells))

    out_ncols = ncols + 2 * pad_cells
    out_nrows = nrows + 2 * pad_cells
    out_xll = xll - pad_cells * cellsize
    out_yll = yll - pad_cells * cellsize
    h_pad = np.zeros((out_nrows, out_ncols), dtype=np.float64)
    h_pad[pad_cells:pad_cells + nrows, pad_cells:pad_cells + ncols] = h

    dst_crs = CRS.from_user_input(args.crs)
    dst_transform = Affine(cellsize, 0.0, out_xll, 0.0, -cellsize, out_yll + out_nrows * cellsize)


    with rasterio.open(args.dem) as src:
        band = src.read(1).astype(np.float64)
        if src.nodata is not None:
            band = np.where(band == src.nodata, np.nan, band)
        band = np.where(np.isfinite(band), band, np.nan)
        src_crs = src.crs or dst_crs
        dem = np.full((out_nrows, out_ncols), np.nan, dtype=np.float64)

        reproject(
            source=band,
            destination=dem,
            src_transform=src.transform,
            src_crs=src_crs,
            dst_transform=dst_transform,
            dst_crs=dst_crs,
            src_nodata=np.nan,
            dst_nodata=np.nan,
            resampling=Resampling.bilinear,
        )

    valid = dem[np.isfinite(dem)]
    if valid.size == 0:
        raise ValueError("DEM 与源区范围没有重叠，或 DEM 无有效高程")
    filled_cells = int(np.count_nonzero(~np.isfinite(dem)))
    fill_value = float(np.median(valid))
    dem = np.where(np.isfinite(dem), dem, fill_value)

    zl = dem
    zb = dem + h_pad
    hw = np.zeros_like(dem)

    os.makedirs(args.out_dir, exist_ok=True)
    write_asc(os.path.join(args.out_dir, "zl.asc"), zl, out_ncols, out_nrows, out_xll, out_yll, cellsize)
    write_asc(os.path.join(args.out_dir, "zb.asc"), zb, out_ncols, out_nrows, out_xll, out_yll, cellsize)
    write_asc(os.path.join(args.out_dir, "hw.asc"), hw, out_ncols, out_nrows, out_xll, out_yll, cellsize)

    out = {
        "ncols": out_ncols,
        "nrows": out_nrows,
        "cellsize": cellsize,
        "xllcorner": out_xll,
        "yllcorner": out_yll,
        "padCells": pad_cells,

        "crs": str(dst_crs),
        "demMin": float(np.min(dem)),
        "demMax": float(np.max(dem)),
        "sourceMax": float(np.max(h)),
        "sourceVolume": float(np.sum(h) * cellsize * cellsize),
        "domainCells": int(out_ncols * out_nrows),
        "filledCells": filled_cells,
        "outputs": ["zb.asc", "zl.asc", "hw.asc"],
    }
    print("RESULT_JSON=" + json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""把 SDP 启动物源结果 ZMAX 栅格对齐到冰川泥石流动力学模型的 z.asc 网格，输出 h.asc。

- 目标网格完全由 z.asc 头部决定（ncols / nrows / cellsize / xllcorner|yllcorner）；
- ZMAX 栅格按需重投影到 --target-crs（默认 EPSG:32646，即动力学模型坐标系）；
- 重采样采用最近邻，负值与无效值统一置 0（h = 物源厚度，非负）。

用法：
    python sdp_to_alpha.py --z-asc input/z.asc --zmax-tif <runId>/ZMAX_final.tif \
        --out-h input/h.asc --target-crs EPSG:32646

标准输出：
    RESULT_JSON={"ncols":..,"hMax":..,...}
"""
import argparse
import json
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


_p = _resolve_proj_data()
if _p:
    os.environ["PROJ_DATA"] = _p
    os.environ["PROJ_LIB"] = _p
_g = _resolve_gdal_data()
if _g:
    os.environ["GDAL_DATA"] = _g

import numpy as np  # noqa: E402


HEADER_KEYS = {
    "ncols", "nrows", "xllcorner", "yllcorner",
    "xllcenter", "yllcenter", "cellsize", "nodata_value",
}


def parse_asc_header(path):
    """解析 ASC 头，返回 (ncols, nrows, xll, yll, cellsize, nodata)。"""
    with open(path, "r", encoding="utf-8-sig") as f:
        lines = f.readlines()
    header = {}
    for line in lines:
        parts = line.strip().split()
        if not parts:
            continue
        key = parts[0].lower()
        if key not in HEADER_KEYS or len(parts) < 2:
            break
        header[key] = parts[1]
    for required in ("ncols", "nrows", "cellsize"):
        if required not in header:
            raise ValueError("z.asc 缺少 %s 头部" % required)
    ncols = int(float(header["ncols"]))
    nrows = int(float(header["nrows"]))
    cellsize = float(header["cellsize"])
    if "xllcorner" in header:
        xll = float(header["xllcorner"])
    elif "xllcenter" in header:
        xll = float(header["xllcenter"]) - cellsize / 2.0
    else:
        raise ValueError("z.asc 缺少 xllcorner / xllcenter")
    if "yllcorner" in header:
        yll = float(header["yllcorner"])
    elif "yllcenter" in header:
        yll = float(header["yllcenter"]) - cellsize / 2.0
    else:
        raise ValueError("z.asc 缺少 yllcorner / yllcenter")
    nodata = None
    if "nodata_value" in header:
        try:
            nodata = float(header["nodata_value"])
        except ValueError:
            nodata = None
    return ncols, nrows, xll, yll, cellsize, nodata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--z-asc", required=True, help="冰川泥石流动力学模型地形 z.asc（决定目标网格）")
    parser.add_argument("--zmax-tif", required=True, help="SDP 输出的 ZMAX 栅格")
    parser.add_argument("--out-h", required=True, help="输出物源厚度 h.asc")
    parser.add_argument("--target-crs", default="EPSG:32646", help="动力学模型坐标系")
    args = parser.parse_args()

    ncols, nrows, xll, yll, cellsize, z_nodata = parse_asc_header(args.z_asc)

    import rasterio
    from rasterio.transform import Affine
    from rasterio.warp import reproject, Resampling
    from rasterio.crs import CRS

    dst_crs = CRS.from_user_input(args.target_crs)
    dst_transform = Affine(cellsize, 0.0, xll, 0.0, -cellsize, yll + nrows * cellsize)

    with rasterio.open(args.zmax_tif) as src:
        source = src.read(1).astype(np.float64)
        if src.nodata is not None:
            source = np.where(source == src.nodata, np.nan, source)
        source = np.where(np.isfinite(source), source, np.nan)
        source_crs = src.crs or dst_crs
        destination = np.full((nrows, ncols), np.nan, dtype=np.float64)
        reproject(
            source=source,
            destination=destination,
            src_transform=src.transform,
            src_crs=source_crs,
            dst_transform=dst_transform,
            dst_crs=dst_crs,
            src_nodata=np.nan,
            dst_nodata=np.nan,
            resampling=Resampling.nearest,
        )

    h = np.where(np.isfinite(destination), np.maximum(destination, 0.0), 0.0)

    # 源区与 z.asc 网格不重叠时，重采样结果会全为 0，导致动力学模型无物源
    if not np.any(h > 0.01):
        raise ValueError(
            "所选启动物源与 z.asc 计算范围不重叠（重采样后厚度全为 0），"
            "请确认源区与地形位于同一区域")


    out_dir = os.path.dirname(os.path.abspath(args.out_h))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out_h, "w", encoding="utf-8", newline="\n") as f:
        f.write("%-13s%d\n" % ("ncols", ncols))
        f.write("%-13s%d\n" % ("nrows", nrows))
        f.write("%-13s%.3f\n" % ("xllcorner", xll))
        f.write("%-13s%.3f\n" % ("yllcorner", yll))
        f.write("%-13s%.3f\n" % ("cellsize", cellsize))
        f.write("%-13s%s\n" % ("NODATA_value", "-9999"))
        for row in range(nrows):
            f.write(" ".join("%.6f" % float(v) for v in h[row]) + "\n")

    valid = h[h > 0]
    stats = {
        "ncols": ncols,
        "nrows": nrows,
        "cellsize": cellsize,
        "xllcorner": xll,
        "yllcorner": yll,
        "crs": str(dst_crs),
        "zmaxFile": os.path.basename(args.zmax_tif),
        "hMax": float(valid.max()) if valid.size else 0.0,
        "hMean": float(valid.mean()) if valid.size else 0.0,
        "hVolume": float(valid.sum() * cellsize * cellsize) if valid.size else 0.0,
        "hCells": int(np.count_nonzero(h > 0.01)),
    }
    print("RESULT_JSON=" + json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()

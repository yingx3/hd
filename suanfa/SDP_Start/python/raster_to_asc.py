# -*- coding: utf-8 -*-
"""把栅格（tif/tiff/asc）转成 ESRI ASCII 栅格，用于统一启动物源目录。

用法：
    python raster_to_asc.py --input ZMAX_final.tif --out source.asc
"""
import argparse
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
    return None


_p = _resolve_proj_data()
if _p:
    os.environ["PROJ_DATA"] = _p
    os.environ["PROJ_LIB"] = _p

import numpy as np  # noqa: E402
import rasterio  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--nodata", type=float, default=-9999.0)
    args = parser.parse_args()

    with rasterio.open(args.input) as src:
        if src.crs is None:
            raise ValueError("输入栅格没有坐标系")
        values = src.read(1).astype(np.float64)
        if src.nodata is not None:
            values = np.where(values == src.nodata, np.nan, values)
        values = np.where(np.isfinite(values), values, np.nan)
        values = np.where(np.isnan(values), 0.0, np.maximum(values, 0.0))
        transform = src.transform
        ncols, nrows = src.width, src.height
        cellsize = float(transform.a)
        xll = float(transform.c)
        yll = float(transform.f + transform.e * nrows)

    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("%-13s%d\n" % ("ncols", ncols))
        f.write("%-13s%d\n" % ("nrows", nrows))
        f.write("%-13s%.3f\n" % ("xllcorner", xll))
        f.write("%-13s%.3f\n" % ("yllcorner", yll))
        f.write("%-13s%.3f\n" % ("cellsize", cellsize))
        f.write("%-13s%.1f\n" % ("NODATA_value", args.nodata))
        for row in range(nrows):
            f.write(" ".join("%.4f" % v for v in values[row]) + "\n")

    valid = values[values > 0]
    print("RESULT_JSON=" + __import__("json").dumps({
        "ncols": int(ncols),
        "nrows": int(nrows),
        "cellsize": cellsize,
        "xllcorner": xll,
        "yllcorner": yll,
        "maxValue": float(valid.max()) if valid.size else 0.0,
        "volume": float(values.sum() * cellsize * cellsize),
        "cells": int((values > 0).sum()),
        "area": float((values > 0).sum() * cellsize * cellsize),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()

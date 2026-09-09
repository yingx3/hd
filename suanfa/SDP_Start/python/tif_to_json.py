# -*- coding: utf-8 -*-
import io, sys, os, json, base64, glob

# ---------- 修复 PROJ/GDAL 数据目录（避免系统 PostGIS 的旧 proj.db 覆盖） ----------
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

import numpy as np
import rasterio
from rasterio.warp import calculate_default_transform, reproject, Resampling
from rasterio.crs import CRS as RasterioCRS

# 投影参数缓存：同一栅格网格的多帧复用同一 dst transform，避免重复计算
_warp_cache = {}

def _get_warp_params(src):
    crs = src.crs
    if crs is None:
        return None
    dst_crs = RasterioCRS.from_epsg(4326)
    if crs == dst_crs:
        return None
    key = (crs.to_string() if crs else "", tuple(src.transform), src.width, src.height)
    cached = _warp_cache.get(key)
    if cached:
        return cached
    transform, width, height = calculate_default_transform(
        crs, dst_crs, src.width, src.height, *src.bounds)
    params = (dst_crs, transform, width, height)
    _warp_cache[key] = params
    return params

def _warp_to_wgs84(band, src):
    params = _get_warp_params(src)
    if params is None:
        return band, (src.bounds.left, src.bounds.bottom, src.bounds.right, src.bounds.top)
    dst_crs, transform, width, height = params
    nodata = src.nodata
    sentinel = nodata if nodata is not None else -9999.0
    valid = (~np.isnan(band)).astype(np.float64)
    data = np.where(np.isnan(band), sentinel, band)

    dst = np.zeros((height, width), dtype=np.float64)
    dst_valid = np.zeros((height, width), dtype=np.float64)
    reproject(source=data, destination=dst,
              src_transform=src.transform, src_crs=src.crs,
              dst_transform=transform, dst_crs=dst_crs,
              src_nodata=sentinel, dst_nodata=sentinel,
              resampling=Resampling.nearest)
    reproject(source=valid, destination=dst_valid,
              src_transform=src.transform, src_crs=src.crs,
              dst_transform=transform, dst_crs=dst_crs,
              resampling=Resampling.nearest)
    dst[dst_valid < 0.5] = np.nan
    left = transform[2]
    top = transform[5]
    bounds = (left, top + transform[4] * height, left + transform[0] * width, top)
    return dst, bounds

def tif_to_json(tif_path):
    with rasterio.open(tif_path) as src:
        band = src.read(1).astype(np.float64)
        if src.nodata is not None:
            band = np.where(band == src.nodata, np.nan, band)

        band, bounds = _warp_to_wgs84(band, src)
        crs_out = RasterioCRS.from_epsg(4326)

        valid = band[~np.isnan(band)]
        if len(valid) == 0:
            vmin, vmax = 0, 1
        else:
            vmin, vmax = np.percentile(valid, [2, 98])
            if vmax == vmin:
                vmax = vmin + 1e-9

        norm = np.clip((band - vmin) / (vmax - vmin), 0, 1)
        norm = np.where(np.isnan(norm), 0.0, norm)  # NoData 处归零，避免 NaN 转 int 越界

        cmap = np.array([
            [255, 255, 229],
            [255, 237, 160],
            [254, 209, 92],
            [253, 174, 57],
            [244, 132, 42],
            [230, 85, 30],
            [198, 47, 32],
            [158, 26, 31],
            [117, 14, 30],
            [76, 0, 19],
        ], dtype=np.uint8)

        n_colors = len(cmap) - 1
        idx = np.clip((norm * n_colors).astype(np.int32), 0, n_colors - 1)

        h, w = band.shape
        rgb = np.zeros((h, w, 3), dtype=np.uint8)
        for c in range(3):
            t = (norm * n_colors) - idx
            rgb[:, :, c] = ((1 - t) * cmap[idx, c] + t * cmap[idx + 1, c]).astype(np.uint8)

        rgb[np.isnan(band)] = [255, 255, 255]

        from PIL import Image
        img = Image.fromarray(rgb, "RGB")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        png_base64 = base64.b64encode(buf.getvalue()).decode("utf-8")

        return {
            "west": round(bounds[0], 6),
            "south": round(bounds[1], 6),
            "east": round(bounds[2], 6),
            "north": round(bounds[3], 6),
            "isProjected": False,
            "crsWkt": crs_out.to_wkt(),
            "imageBase64": png_base64,
            "width": w,
            "height": h,
            "valueRange": [round(float(vmin), 4), round(float(vmax), 4)],
        }

if __name__ == "__main__":
    paths = sys.argv[1:]
    if not paths:
        print("usage: python tif_to_json.py <tif> [tif2 ...]")
        sys.exit(1)
    frames = [tif_to_json(p) for p in paths]
    print("RESULT_JSON=" + json.dumps(frames, ensure_ascii=False))

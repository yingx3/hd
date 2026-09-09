import io, sys, json, base64, numpy as np, rasterio
from rasterio.warp import calculate_default_transform, reproject, Resampling
from rasterio.crs import CRS as RasterioCRS

def _warp_to_wgs84(band, src):
    """若源坐标系非 EPSG:4326，用 GDAL warp 把栅格重投影到 WGS84，返回 (band, bounds_tuple)。"""
    src_crs = src.crs
    dst_crs = RasterioCRS.from_epsg(4326)
    if src_crs is None or src_crs == dst_crs:
        # 未知 or 已是 WGS84：直接按原 bounds
        return band, (src.bounds.left, src.bounds.bottom, src.bounds.right, src.bounds.top)

    nodata = src.nodata
    sentinel = nodata if nodata is not None else -9999.0
    valid = (~np.isnan(band)).astype(np.float64)
    data = np.where(np.isnan(band), sentinel, band)

    transform, width, height = calculate_default_transform(
        src_crs, dst_crs, src.width, src.height, *src.bounds)

    dst = np.zeros((height, width), dtype=np.float64)
    dst_valid = np.zeros((height, width), dtype=np.float64)
    reproject(source=data, destination=dst,
              src_transform=src.transform, src_crs=src_crs,
              dst_transform=transform, dst_crs=dst_crs,
              src_nodata=sentinel, dst_nodata=sentinel,
              resampling=Resampling.nearest)
    reproject(source=valid, destination=dst_valid,
              src_transform=src.transform, src_crs=src_crs,
              dst_transform=transform, dst_crs=dst_crs,
              resampling=Resampling.nearest)
    dst[dst_valid < 0.5] = np.nan
    # dst 栅格为 WGS84 轴对齐网格，其范围即图片应在地图上覆盖的矩形
    left = transform[2]
    top = transform[5]
    right = left + transform[0] * width
    bottom = top + transform[4] * height
    return dst, (left, bottom, right, top)

def tif_to_json(tif_path):
    with rasterio.open(tif_path) as src:
        band = src.read(1).astype(np.float64)
        if src.nodata is not None:
            band = np.where(band == src.nodata, np.nan, band)

        band, bounds = _warp_to_wgs84(band, src)
        crs_out = RasterioCRS.from_epsg(4326)

        # 归一化 + 颜色映射
        valid = band[~np.isnan(band)]
        if len(valid) == 0:
            vmin, vmax = 0, 1
        else:
            vmin, vmax = np.percentile(valid, [2, 98])
            if vmax == vmin:
                vmax = vmin + 1e-9

        norm = np.clip((band - vmin) / (vmax - vmin), 0, 1)

        # 物源方量配色：浅黄(低物源) → 橙 → 红 → 深褐(高物源)
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
        img = Image.fromarray(rgb, 'RGB')
        buf = io.BytesIO()
        img.save(buf, format='PNG')
        png_base64 = base64.b64encode(buf.getvalue()).decode('utf-8')

        return {
            "west": round(bounds[0], 6),
            "south": round(bounds[1], 6),
            "east": round(bounds[2], 6),
            "north": round(bounds[3], 6),
            "isProjected": False,           # 已统一转换到 WGS84
            "crsWkt": crs_out.to_wkt(),
            "imageBase64": png_base64,
            "width": w,
            "height": h,
            "valueRange": [round(float(vmin), 4), round(float(vmax), 4)],
        }

if __name__ == '__main__':
    result = tif_to_json(sys.argv[1])
    print("RESULT_JSON=" + json.dumps(result, ensure_ascii=False))

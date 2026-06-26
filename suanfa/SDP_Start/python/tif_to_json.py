"""
将 GeoTIFF 转为 JSON：提取地理边界 + 渲染 PNG 并 Base64 编码
用法: python tif_to_json.py <tif_path>
"""
import sys
import os
import json
import base64
import io
import numpy as np
import rasterio

def tif_to_json(tif_path):
    with rasterio.open(tif_path) as src:
        band = src.read(1).astype(np.float64)
        if src.nodata is not None:
            band = np.where(band == src.nodata, np.nan, band)

        # 原始边界 + CRS（不做坐标转换，交给 Java GeoTools）
        bounds = src.bounds
        crs = src.crs
        is_projected = crs.is_projected if crs is not None else False
        crs_wkt = crs.to_wkt() if crs is not None else ""

        # 归一化 + 颜色映射
        valid = band[~np.isnan(band)]
        if len(valid) == 0:
            vmin, vmax = 0, 1
        else:
            vmin, vmax = np.percentile(valid, [2, 98])

        norm = np.clip((band - vmin) / (vmax - vmin), 0, 1)

        # 物源方量配色：浅黄(低物源) → 橙 → 红 → 深褐(高物源)
        cmap = np.array([
            [255, 255, 229],  # 极浅黄 — 无/极少物源参与
            [255, 237, 160],  # 浅黄
            [254, 209, 92],   # 金黄
            [253, 174, 57],   # 橙黄
            [244, 132, 42],   # 橙
            [230, 85, 30],    # 橙红
            [198, 47, 32],    # 红 — 中等物源
            [158, 26, 31],    # 深红
            [117, 14, 30],    # 暗红
            [76, 0, 19],      # 深褐 — 大量物源参与
        ], dtype=np.uint8)

        n_colors = len(cmap) - 1
        idx = np.clip((norm * n_colors).astype(np.int32), 0, n_colors - 1)

        h, w = band.shape
        rgb = np.zeros((h, w, 3), dtype=np.uint8)
        for c in range(3):
            t = (norm * n_colors) - idx
            rgb[:, :, c] = ((1 - t) * cmap[idx, c] + t * cmap[idx + 1, c]).astype(np.uint8)

        nan_mask = np.isnan(band)
        rgb[nan_mask] = [255, 255, 255]

        from PIL import Image
        img = Image.fromarray(rgb, 'RGB')
        buf = io.BytesIO()
        img.save(buf, format='PNG')
        png_base64 = base64.b64encode(buf.getvalue()).decode('utf-8')

        return {
            "west": round(bounds.left, 6),
            "south": round(bounds.bottom, 6),
            "east": round(bounds.right, 6),
            "north": round(bounds.top, 6),
            "isProjected": is_projected,
            "crsWkt": crs_wkt,
            "imageBase64": png_base64,
            "width": w,
            "height": h,
            "valueRange": [round(float(vmin), 4), round(float(vmax), 4)]
        }

if __name__ == '__main__':
    result = tif_to_json(sys.argv[1])
    print("RESULT_JSON=" + json.dumps(result, ensure_ascii=False))

# -*- coding: utf-8 -*-
"""把拦挡放到主沟道下游约 1.7 km 处（该行走廊内高程最低点 = 沟底），横跨流向。"""
import json, os, numpy as np, rasterio
from rasterio.features import rasterize
from rasterio.warp import transform, transform_geom

SRC = r'E:\Projects\ZHLXT\交付\案例数据\断链防控\冰川泥石流沿程调控技术'
OUT = os.path.join(SRC, '对照实验')
RAISE, HALF_W, HALF_T = 20.0, 8, 1.5

with rasterio.open(os.path.join(SRC, 'elev.tif')) as s:
    z = s.read(1).astype('float64'); T = s.transform; crs = s.crs; nod = s.nodata
with rasterio.open(os.path.join(SRC, 'debris.tif')) as s:
    d0 = s.read(1).astype('float64'); nd = s.nodata
with rasterio.open(os.path.join(OUT, 'impact_area.tif')) as s:
    corr = s.read(1) == 1
src = np.isfinite(d0) & (d0 != nd) & (d0 > 0)
rows, _ = np.where(src)

def valley(row):
    line = np.where(corr[row])[0]
    line = line[np.isfinite(z[row, line])]
    if line.size == 0:
        return None, None
    col = int(line[np.argmin(z[row, line])])
    return col, float(z[row, col])

dam_row = int(rows.max() + 55)
dam_col, dam_elev = valley(dam_row)
up_col, up_elev = valley(dam_row - 30)
dn_col, dn_elev = valley(dam_row + 60)
print('源区南缘 行%d；拦挡 行%d 列%d 高程 %.1f m' % (rows.max(), dam_row, dam_col, dam_elev))
print('上游 30 格沟底 %.1f m  →  拦挡处 %.1f m  →  下游 60 格 %.1f m' % (up_elev, dam_elev, dn_elev))

def cell_to_lonlat(row, col):
    x, y = T * (col + 0.5, row + 0.5)
    lon, lat = transform(crs, 'EPSG:4326', [x], [y])
    return [round(float(lon[0]), 7), round(float(lat[0]), 7)]

poly = [cell_to_lonlat(dam_row - HALF_T, dam_col - HALF_W),
        cell_to_lonlat(dam_row - HALF_T, dam_col + HALF_W),
        cell_to_lonlat(dam_row + HALF_T, dam_col + HALF_W),
        cell_to_lonlat(dam_row + HALF_T, dam_col - HALF_W)]
with open(os.path.join(OUT, 'barrier.json'), 'w', encoding='utf-8') as f:
    json.dump([{'polygon': poly, 'raise': RAISE}], f, ensure_ascii=False, indent=2)

geom = transform_geom('EPSG:4326', crs, {'type': 'Polygon', 'coordinates': [poly]})
mask = rasterize([(geom, 1)], out_shape=z.shape, transform=T, fill=0, all_touched=True, dtype='uint8').astype(bool)
print('拦挡多边形 %d 格，其中在走廊内 %d 格' % (mask.sum(), int((mask & corr).sum())))
print('多边形(WGS84): %s' % json.dumps(poly, ensure_ascii=False))

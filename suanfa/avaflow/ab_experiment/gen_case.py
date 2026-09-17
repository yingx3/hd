# -*- coding: utf-8 -*-
"""生成一套"拦挡看得出来效果"的对照实验数据：
1) debris 加厚到 15 m（源区形状不变）
2) impact_area 从「整幅 DEM」收窄成「沿最陡下降路径的沟道走廊」（缓冲 300 m）
3) 在源区下游约 1.8 km 处生成一个横跨沟道的拦挡多边形（垂直流向，宽 480 m、厚 90 m）
"""
import json, os
import numpy as np
import rasterio
from rasterio.transform import Affine
from rasterio.warp import transform_geom, transform

SRC_DIR = r'E:\Projects\ZHLXT\交付\案例数据\断链防控\冰川泥石流沿程调控技术'
OUT_DIR = os.path.join(SRC_DIR, r'对照实验')
os.makedirs(OUT_DIR, exist_ok=True)

THICK = 15.0          # 新物源厚度（m）
BUFFER_ITERS = 10     # 走廊缓冲（3x3 膨胀迭代次数）≈ ±300 m
RAISE = 20.0          # 拦挡加高值（m）
DAM_DIST_CELLS = 60   # 拦挡位置：距源区约 60 格 ≈ 1.8 km

with rasterio.open(os.path.join(SRC_DIR, 'elev.tif')) as s:
    elev = s.read(1).astype('float64'); T = s.transform; crs = s.crs
    nodata = s.nodata; profile = s.profile.copy()
    nrows, ncols = elev.shape
with rasterio.open(os.path.join(SRC_DIR, 'debris.tif')) as s:
    debris0 = s.read(1).astype('float64'); nod0 = s.nodata

src = np.isfinite(debris0) & (debris0 != nod0) & (debris0 > 0)
z = np.where(np.isfinite(elev) & (elev != nodata), elev, np.nan)

# ---- 1) 逐源区像元最陡下降，统计沟道路径 ----
visited = np.zeros_like(src, dtype=np.int32)
for r0, c0 in zip(*np.where(src)):
    r, c = int(r0), int(c0)
    for _ in range(4000):
        visited[r, c] += 1
        best, best_z = None, z[r, c]
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                if dr == 0 and dc == 0:
                    continue
                nr, nc = r + dr, c + dc
                if nr < 0 or nc < 0 or nr >= nrows or nc >= ncols:
                    continue
                val = z[nr, nc]
                if np.isfinite(val) and val < best_z - 1e-6:
                    best_z, best = val, (nr, nc)
        if best is None:
            break
        r, c = best

corridor = visited > 0
for _ in range(BUFFER_ITERS):                      # 3x3 膨胀缓冲
    p = np.pad(corridor, 1, mode='constant')
    corridor = (p[:-2, :-2] | p[:-2, 1:-1] | p[:-2, 2:] |
                p[1:-1, :-2] | p[1:-1, 1:-1] | p[1:-1, 2:] |
                p[2:, :-2] | p[2:, 1:-1] | p[2:, 2:])

# ---- 2) 新物源 / 影响范围 ----
debris_new = np.full(elev.shape, -9999.0, dtype='float32')
debris_new[src] = THICK
impact = np.full(elev.shape, -9999.0, dtype='float32')
impact[corridor] = 1.0

for name, arr in (('elev.tif', elev.astype('float32')), ('debris.tif', debris_new), ('impact_area.tif', impact)):
    prof = profile.copy(); prof.update(dtype='float32', nodata=-9999.0, compress='deflate', tiled=False)
    prof.pop('blockxsize', None); prof.pop('blockysize', None)
    with rasterio.open(os.path.join(OUT_DIR, name), 'w', **prof) as d:
        d.write(arr, 1)

# ---- 3) 拦挡多边形：源区中心沿最陡下降路径 60 格处，垂直流向 ----
rr, cc = np.where(src)
r, c = int(round(rr.mean())), int(round(cc.mean()))
path = [(r, c)]
for _ in range(DAM_DIST_CELLS + 12):
    best, best_z = None, z[r, c]
    for dr in (-1, 0, 1):
        for dc in (-1, 0, 1):
            if dr == 0 and dc == 0:
                continue
            nr, nc = r + dr, c + dc
            if 0 <= nr < nrows and 0 <= nc < ncols:
                val = z[nr, nc]
                if np.isfinite(val) and val < best_z - 1e-6:
                    best_z, best = val, (nr, nc)
    if best is None:
        break
    r, c = best
    path.append((r, c))
k = min(DAM_DIST_CELLS, len(path) - 2)
pr, pc = path[k]
nr, nc_ = path[k + 1]
dr, dc = (nr - pr), (nc_ - pc)
norm = max(np.hypot(dr, dc), 1e-6)
ux, uy = dc / norm, -dr / norm          # 垂直于流向（像元坐标：行向下、列向右）
half_w, half_t = 8.0, 1.5               # 半宽 8 格(240m) / 半厚 1.5 格(45m)

def cell_to_lonlat(row, col):
    x, y = T * (col + 0.5, row + 0.5)
    lon, lat = transform(crs, 'EPSG:4326', [x], [y])
    return [round(float(lon[0]), 7), round(float(lat[0]), 7)]

base = np.array([pc + 0.5, pr + 0.5]); dvec = np.array([ux, uy])
poly = []
for sgn_t in (-half_t, half_t):
    for sgn_w in ((-half_w, half_w) if sgn_t < 0 else (half_w, -half_w)):
        pt = base + dvec * sgn_w + np.array([-uy, ux]) * sgn_t
        poly.append(cell_to_lonlat(pt[1] - 0.5, pt[0] - 0.5))

with open(os.path.join(OUT_DIR, 'barrier.json'), 'w', encoding='utf-8') as f:
    json.dump([{'polygon': poly, 'raise': RAISE}], f, ensure_ascii=False, indent=2)

pixel = abs(T.a) * abs(T.e)
print('拦挡位置：距源区 %d 格 ≈ %.2f km，流向 (Δrow=%.0f, Δcol=%.0f)' % (k, k * abs(T.a) / 1000.0, dr, dc))
print('多边形(WGS84)：%s' % json.dumps(poly, ensure_ascii=False))
print('源区: %d 格 = %.4f km2，新厚度 %.1f m，体积 %.3g m3' % (src.sum(), src.sum()*pixel/1e6, THICK, src.sum()*pixel*THICK))
print('影响范围: %d 格 = %.2f km2（原整幅 250x411=%.2f km2）' % (corridor.sum(), corridor.sum()*pixel/1e6, nrows*ncols*pixel/1e6))
print('输出目录:', OUT_DIR)

# -*- coding: utf-8 -*-
"""对照实验比对：无拦挡 vs 有拦挡（读取两次 r.avaflow 输出的 ASC 帧）。"""
import glob, io, json, os
import numpy as np

WSL = r'\\wsl.localhost\Ubuntu-20.04\home\wm'
BASE_PREFIX = 'beta_ab_base'
DAM_PREFIX = 'beta_ab_dam'
SRC = r'E:\Projects\ZHLXT\交付\案例数据\断链防控\冰川泥石流沿程调控技术\对照实验'

def frames(prefix):
    d = os.path.join(WSL, prefix + '_results', prefix + '_ascii')
    fs = sorted(glob.glob(os.path.join(d, '%s_hflow*.asc' % prefix)))
    out = []
    for p in fs:
        with io.open(p, encoding='utf-8-sig', errors='ignore') as f:
            hdr = {}
            for _ in range(6):
                parts = f.readline().split()
                if len(parts) >= 2:
                    hdr[parts[0].lower()] = float(parts[1])
            arr = np.loadtxt(f, dtype='float64')
        out.append((hdr, arr))
    return out

A = frames(BASE_PREFIX)   # 无拦挡
B = frames(DAM_PREFIX)    # 有拦挡
print('帧数: 无拦挡=%d  有拦挡=%d' % (len(A), len(B)))
print('%-6s %-26s %-26s %s' % ('frame', '无拦挡 max/sum/wet', '有拦挡 max/sum/wet', 'max|Δ|'))
for i, ((_, a), (_, b)) in enumerate(zip(A, B), 1):
    d = np.abs(a - b)
    print('%-6d %7.2f / %9.1f / %5d  %7.2f / %9.1f / %5d  %8.3f'
          % (i, a.max(), a.sum(), int((a > 0.05).sum()),
             b.max(), b.sum(), int((b > 0.05).sum()), d.max()))

hdr, a_last = A[-1]
_, b_last = B[-1]
_, a_first = A[0]
cell = hdr['cellsize']
xll = hdr.get('xllcorner', hdr.get('xllcenter') - cell / 2)
yll = hdr.get('yllcorner', hdr.get('yllcenter') - cell / 2)

# 拦挡范围掩膜（用平台同一套逻辑：WGS84 -> 网格投影 + all_touched）
import rasterio
from rasterio.transform import Affine
from rasterio.features import rasterize
from rasterio.warp import transform_geom
barrier = json.load(io.open(os.path.join(SRC, 'barrier.json'), encoding='utf-8-sig'))[0]
T = Affine(cell, 0, xll, 0, -cell, yll + a_last.shape[0] * cell)
geom = transform_geom('EPSG:4326', 'EPSG:32646', {'type': 'Polygon', 'coordinates': [barrier['polygon']]})
mask = rasterize([(geom, 1)], out_shape=a_last.shape, transform=T, fill=0, all_touched=True, dtype='uint8').astype(bool)

print('\n== 拦挡范围内（%d 格，加高 %.0f m）==' % (mask.sum(), barrier['raise']))
print('  无拦挡: 末帧最大流深 %.2f m，出现过流(>0.05m) %d 格' % (a_last[mask].max(), int((a_last[mask] > 0.05).sum())))
print('  有拦挡: 末帧最大流深 %.2f m，出现过流(>0.05m) %d 格' % (b_last[mask].max(), int((b_last[mask] > 0.05).sum())))
print('\n== 全场末帧 ==')
print('  最大流深    : %.2f m  ->  %.2f m' % (a_last.max(), b_last.max()))
print('  过流面积    : %d 格 -> %d 格 (%.0f%%)' % ((a_last > 0.05).sum(), (b_last > 0.05).sum(),
      100.0 * ((b_last > 0.05).sum() - (a_last > 0.05).sum()) / max(1, (a_last > 0.05).sum())))
print('  体积(Σh·A)  : %.3g m3 -> %.3g m3' % (a_last.sum() * cell * cell, b_last.sum() * cell * cell))
d = b_last - a_last
print('  最大增厚 %.2f m（坝前淤积），最大减薄 %.2f m（下游变薄）' % (d.max(), d.min()))

np.save(r'E:\Projects\ZHLXT\backend\hd-mao_0322\target\ab_base_last.npy', a_last)
np.save(r'E:\Projects\ZHLXT\backend\hd-mao_0322\target\ab_dam_last.npy', b_last)
np.save(r'E:\Projects\ZHLXT\backend\hd-mao_0322\target\ab_mask.npy', mask)
np.save(r'E:\Projects\ZHLXT\backend\hd-mao_0322\target\ab_base_all.npy', np.array([a for _, a in A]))
np.save(r'E:\Projects\ZHLXT\backend\hd-mao_0322\target\ab_dam_all.npy', np.array([b for _, b in B]))
print('\n已保存末帧/掩膜/全部帧到 target/*.npy 供画图')

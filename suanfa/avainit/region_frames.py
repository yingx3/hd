# -*- coding: utf-8 -*-
"""生成"冰岩崩启动"动画帧（ESRI ASCII 栅格）。

输入为 EPSG:32646 下的源区多边形环，输出若干帧物源厚度栅格：
  - 启动前：源区多边形内为完整物源厚度；
  - 启动后：源区逐帧"掏空"，同时沿下坡方向长出正在推进的崩落体（lobe）。

帧格式与冰川泥石流动力学模型（DepthAveraged2D）完全一致，前端复用同一套
ASC -> PNG -> Cesium 影像图层渲染链路，因此两种动画的观感与过渡是连续的。

用法：
    python region_frames.py --polygon-json ring.json --out-dir frames \
        --ice-thickness 4 --source-ratio 100 --cellsize 30 --frames 16
"""
import argparse
import json
import math
import os

import numpy as np

MAX_DIM = 480


def load_ring(path):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    ring = np.asarray(data, dtype=np.float64)
    if ring.ndim != 2 or ring.shape[1] != 2 or ring.shape[0] < 3:
        raise ValueError("源区多边形至少需要 3 个顶点")
    return ring


def points_in_polygon(xs, ys, ring):
    """向量化射线法判断网格点是否落在多边形内。"""
    inside = np.zeros(xs.shape, dtype=bool)
    n = len(ring)
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % n]
        cond = (y1 > ys) != (y2 > ys)
        xint = (x2 - x1) * (ys - y1) / ((y2 - y1) if (y2 - y1) != 0 else 1e-12) + x1
        inside ^= cond & (xs < xint)
    return inside


def principal_axis(ring):
    """用 PCA 估计源区长轴方向；返回单位向量，并统一指向南（y 减小）一侧。"""
    centered = ring - ring.mean(axis=0)
    cov = np.cov(centered.T)
    w, v = np.linalg.eigh(cov)
    axis = v[:, int(np.argmax(w))]
    if axis[1] > 0:
        axis = -axis
    norm = np.linalg.norm(axis)
    return axis / (norm if norm > 0 else 1.0)


def build_grid(ring, cellsize, margin):
    minx, miny = ring.min(axis=0)
    maxx, maxy = ring.max(axis=0)
    spanx = max(maxx - minx, cellsize)
    spany = max(maxy - miny, cellsize)
    pad = margin * max(spanx, spany)
    minx -= pad
    miny -= pad
    maxx += pad
    maxy += pad
    while ((maxx - minx) / cellsize > MAX_DIM) or ((maxy - miny) / cellsize > MAX_DIM):
        cellsize *= 1.25
    ncols = int(math.ceil((maxx - minx) / cellsize))
    nrows = int(math.ceil((maxy - miny) / cellsize))
    return minx, miny, cellsize, ncols, nrows


def render_frames(ring, out_dir, h0, cellsize, frame_count, margin, azimuth=None):
    xll, yll, cellsize, ncols, nrows = build_grid(ring, cellsize, margin)
    xs = xll + (np.arange(ncols) + 0.5) * cellsize
    ys = yll + (nrows - np.arange(nrows) - 0.5) * cellsize  # 第一行是北侧
    gx, gy = np.meshgrid(xs, ys)
    mask = points_in_polygon(gx, gy, ring)

    if azimuth is None:
        axis = principal_axis(ring)
    else:
        rad = math.radians(azimuth)
        axis = np.array([math.sin(rad), math.cos(rad)], dtype=np.float64)
    normal = np.array([-axis[1], axis[0]])

    centroid = ring.mean(axis=0)
    rel = np.stack([gx - centroid[0], gy - centroid[1]], axis=-1)
    along = rel @ axis
    lateral = rel @ normal

    inside_along = along[mask]
    if inside_along.size == 0:
        raise ValueError("多边形落在网格之外")
    s_max = float(inside_along.max())
    s_min = float(inside_along.min())
    extent = max(s_max - s_min, cellsize)
    half_width = max(float(np.abs(lateral[mask]).max()), cellsize)
    lobe_len = 1.35 * extent

    os.makedirs(out_dir, exist_ok=True)
    for k in range(frame_count):
        f = k / (frame_count - 1) if frame_count > 1 else 1.0
        h = np.zeros_like(gx, dtype=np.float64)

        # 源区：随启动进程逐步掏空（前 15% 保持满厚度，形成"起势"）
        release = max(0.0, (f - 0.12) / 0.88)
        h[mask] = h0 * max(0.0, 1.0 - release * 0.9)

        # 崩落体：从源区前缘沿下坡方向推进
        adv = lobe_len * f
        if adv > 0:
            front = s_max + adv
            with np.errstate(invalid="ignore", divide="ignore"):
                t = (along - s_max) / max(adv, 1e-6)
            body = (~mask) & (along > s_max) & (along <= front)
            shape_along = np.clip(1.0 - t, 0.0, 1.0) ** 0.55
            width_now = half_width * (0.9 + 0.9 * f)
            shape_lat = np.clip(1.0 - (np.abs(lateral) / width_now) ** 2, 0.0, 1.0)
            h = np.where(body, h0 * shape_along * shape_lat, h)

        path = os.path.join(out_dir, "h_%06d.asc" % k)
        with open(path, "w", encoding="utf-8", newline="\n") as fp:
            fp.write("%-13s%d\n" % ("ncols", ncols))
            fp.write("%-13s%d\n" % ("nrows", nrows))
            fp.write("%-13s%.3f\n" % ("xllcorner", xll))
            fp.write("%-13s%.3f\n" % ("yllcorner", yll))
            fp.write("%-13s%.3f\n" % ("cellsize", cellsize))
            fp.write("%-13s%s\n" % ("NODATA_value", "-9999"))
            for row in range(nrows):
                fp.write(" ".join("%.4f" % v for v in h[row]) + "\n")

    return {
        "ncols": ncols,
        "nrows": nrows,
        "cellsize": cellsize,
        "xllcorner": xll,
        "yllcorner": yll,
        "frames": frame_count,
        "h0": h0,
        "lobeLength": lobe_len,
        "flowAxis": [float(axis[0]), float(axis[1])],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--polygon-json", required=True, help="EPSG:32646 下的多边形环 [[x,y],...]")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--ice-thickness", type=float, default=4.0)
    parser.add_argument("--source-ratio", type=float, default=100.0)
    parser.add_argument("--cellsize", type=float, default=30.0)
    parser.add_argument("--frames", type=int, default=16)
    parser.add_argument("--margin", type=float, default=0.55)
    parser.add_argument("--flow-azimuth", type=float, default=None,
                        help="下坡方位角（自北顺时针，度）；缺省用多边形长轴")
    args = parser.parse_args()

    ring = load_ring(args.polygon_json)
    h0 = max(0.0, args.ice_thickness * args.source_ratio / 100.0)
    info = render_frames(
        ring,
        args.out_dir,
        h0,
        max(5.0, args.cellsize),
        max(2, args.frames),
        max(0.0, args.margin),
        args.flow_azimuth,
    )
    print("RESULT_JSON=" + json.dumps(info, ensure_ascii=False))


if __name__ == "__main__":
    main()

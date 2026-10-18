# -*- coding: utf-8 -*-
"""生成"冰岩崩启动"动画帧（ESRI ASCII 栅格）。

输入为 EPSG:32646 下的源区多边形环，输出若干帧物源厚度栅格：

  - 前段：源区自下游向上游逐步剥离（不是整块变色）；
  - 后段：剥离出的体积沿真实下坡方向堆成向前推进、横向扩展的椭圆崩落体；
  - 每帧按体积守恒反算崩落体厚度，因此前缘会自然变薄并淡出。

帧格式与冰川泥石流动力学模型（DepthAveraged2D）一致，前端复用同一套
ASC -> PNG -> Cesium 影像图层渲染链路。

用法：
    python region_frames.py --polygon-json ring.json --out-dir frames \
        --ice-thickness 4 --source-ratio 100 --cellsize 30 --frames 40 \
        --flow-azimuth 215
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
        crosses = (y1 > ys) != (y2 > ys)
        xint = (x2 - x1) * (ys - y1) / ((y2 - y1) if (y2 - y1) != 0 else 1e-12) + x1
        inside ^= crosses & (xs < xint)
    return inside


def principal_axis(ring):
    """PCA 估计源区长轴；无真实坡向时的回退方案。"""
    centered = ring - ring.mean(axis=0)
    cov = np.cov(centered.T)
    w, v = np.linalg.eigh(cov)
    axis = v[:, int(np.argmax(w))]
    if axis[1] > 0:
        axis = -axis
    norm = np.linalg.norm(axis)
    return axis / (norm if norm > 0 else 1.0)


def build_grid(ring, cellsize, pad):
    minx, miny = ring.min(axis=0)
    maxx, maxy = ring.max(axis=0)
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
    centroid = ring.mean(axis=0)
    span = float(max(np.ptp(ring[:, 0]), np.ptp(ring[:, 1]), cellsize))

    if azimuth is None:
        axis = principal_axis(ring)
    else:
        rad = math.radians(azimuth)
        axis = np.array([math.sin(rad), math.cos(rad)], dtype=np.float64)
    normal = np.array([-axis[1], axis[0]])

    along_ring = (ring - centroid) @ axis
    lateral_ring = (ring - centroid) @ normal
    extent = max(float(along_ring.max() - along_ring.min()), cellsize)
    half_width = max(float(np.abs(lateral_ring).max()), cellsize * 0.5)
    lobe_len = 1.35 * extent

    # 留足下游余量，避免崩落体被栅格边界裁成直边
    pad = max(margin * span, lobe_len * 1.2)
    xll, yll, cellsize, ncols, nrows = build_grid(ring, cellsize, pad)

    xs = xll + (np.arange(ncols) + 0.5) * cellsize
    ys = yll + (nrows - np.arange(nrows) - 0.5) * cellsize  # 第一行在北侧
    gx, gy = np.meshgrid(xs, ys)
    mask = points_in_polygon(gx, gy, ring)

    rel = np.stack([gx - centroid[0], gy - centroid[1]], axis=-1)
    along = rel @ axis
    lateral = rel @ normal

    if not mask.any():
        raise ValueError("多边形落在网格之外")

    s_min = float(along[mask].min())
    s_max = float(along[mask].max())
    s_span = max(s_max - s_min, cellsize)
    cell_area = cellsize * cellsize
    s_norm = np.clip((along - s_min) / s_span, 0.0, 1.0)

    os.makedirs(out_dir, exist_ok=True)
    for k in range(frame_count):
        f = k / (frame_count - 1) if frame_count > 1 else 1.0

        # 源区自下游向上游逐步剥离（过渡带 15% 源区长度）
        release = max(0.0, (f - 0.08) / 0.92)
        front = 1.15 - 1.3 * release
        keep = np.clip((front - s_norm) / 0.15, 0.0, 1.0)

        h = np.zeros_like(gx, dtype=np.float64)
        h[mask] = h0 * keep[mask]

        # 已剥离体积（m^3）
        released = max(0.0, (h0 * float(mask.sum()) - float(h.sum())) * cell_area)

        # 崩落体：沿坡向前进的椭圆体，按体积守恒反算厚度
        adv = lobe_len * f
        if adv > 1e-6 and released > 1e-9:
            center = s_max + 0.55 * adv
            half_len = max(0.5 * adv, 0.6 * cellsize)
            half_wid = max(half_width * (0.6 + 0.8 * f), 0.9 * cellsize)
            r2 = ((along - center) / half_len) ** 2 + (lateral / half_wid) ** 2
            profile = np.clip(1.0 - r2, 0.0, 1.0) ** 0.75
            profile = np.where(mask, 0.0, profile)
            profile_sum = float(profile.sum())
            if profile_sum > 0:
                deposit = released / (profile_sum * cell_area)
                h = np.maximum(h, profile * deposit)

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
        "halfWidth": half_width,
        "flowAxis": [float(axis[0]), float(axis[1])],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--polygon-json", required=True, help="EPSG:32646 下的多边形环 [[x,y],...]")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--ice-thickness", type=float, default=4.0)
    parser.add_argument("--source-ratio", type=float, default=100.0)
    parser.add_argument("--cellsize", type=float, default=30.0)
    parser.add_argument("--frames", type=int, default=40)
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

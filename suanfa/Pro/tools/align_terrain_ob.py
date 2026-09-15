#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
align_terrain_ob.py -- 拦挡/调控后地形对齐工具（Pro 内核输入生成器）
=====================================================================
把「断链防控 / 沿程调控」产生的拦挡后地形（如 Terrain_Ob.txt）对齐到
洪水泥石流启动动力学模型（suanfa/Pro/run_pro.py）的计算网格，
换算成与 zB 相同的基准，并生成一套可直接计算的输入目录。

三种数据来源（三选一）：
  A) Terrain_Ob 有明确地理参考（推荐，最可靠）
        --ob-cellsize 30 --ob-xll 684000 --ob-yll 3339000
  B) 无地理参考，但确认与 zB 覆盖同一范围
        --assume-same-extent
  C) 不使用 Terrain_Ob，直接在计算网格上按手绘多边形加高（等效平台调控）
        --polygon "lon,lat; lon,lat; ..." --raise 20 --utm-zone 47

输出目录内容：
  zB.txt                 灾前地形（原样复制，带 ESRI ASCII 头）
  hW.txt                 初始水深（原样复制）
  zL.txt                 拦挡后地形（计算用输入）
  Terrain_Ob_aligned.txt 对齐并换算基准后的地形（便于核查）
  Terrain_Ob_delta.txt   相对 zB 的增量（拦挡加高范围）
  report.txt             诊断报告（配准质量 / 拟合参数 / 增量统计）

用法示例见同目录 README_align_terrain_ob.md
"""

import argparse
import io
import math
import os
import shutil
import sys

import numpy as np

ASC_HEADER_KEYS = (
    "ncols", "nrows", "xllcorner", "yllcorner",
    "xllcenter", "yllcenter", "cellsize", "nodata_value",
)


def log(msg):
    print(msg, flush=True)


# --------------------------------------------------------------------------- #
# ESRI ASCII 读写
# --------------------------------------------------------------------------- #
def read_ascii(path):
    """读取矩阵文本，返回 (array, header)。header 为空 dict 表示无头部。第 0 行 = 最北。"""
    if not os.path.isfile(path):
        raise FileNotFoundError("文件不存在: %s" % path)
    header = {}
    data_lines = []
    started = False
    with io.open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            if not started:
                s = line.strip()
                if not s:
                    continue
                parts = s.split()
                key = parts[0].lower()
                if key in ASC_HEADER_KEYS and len(parts) > 1:
                    header[key] = float(parts[1])
                    continue
                started = True
            data_lines.append(line)
    if not data_lines:
        raise ValueError("文件没有数据行: %s" % path)
    arr = np.loadtxt(io.StringIO("".join(data_lines)))
    if arr.ndim != 2:
        raise ValueError("输入矩阵必须是二维: %s" % path)
    return arr.astype(np.float64), header


def write_ascii(path, arr, header, fmt="%.7e"):
    """写 ESRI ASCII（带 6 行标准头），第 0 行 = 最北。"""
    nrows, ncols = arr.shape
    hdr = dict(header or {})
    hdr["ncols"] = ncols
    hdr["nrows"] = nrows
    hdr.setdefault("nodata_value", -9999)
    order = ["ncols", "nrows", "xllcorner", "yllcorner", "xllcenter", "yllcenter",
             "cellsize", "nodata_value"]
    keys = [k for k in order if k in hdr]
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        for k in keys:
            v = hdr[k]
            f.write("%-13s %s\n" % (k, ("%d" % int(v)) if k in ("ncols", "nrows") else _num(v)))
        for row in arr:
            f.write(" ".join(fmt % v for v in row) + "\n")


def _num(v):
    f = float(v)
    if f == int(f):
        return str(int(f))
    return ("%.2f" % f).rstrip("0").rstrip(".")


# --------------------------------------------------------------------------- #
# 网格 / 重采样
# --------------------------------------------------------------------------- #
def grid_from_header(header, shape):
    """由 ESRI 头构造网格描述；缺头时报错（对齐目标必须有地理参考）。"""
    nrows, ncols = shape
    if not header:
        raise ValueError("目标网格缺少 ESRI ASCII 头部，无法对齐")
    cs = header.get("cellsize")
    if cs is None:
        raise ValueError("目标网格缺少 cellsize")
    if "xllcorner" in header:
        xll = float(header["xllcorner"])
    elif "xllcenter" in header:
        xll = float(header["xllcenter"]) - cs / 2.0
    else:
        raise ValueError("目标网格缺少 xllcorner / xllcenter")
    if "yllcorner" in header:
        yll = float(header["yllcorner"])
    elif "yllcenter" in header:
        yll = float(header["yllcenter"]) - cs / 2.0
    else:
        raise ValueError("目标网格缺少 yllcorner / yllcenter")
    return {"xll": xll, "yll": yll, "cellsize": float(cs),
            "nrows": int(header.get("nrows", nrows)), "ncols": int(header.get("ncols", ncols))}


def dst_centers(grid):
    """返回目标网格每个像元的中心坐标：xs(1,ncols), ys(nrows,1)（第 0 行最北）。"""
    xs = grid["xll"] + (np.arange(grid["ncols"]) + 0.5) * grid["cellsize"]
    ys = grid["yll"] + (grid["nrows"] - np.arange(grid["nrows"]) - 0.5) * grid["cellsize"]
    return xs, ys


def resample_bilinear(src, src_grid, dst_grid, flip_y=False):
    """把 src（第 0 行最北；flip_y=True 时视为第 0 行最南）重采样到 dst_grid。越界用边缘值。"""
    if flip_y:
        src = src[::-1]
    xs, ys = dst_centers(dst_grid)
    X, Y = np.meshgrid(xs, ys)

    col = (X - src_grid["xll"]) / src_grid["cellsize"] - 0.5
    row = (src_grid["yll"] + src_grid["nrows"] * src_grid["cellsize"] - Y) / src_grid["cellsize"] - 0.5

    col = np.clip(col, 0, src_grid["ncols"] - 1)
    row = np.clip(row, 0, src_grid["nrows"] - 1)

    c0 = np.floor(col).astype(int)
    r0 = np.floor(row).astype(int)
    c1 = np.minimum(c0 + 1, src_grid["ncols"] - 1)
    r1 = np.minimum(r0 + 1, src_grid["nrows"] - 1)
    dc = col - c0
    dr = row - r0

    v00 = src[r0, c0]
    v01 = src[r0, c1]
    v10 = src[r1, c0]
    v11 = src[r1, c1]
    top = v00 * (1 - dc) + v01 * dc
    bot = v10 * (1 - dc) + v11 * dc
    return top * (1 - dr) + bot * dr


def resize_to(src, shape, flip_y=False):
    """无地理参考时按范围等比缩放（假定覆盖同一 bbox）。"""
    if flip_y:
        src = src[::-1]
    nrows, ncols = shape
    ys = np.linspace(0, src.shape[0] - 1, nrows)
    xs = np.linspace(0, src.shape[1] - 1, ncols)
    c0 = np.floor(xs).astype(int)
    r0 = np.floor(ys).astype(int)
    c1 = np.minimum(c0 + 1, src.shape[1] - 1)
    r1 = np.minimum(r0 + 1, src.shape[0] - 1)
    dc = (xs - c0)[None, :]
    dr = (ys - r0)[:, None]
    v00 = src[np.ix_(r0, c0)]
    v01 = src[np.ix_(r0, c1)]
    v10 = src[np.ix_(r1, c0)]
    v11 = src[np.ix_(r1, c1)]
    top = v00 * (1 - dc) + v01 * dc
    bot = v10 * (1 - dc) + v11 * dc
    return top * (1 - dr) + bot * dr


# --------------------------------------------------------------------------- #
# 基准换算
# --------------------------------------------------------------------------- #
def fit_linear(ref, mov):
    """最小二乘拟合 ref ≈ a*mov + b，返回 (a, b, r, r2)。"""
    r_flat = np.asarray(ref).ravel()
    m_flat = np.asarray(mov).ravel()
    ok = np.isfinite(r_flat) & np.isfinite(m_flat)
    if ok.sum() < 16:
        return 1.0, 0.0, float("nan"), float("nan")
    x = m_flat[ok]
    y = r_flat[ok]
    A = np.vstack([x, np.ones_like(x)]).T
    (a, b), *_ = np.linalg.lstsq(A, y, rcond=None)
    pred = a * x + b
    ss_res = float(((y - pred) ** 2).sum())
    ss_tot = float(((y - y.mean()) ** 2).sum()) or 1.0
    r2 = 1.0 - ss_res / ss_tot
    r = float(np.corrcoef(x, y)[0, 1]) if x.std() > 0 and y.std() > 0 else float("nan")
    return float(a), float(b), r, r2


# --------------------------------------------------------------------------- #
# 经纬度 -> UTM（WGS84），避免依赖 pyproj
# --------------------------------------------------------------------------- #
def ll_to_utm(lon, lat, zone):
    a = 6378137.0
    f = 1.0 / 298.257223563
    e2 = f * (2 - f)
    ep2 = e2 / (1 - e2)
    k0 = 0.9996
    lon0 = math.radians(zone * 6 - 183)

    lat_r = math.radians(lat)
    lon_r = math.radians(lon)
    N = a / math.sqrt(1 - e2 * math.sin(lat_r) ** 2)
    T = math.tan(lat_r) ** 2
    C = ep2 * math.cos(lat_r) ** 2
    A = (lon_r - lon0) * math.cos(lat_r)
    M = a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * lat_r
             - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * lat_r)
             + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * lat_r)
             - (35 * e2 ** 3 / 3072) * math.sin(6 * lat_r))
    x = k0 * N * (A + (1 - T + C) * A ** 3 / 6
                  + (5 - 18 * T + T ** 2 + 72 * C - 58 * ep2) * A ** 5 / 120) + 500000.0
    y = k0 * (M + N * math.tan(lat_r) * (A ** 2 / 2 + (5 - T + 9 * C + 4 * C ** 2) * A ** 4 / 24
              + (61 - 58 * T + T ** 2 + 600 * C - 330 * ep2) * A ** 6 / 720))
    if lat < 0:
        y += 10000000.0
    return x, y


# --------------------------------------------------------------------------- #
# 多边形掩膜（射线法，零依赖）
# --------------------------------------------------------------------------- #
def polygon_mask(dst_grid, poly_xy):
    xs, ys = dst_centers(dst_grid)
    X = xs[None, :]
    Y = ys[:, None]
    inside = np.zeros((dst_grid["nrows"], dst_grid["ncols"]), dtype=bool)
    n = len(poly_xy)
    for i in range(n):
        x1, y1 = poly_xy[i]
        x2, y2 = poly_xy[(i + 1) % n]
        if y1 == y2:
            continue
        cond = (y1 > Y) != (y2 > Y)
        xint = (x2 - x1) * (Y - y1) / (y2 - y1) + x1
        inside ^= cond & (X < xint)
    return inside


def parse_polygon(text, utm_zone):
    """解析 "lon,lat; lon,lat; ..."；--polygon-crs ll 时转 UTM。"""
    pts = []
    for chunk in text.replace("\n", ";").split(";"):
        chunk = chunk.strip()
        if not chunk:
            continue
        parts = [p for p in chunk.replace(",", " ").split() if p]
        if len(parts) < 2:
            raise ValueError("多边形顶点格式应为 lon,lat 或 x,y : %r" % chunk)
        pts.append((float(parts[0]), float(parts[1])))
    if len(pts) < 3:
        raise ValueError("多边形至少需要 3 个顶点")
    return pts


# --------------------------------------------------------------------------- #
# 报告
# --------------------------------------------------------------------------- #
def build_report(ctx):
    L = []
    L.append("拦挡后地形对齐报告")
    L.append("=" * 60)
    L.append("生成时间 : %s" % ctx["time"])
    L.append("源目录   : %s" % ctx["src_dir"])
    L.append("输出目录 : %s" % ctx["out_dir"])
    L.append("")
    L.append("[网格]")
    L.append("  计算网格 : %d 行 x %d 列, cellsize=%s" % (ctx["nrows"], ctx["ncols"], ctx["cellsize"]))
    L.append("  左下角   : x=%.2f y=%.2f" % (ctx["xll"], ctx["yll"]))
    L.append("  覆盖范围 : %.0f m x %.0f m" % (ctx["ncols"] * ctx["cellsize"], ctx["nrows"] * ctx["cellsize"]))
    if ctx.get("center_lon") is not None:
        L.append("  网格中心 : lon=%.6f lat=%.6f (WGS84, 按 UTM zone %d 换算)"
                 % (ctx["center_lon"], ctx["center_lat"], ctx["utm_zone"]))
        L.append("  建议 Pro 输入坐标系: EPSG:%d  (run_pro.py --source-crs EPSG:%d)"
                 % (32600 + ctx["utm_zone"], 32600 + ctx["utm_zone"]))
    L.append("")
    L.append("[对齐]")
    L.append("  数据来源 : %s" % ctx["source_desc"])
    if ctx.get("a") is not None:
        L.append("  基准换算 : zB = %.6f * ob + %.3f" % (ctx["a"], ctx["b"]))
        L.append("  相关系数 : r = %.4f   R2 = %.4f" % (ctx["r"], ctx["r2"]))
        if ctx["r"] < 0.90:
            L.append("  !! 警告：r < 0.90，Terrain_Ob 与 zB 形态不一致，")
            L.append("     极可能不是同一区域或存在坐标/镜像错误。")
            L.append("     该文件仅可用于目视排查，不建议直接参与生产计算。")
    if ctx.get("polygon_cells"):
        L.append("  多边形加高: %d 个网格, +%.2f m" % (ctx["polygon_cells"], ctx["raise"]))
    L.append("")
    L.append("[增量统计]  (对齐后地形 - zB)")
    L.append("  最小/最大 : %.3f / %.3f m" % (ctx["dmin"], ctx["dmax"]))
    L.append("  正增量格数: %d (阈值 %.2f m 以上 %d 格)" % (ctx["pos_cells"], ctx["min_raise"], ctx["kept_cells"]))
    L.append("  最大加高  : %.2f m" % ctx["dmax"])
    L.append("  加高体积  : %.1f m3 (按 %s m 网格)" % (ctx["volume"], ctx["cellsize"]))
    L.append("")
    L.append("[输出]")
    for k, v in ctx["outputs"].items():
        L.append("  %-22s %s" % (k, v))
    L.append("")
    if ctx.get("r") is not None and ctx["r"] < 0.90:
        L.append("结论: 配准质量不达标（UNVERIFIED）。请提供 Terrain_Ob 的地理参考")
        L.append("      （xllcorner / yllcorner / cellsize），或改用 --polygon 直接在")
        L.append("      计算网格上生成拦挡地形。")
    else:
        L.append("结论: 已生成可直接用于 run_pro.py 的输入目录。")
    return "\n".join(L)


# --------------------------------------------------------------------------- #
def main(argv=None):
    p = argparse.ArgumentParser(description="把拦挡后地形对齐到 Pro 计算网格")
    p.add_argument("--src-dir", required=True, help="包含 zB.txt/zL.txt/hW.txt 的目录")
    p.add_argument("--out-dir", required=True, help="输出目录（生成可计算输入集）")
    p.add_argument("--zb", default="zB.txt")
    p.add_argument("--zl", default="zL.txt")
    p.add_argument("--hw", default="hW.txt")
    p.add_argument("--ob-file", default="Terrain_Ob.txt")
    p.add_argument("--ob-cellsize", type=float, help="Terrain_Ob 的像元大小(米)")
    p.add_argument("--ob-xll", type=float, help="Terrain_Ob 左下角 x（投影坐标）")
    p.add_argument("--ob-yll", type=float, help="Terrain_Ob 左下角 y（投影坐标）")
    p.add_argument("--ob-transpose", action="store_true",
                   help="Terrain_Ob 是转置存储的（行=东向、列=南向）；yg 系列导出即为此格式")
    p.add_argument("--ob-flip-y", action="store_true", help="Terrain_Ob 第 0 行是最南（需要翻转）")
    p.add_argument("--assume-same-extent", action="store_true",
                   help="假定 Terrain_Ob 与 zB 覆盖同一范围（无地理参考时使用）")
    p.add_argument("--polygon", help='手绘范围 "lon,lat; lon,lat; ..."（与平台一致）')
    p.add_argument("--polygon-crs", choices=["ll", "proj"], default="ll",
                   help="polygon 坐标类型：ll=经纬度(默认)，proj=与 zB 相同的投影坐标")
    p.add_argument("--utm-zone", type=int, default=46,
                   help="经纬度->UTM 的带号；本目录 task1 数据为 46（EPSG:32646），"
                        "若数据实际按 32647 投影则传 47")
    p.add_argument("--raise", dest="raise_m", type=float, default=20.0, help="加高值(米)，仅 --polygon 模式")
    p.add_argument("--mode", choices=["delta", "keep"], default="delta",
                   help="delta=zL+正增量(默认，安全)；keep=直接用对齐后的地形作为 zL")
    p.add_argument("--min-raise", type=float, default=0.5, help="小于该值的增量视为噪声(默认 0.5m)")
    p.add_argument("--allow-low-quality", action="store_true", help="允许低配准质量直接输出（退出码 0）")
    args = p.parse_args(argv)

    import time
    zb, zb_hdr = read_ascii(os.path.join(args.src_dir, args.zb))
    zl, _ = read_ascii(os.path.join(args.src_dir, args.zl))
    hw, _ = read_ascii(os.path.join(args.src_dir, args.hw))
    if not (zb.shape == zl.shape == hw.shape):
        raise ValueError("zB/zL/hW 尺寸不一致: %s / %s / %s" % (zb.shape, zl.shape, hw.shape))

    dst = grid_from_header(zb_hdr, zb.shape)
    out_dir = os.path.abspath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)

    ctx = {
        "time": time.strftime("%Y-%m-%d %H:%M:%S"),
        "src_dir": os.path.abspath(args.src_dir), "out_dir": out_dir,
        "nrows": dst["nrows"], "ncols": dst["ncols"], "cellsize": dst["cellsize"],
        "xll": dst["xll"], "yll": dst["yll"],
        "center_lon": None, "center_lat": None, "utm_zone": args.utm_zone,
        "a": None, "b": None, "r": None, "r2": None,
        "min_raise": args.min_raise, "raise": args.raise_m,
        "polygon_cells": 0, "outputs": {},
    }
    # 网格中心的经纬度（近似，便于用户在地图上画多边形）
    cx = dst["xll"] + dst["ncols"] * dst["cellsize"] / 2.0
    cy = dst["yll"] + dst["nrows"] * dst["cellsize"] / 2.0
    lon0 = args.utm_zone * 6 - 183
    lat_guess = math.degrees(cy / 6378137.0 * (1 - 1 / 298.257223563))
    for _ in range(3):
        lon_g, lat_g = _inverse_utm(cx, cy, args.utm_zone)
        lat_guess = lat_g
    ctx["center_lon"], ctx["center_lat"] = lon_g, lat_g

    if args.polygon:
        poly = parse_polygon(args.polygon, args.utm_zone)
        if args.polygon_crs == "ll":
            poly = [ll_to_utm(x, y, args.utm_zone) for x, y in poly]
        mask = polygon_mask(dst, poly)
        n = int(mask.sum())
        if n == 0:
            raise ValueError("多边形不在计算网格范围内，请检查坐标或 --utm-zone")
        zL_reg = zl.copy()
        zL_reg[mask] = zL_reg[mask] + args.raise_m
        aligned = zL_reg.copy()
        delta = aligned - zb
        ctx["source_desc"] = "手绘多边形 +%.2fm（%d 个网格，%s）" % (
            args.raise_m, n, "经纬度->UTM %d" % args.utm_zone if args.polygon_crs == "ll" else "投影坐标")
        ctx["polygon_cells"] = n
    else:
        ob_path = os.path.join(args.src_dir, args.ob_file)
        ob, _ = read_ascii(ob_path)
        if args.ob_transpose:
            ob = ob.T
            log("[对齐] Terrain_Ob 已按转置解读（行=东向，列=南向）")
        if args.ob_xll is not None and args.ob_yll is not None and args.ob_cellsize:
            src_grid = {"xll": args.ob_xll, "yll": args.ob_yll, "cellsize": args.ob_cellsize,
                        "nrows": ob.shape[0], "ncols": ob.shape[1]}
            aligned = resample_bilinear(ob, src_grid, dst, flip_y=args.ob_flip_y)
            ctx["source_desc"] = ("按地理参考重采样: xll=%.2f yll=%.2f cell=%s m, 源 %d x %d%s"
                                  % (args.ob_xll, args.ob_yll, args.ob_cellsize, ob.shape[1], ob.shape[0],
                                     "（转置）" if args.ob_transpose else ""))
        elif args.assume_same_extent:
            aligned = resize_to(ob, dst_shape(dst), flip_y=args.ob_flip_y)
            ctx["source_desc"] = ("假定与 zB 同范围（等比缩放 %d x %d -> %d x %d，无地理参考）"
                                  % (ob.shape[1], ob.shape[0], dst["ncols"], dst["nrows"]))
        else:
            raise SystemExit(
                "请指定 Terrain_Ob 的地理参考（--ob-cellsize/--ob-xll/--ob-yll）\n"
                "或加 --assume-same-extent 假定与 zB 同范围，\n"
                "或使用 --polygon 直接在计算网格上加高。")

        a, b, r, r2 = fit_linear(zb, aligned)
        ctx.update({"a": a, "b": b, "r": r, "r2": r2})
        log("[对齐] %s" % ctx["source_desc"])
        log("[基准] zB = %.6f * ob + %.3f   r=%.4f  R2=%.4f" % (a, b, r, r2))
        if r < 0.90:
            log("[警告] r=%.4f < 0.90：Terrain_Ob 与 zB 形态不一致，" % r)
            log("        极可能不是同一区域。结果仅用于排查，请勿直接生产使用。")
        aligned = a * aligned + b
        delta = aligned - zb

    if args.mode == "keep" and not args.polygon:
        zL_reg = aligned.copy()
    else:
        pos = np.where(delta >= args.min_raise, delta, 0.0)
        zL_reg = zl + pos

    # ---------------- 输出 ----------------
    outputs = {}
    shutil.copyfile(os.path.join(args.src_dir, args.zb), os.path.join(out_dir, "zB.txt"))
    outputs["zB.txt"] = "灾前地形（原样复制）"
    shutil.copyfile(os.path.join(args.src_dir, args.hw), os.path.join(out_dir, "hW.txt"))
    outputs["hW.txt"] = "初始水深（原样复制）"
    write_ascii(os.path.join(out_dir, "zL.txt"), zL_reg, zb_hdr)
    outputs["zL.txt"] = "拦挡后地形（计算输入，带 6 行 ASCII 头）"
    write_ascii(os.path.join(out_dir, "Terrain_Ob_aligned.txt"), aligned, zb_hdr)
    outputs["Terrain_Ob_aligned.txt"] = "对齐+换算基准后的地形"
    write_ascii(os.path.join(out_dir, "Terrain_Ob_delta.txt"), delta, zb_hdr)
    outputs["Terrain_Ob_delta.txt"] = "相对 zB 的增量"

    pos_cells = int((delta > 0).sum())
    kept_cells = int((delta >= args.min_raise).sum())
    ctx.update({
        "dmin": float(delta.min()), "dmax": float(delta.max()),
        "pos_cells": pos_cells, "kept_cells": kept_cells,
        "volume": float(np.where(delta >= args.min_raise, delta, 0.0).sum() * dst["cellsize"] ** 2),
        "outputs": outputs,
    })
    report = build_report(ctx)
    with io.open(os.path.join(out_dir, "report.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(report)
    log("")
    log(report)

    if ctx["r"] is not None and ctx["r"] < 0.90 and not args.allow_low_quality:
        log("")
        log("退出码 2：配准质量不足。确认无误可加 --allow-low-quality 忽略。")
        return 2
    return 0


def dst_shape(grid):
    return (grid["nrows"], grid["ncols"])


def _inverse_utm(x, y, zone):
    """近似逆投影（用于报告网格中心经纬度，毫秒级精度足够）。"""
    a = 6378137.0
    f = 1.0 / 298.257223563
    e2 = f * (2 - f)
    ep2 = e2 / (1 - e2)
    k0 = 0.9996
    lon0 = math.radians(zone * 6 - 183)
    M = y / k0
    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    mu = M / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu))
    N1 = a / math.sqrt(1 - e2 * math.sin(phi1) ** 2)
    T1 = math.tan(phi1) ** 2
    C1 = ep2 * math.cos(phi1) ** 2
    R1 = a * (1 - e2) / (1 - e2 * math.sin(phi1) ** 2) ** 1.5
    D = (x - 500000.0) / (N1 * k0)
    lat = phi1 - (N1 * math.tan(phi1) / R1) * (
        D ** 2 / 2 - (5 + 3 * T1 + 10 * C1 - 4 * C1 ** 2 - 9 * ep2) * D ** 4 / 24
        + (61 + 90 * T1 + 298 * C1 + 45 * T1 ** 2 - 252 * ep2 - 3 * C1 ** 2) * D ** 6 / 720)
    lon = lon0 + (D - (1 + 2 * T1 + C1) * D ** 3 / 6
                  + (5 - 2 * C1 + 28 * T1 - 3 * C1 ** 2 + 8 * ep2 + 24 * T1 ** 2) * D ** 5 / 120) / math.cos(phi1)
    return math.degrees(lon), math.degrees(lat)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="ignore")
    sys.exit(main())

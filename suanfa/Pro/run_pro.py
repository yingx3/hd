# -*- coding: utf-8 -*-
"""洪水泥石流启动动力学模型（Pro）—— python_port 数值内核包装脚本。

职责：
  1. 读取三幅输入栅格：灾前地形 zb / 灾后地形 zl / 初始水深 hw
     （tif/tiff，或带 ESRI ASCII 头部的 asc/txt；文件名大小写不敏感，
      兼容 zB.txt / zL.txt / hW.txt 这类既有任务数据）；
  2. 统一到同一计算网格：经纬度输入自动重投影到 UTM，保证 ASC 的 cellsize 是米；
  3. 调用 python_port 的数值内核（main.py）求解双层浅水流（泥石流层 + 水层）；
  4. 每个输出时刻直接写出 Cesium / DebrisFlow 可渲染的 ESRI ASCII 帧，
     同时写出进度文件与帧元数据，供后端轮询与前端渲染使用。

由后端 AdminUserController 调用：

  # 探测输入栅格（不计算）；--input-dir 缺省时取 <jobDir>/inputs
  python run_pro.py --probe --job-dir <jobDir> --input-dir <任务数据目录> --source-crs EPSG:32647

  # 正式计算
  python run_pro.py --job-dir <jobDir> --static-dir <nginxHtml> \
      --out-base /ng/pro/<jobId> --bed 0.2 --nn 0.0125 --dx 0 --dy 0 \
      --rous 2700 --rouf 1000 --interval 10 --tmax 100 --max-frames 40 --field total
"""
import argparse
import importlib.util
import io
import json
import math
import os
import sys
import time
import traceback

import numpy as np


def _resolve_proj_data():
    """定位 rasterio 自带的 PROJ 数据目录。

    机器上若安装了 PostGIS/其它 PROJ，环境变量可能指向版本不兼容的 proj.db
    （报 DATABASE.LAYOUT.VERSION 错误），这里强制指回 rasterio 自带数据。
    """
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


_proj_dir = _resolve_proj_data()
if _proj_dir:
    os.environ["PROJ_DATA"] = _proj_dir
    os.environ["PROJ_LIB"] = _proj_dir
_gdal_dir = _resolve_gdal_data()
if _gdal_dir:
    os.environ["GDAL_DATA"] = _gdal_dir

HERE = os.path.dirname(os.path.abspath(__file__))
PORT_DIR = os.path.join(HERE, "python_port")

ASC_HEADER_KEYS = (
    "ncols", "nrows", "xllcorner", "yllcorner",
    "xllcenter", "yllcenter", "cellsize", "nodata_value",
)

# 输入栅格命名：zb(灾前地形) / zl(灾后地形) / hw(初始水深)
INPUT_KEYS = ("zb", "zl", "hw")
INPUT_STEMS = {
    "zb": ("zb", "z_b", "zb_dem", "beforedem", "before", "pre", "elevation", "elev", "dem"),
    "zl": ("zl", "z_l", "zl_dem", "afterdem", "after", "postdem", "post"),
    "hw": ("hw", "h_w", "h0", "waterdepth", "water", "depth"),
}
INPUT_EXTS = (".tif", ".tiff", ".asc", ".txt")
INPUT_EXT_PRIORITY = {".tif": 0, ".tiff": 1, ".asc": 2, ".txt": 3}


def resolve_inputs(input_dir):
    """在目录中定位 zb/zl/hw 三幅输入，返回 {key: 绝对路径}。

    文件名大小写不敏感（兼容 zB.txt / zL.txt / hW.txt 这类既有任务数据），
    扩展名支持 tif/tiff 与带 ESRI ASCII 头部的 asc/txt。
    """
    if not input_dir:
        raise ValueError("未指定输入目录")
    input_dir = os.path.abspath(input_dir)
    if not os.path.isdir(input_dir):
        raise ValueError("输入目录不存在: %s" % input_dir)

    stems = {}
    for name in sorted(os.listdir(input_dir)):
        path = os.path.join(input_dir, name)
        if not os.path.isfile(path):
            continue
        stem, ext = os.path.splitext(name)
        ext = ext.lower()
        if ext not in INPUT_EXTS:
            continue
        stems.setdefault(stem.lower(), []).append((ext, path))

    resolved = {}
    for key in INPUT_KEYS:
        candidates = []
        for alias in INPUT_STEMS[key]:
            if alias in stems:
                candidates = list(stems[alias])
                break
        if not candidates:
            for stem, items in sorted(stems.items()):
                if any(stem == a or stem.startswith(a + "_") or stem.startswith(a + "-")
                       for a in INPUT_STEMS[key]):
                    candidates = list(items)
                    break
        if candidates:
            candidates.sort(key=lambda item: INPUT_EXT_PRIORITY.get(item[0], 9))
            resolved[key] = candidates[0][1]

    missing = [k for k in INPUT_KEYS if k not in resolved]
    if missing:
        raise ValueError("输入目录 %s 缺少 %s 栅格（目录内可用: %s）"
                         % (input_dir, "/".join(missing), ", ".join(sorted(stems)) or "无"))
    return resolved


def _crs_from_name(name):
    """EPSG:xxxx / WKT / proj 串 -> (crs, 规范名称, wkt)；解析失败返回 (None, name, None)。"""
    if not name:
        return None, None, None
    name = str(name).strip()
    try:
        from rasterio.crs import CRS
        crs = CRS.from_user_input(name)
    except Exception:
        return None, name, None
    try:
        code = crs.to_epsg()
    except Exception:
        code = None
    return crs, (("EPSG:%d" % code) if code else name), crs.to_wkt()


def asc_header_georef(header, default_crs=None):
    """从 ESRI ASCII 头部还原 (transform, crs)；信息不足时返回 (None, None)。"""
    if not header:
        return None, None
    cellsize = header.get("cellsize")
    if cellsize is None:
        cellsize = header.get("dx", header.get("dy"))
    try:
        cellsize = float(cellsize)
        nrows = int(header.get("nrows"))
    except (TypeError, ValueError):
        return None, None
    if not cellsize:
        return None, None

    if header.get("xllcorner") is not None:
        xll = float(header["xllcorner"])
    elif header.get("xllcenter") is not None:
        xll = float(header["xllcenter"]) - cellsize / 2.0
    else:
        xll = None
    if header.get("yllcorner") is not None:
        yll = float(header["yllcorner"])
    elif header.get("yllcenter") is not None:
        yll = float(header["yllcenter"]) - cellsize / 2.0
    else:
        yll = None
    if xll is None or yll is None:
        return None, None

    transform = (cellsize, 0.0, xll, 0.0, -cellsize, yll + cellsize * nrows)
    crs, _name, _wkt = _crs_from_name(default_crs)
    return transform, crs

# 渲染场：总流深 / 水层深度 / 泥石流层厚度 / 流速
FIELD_CHOICES = ("total", "water", "solid", "speed")


# --------------------------------------------------------------------------- #
# 基础工具
# --------------------------------------------------------------------------- #
def log(msg):
    print("[pro] %s" % msg, flush=True)


def emit_progress(job_dir, **kwargs):
    """把运行进度写到 <jobDir>/progress.json，后端轮询该文件向前端反馈。"""
    if not job_dir:
        return
    payload = dict(kwargs)
    payload["updatedAt"] = int(time.time() * 1000)
    try:
        tmp = os.path.join(job_dir, "progress.json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        os.replace(tmp, os.path.join(job_dir, "progress.json"))
    except Exception as exc:  # 进度写失败不影响计算
        log("进度写入失败: %s" % exc)


def dump_json(path, payload):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def utm_epsg(lon, lat):
    zone = int(math.floor((float(lon) + 180.0) / 6.0)) + 1
    zone = min(max(zone, 1), 60)
    return (32600 if float(lat) >= 0 else 32700) + zone


def epsg_name(crs):
    """把 rasterio CRS 转成后端 proj4j 能识别的名称（优先 EPSG:xxxx）。"""
    if crs is None:
        return None, None
    try:
        code = crs.to_epsg()
    except Exception:
        code = None
    return (("EPSG:%d" % code) if code else None), crs.to_wkt()


# --------------------------------------------------------------------------- #
# 读取输入（TIFF；同时兼容带 ESRI 头部的 ASCII 文本）
# --------------------------------------------------------------------------- #
def read_matrix_file(path):
    """读取纯数值矩阵文本；若带 ESRI ASCII 头部则一并返回网格信息。"""
    header = {}
    data_lines = []
    started = False
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
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
    arr = np.loadtxt(io.StringIO("".join(data_lines)))
    if arr.ndim != 2:
        raise ValueError("输入矩阵必须是二维: %s" % path)
    return arr.astype(np.float64), (header or None)


def _open_raster(path):
    import rasterio  # 延迟导入：避免影响 python_port 内的模块名解析
    return rasterio.open(path)


def read_raster(path, default_crs=None):
    """读取单波段栅格，返回 (数组, 元信息)。nodata -> nan。

    tif/tiff 直接读地理参考；asc/txt 尝试解析 ESRI ASCII 头部，
    头部没有坐标系时用 default_crs（--source-crs）解释其平面坐标。
    """
    if not os.path.isfile(path):
        raise FileNotFoundError("输入栅格不存在: %s" % path)
    ext = os.path.splitext(path)[1].lower()
    if ext not in (".tif", ".tiff"):
        arr, header = read_matrix_file(path)
        nodata = None
        if header and header.get("nodata_value") is not None:
            nodata = float(header["nodata_value"])
            bad = int(np.count_nonzero(arr == nodata))
            if bad:
                arr = np.where(arr == nodata, np.nan, arr)
                log("%s 中 %d 个 NODATA 值按无效处理" % (os.path.basename(path), bad))
        transform, crs = asc_header_georef(header, default_crs)
        crs_name, crs_wkt = epsg_name(crs)
        if crs_name is None and default_crs:
            crs_name = str(default_crs)
        bounds = None
        res = None
        if transform is not None:
            res = (abs(transform[0]), abs(transform[4]))
            left, top = transform[2], transform[5]
            bounds = (left, top + transform[4] * arr.shape[0],
                      left + transform[0] * arr.shape[1], top)
        return arr, {
            "ncols": arr.shape[1], "nrows": arr.shape[0],
            "crs": crs, "crsName": crs_name, "crsWkt": crs_wkt,
            "epsg": (crs.to_epsg() if crs is not None else None),
            "transform": transform, "res": res, "bounds": bounds,
            "nodata": nodata, "header": header,
        }

    with _open_raster(path) as ds:
        band = ds.read(1, masked=True).astype(np.float64)
        arr = np.asarray(band.filled(np.nan), dtype=np.float64)
        crs_name, crs_wkt = epsg_name(ds.crs)
        profile = {
            "ncols": ds.width,
            "nrows": ds.height,
            "crs": ds.crs,
            "crsName": crs_name,
            "crsWkt": crs_wkt,
            "epsg": (ds.crs.to_epsg() if ds.crs is not None else None),
            "transform": tuple(ds.transform)[:6],
            "res": (abs(ds.transform.a), abs(ds.transform.e)),
            "nodata": ds.nodata,
            "bounds": tuple(ds.bounds),
        }
    return arr, profile


def prepare_work_grid(inputs, target_crs_override=None, max_cells=1_000_000,
                      resample_method="bilinear", default_crs=None):
    """把三幅输入栅格统一到同一个（米制）计算网格。

    返回 (arrays, grid)，grid 含 transform/ncols/nrows/crs/dx/dy。
    default_crs 用于给「带 ASCII 头部但无坐标系」的 asc/txt 输入指定坐标系。
    """
    from rasterio.warp import calculate_default_transform, reproject, Resampling
    import rasterio.transform as rio_transform
    from rasterio.crs import CRS

    arr_b, info_b = read_raster(inputs["zb"], default_crs)
    arr_l, info_l = read_raster(inputs["zl"], default_crs)
    arr_w, info_w = read_raster(inputs["hw"], default_crs)

    src_crs = info_b["crs"]
    src_transform = rio_transform.Affine(*info_b["transform"]) if info_b["transform"] else None

    target_crs = None
    reproject_needed = False
    if src_transform is not None and src_crs is not None:
        if target_crs_override:
            target_crs = CRS.from_user_input(target_crs_override)
        elif src_crs.is_geographic:
            # 经纬度网格：重投影到 UTM，保证 ASC 以米为单元格尺寸
            lon = (info_b["bounds"][0] + info_b["bounds"][2]) / 2.0
            lat = (info_b["bounds"][1] + info_b["bounds"][3]) / 2.0
            target_crs = CRS.from_epsg(utm_epsg(lon, lat))
        else:
            target_crs = src_crs
        reproject_needed = (target_crs != src_crs)
    elif src_transform is not None and target_crs_override:
        # 只有平面坐标、无法重投影：沿用源网格，仅按指定坐标系解释（不插值）
        target_crs = CRS.from_user_input(target_crs_override)

    if src_transform is None:
        # 无地理参考：直接按矩阵下标计算，要求三幅栅格形状一致
        if not (arr_b.shape == arr_l.shape == arr_w.shape):
            raise ValueError("输入栅格缺少地理参考且尺寸不一致，无法对齐: %s" % (arr_b.shape,))
        dx, dy = 20.0, 20.0
        grid = {
            "ncols": arr_b.shape[1], "nrows": arr_b.shape[0],
            "transform": None, "crs": None, "crsName": None, "crsWkt": None,
            "dx": dx, "dy": dy, "xll": 0.0, "yll": 0.0, "resampled": False,
        }
        return {"zb": arr_b, "zl": arr_l, "hw": arr_w}, grid

    if reproject_needed:
        dst_transform, dst_w, dst_h = calculate_default_transform(
            src_crs, target_crs, info_b["ncols"], info_b["nrows"], *info_b["bounds"])
    else:
        dst_transform, dst_w, dst_h = src_transform, info_b["ncols"], info_b["nrows"]

    # 限制网格规模，避免超大栅格把内存/时间打爆
    if dst_w * dst_h > max_cells:
        scale = math.sqrt(float(dst_w * dst_h) / float(max_cells))
        dst_transform = rasterio.transform.Affine(
            dst_transform.a * scale, dst_transform.b, dst_transform.c,
            dst_transform.d, dst_transform.e * scale, dst_transform.f)
        dst_w = max(2, int(dst_w / scale))
        dst_h = max(2, int(dst_h / scale))
        log("输入网格过大，按 %.2f 倍降采样到 %dx%d" % (scale, dst_w, dst_h))

    method = Resampling.bilinear
    if resample_method == "nearest":
        method = Resampling.nearest

    def to_target(arr, info, fill=0.0):
        if info["transform"] is None:
            return resample_by_index(arr, dst_h, dst_w)
        if not reproject_needed:
            if arr.shape == (dst_h, dst_w):
                return np.array(arr, dtype=np.float64)
            return resample_by_index(arr, dst_h, dst_w)
        dst = np.full((dst_h, dst_w), fill, dtype=np.float64)
        src = np.array(arr, dtype=np.float64)
        sentinel = -9999.0
        src = np.where(np.isnan(src), sentinel, src)
        reproject(
            source=src, destination=dst,
            src_transform=rasterio.transform.Affine(*info["transform"]),
            src_crs=src_crs, dst_transform=dst_transform, dst_crs=target_crs,
            src_nodata=sentinel, dst_nodata=sentinel,
            resampling=method)
        dst[dst == sentinel] = fill
        return dst

    grid_arrays = {
        "zb": to_target(arr_b, info_b, 0.0),
        "zl": to_target(arr_l, info_l, 0.0),
        "hw": to_target(arr_w, info_w, 0.0),
    }

    crs_name, crs_wkt = epsg_name(target_crs)
    if crs_name is None:
        crs_name, crs_wkt = info_b.get("crsName"), info_b.get("crsWkt")
    dx = abs(dst_transform.a)
    dy = abs(dst_transform.e)
    grid = {
        "ncols": int(dst_w), "nrows": int(dst_h),
        "transform": tuple(dst_transform)[:6],
        "crs": target_crs, "crsName": crs_name, "crsWkt": crs_wkt,
        "dx": float(dx), "dy": float(dy),
        "xll": float(dst_transform.c), "yll": float(dst_transform.f + dst_transform.e * dst_h),
        "resampled": bool(reproject_needed),
    }
    return grid_arrays, grid


def resample_by_index(arr, nrows, ncols):
    """无地理参考时的最近邻重采样（仅用于形状不一致的兜底）。"""
    src_r, src_c = arr.shape
    if (src_r, src_c) == (nrows, ncols):
        return np.array(arr, dtype=np.float64)
    ridx = np.clip((np.arange(nrows) * (src_r / float(nrows))).astype(int), 0, src_r - 1)
    cidx = np.clip((np.arange(ncols) * (src_c / float(ncols))).astype(int), 0, src_c - 1)
    return np.asarray(arr[np.ix_(ridx, cidx)], dtype=np.float64)


# --------------------------------------------------------------------------- #
# python_port 数值内核加载与运行
# --------------------------------------------------------------------------- #
def load_port_modules():
    """按依赖顺序加载 python_port 模块。

    注意：不能把 python_port 目录加进 sys.path —— 那里有 time.py / process.py，
    会遮蔽标准库同名模块（例如 time）。这里用文件路径显式加载并注册模块名。
    """
    def load(name, filename):
        path = os.path.join(PORT_DIR, filename)
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
        return module

    load("global_state", "global_state.py")
    load("doput", "doput.py")
    load("process", "process.py")
    load("solver", "solver.py")
    return load("port_main", "main.py")


def field_of(Uw, Us, field):
    hs = np.nan_to_num(Us[:, :, 0], nan=0.0, posinf=0.0, neginf=0.0)
    hw = np.nan_to_num(Uw[:, :, 0], nan=0.0, posinf=0.0, neginf=0.0)
    if field == "water":
        out = hw
    elif field == "solid":
        out = hs
    elif field == "speed":
        with np.errstate(divide="ignore", invalid="ignore"):
            us = np.nan_to_num(Us[:, :, 1] / np.where(hs > 0, hs, np.nan), nan=0.0)
            vs = np.nan_to_num(Us[:, :, 2] / np.where(hs > 0, hs, np.nan), nan=0.0)
            uw = np.nan_to_num(Uw[:, :, 1] / np.where(hw > 0, hw, np.nan), nan=0.0)
            vw = np.nan_to_num(Uw[:, :, 2] / np.where(hw > 0, hw, np.nan), nan=0.0)
        out = np.maximum(np.sqrt(us ** 2 + vs ** 2), np.sqrt(uw ** 2 + vw ** 2))
    else:
        out = hs + hw  # 总流深（泥石流层 + 水层）
    out = np.where(np.isfinite(out), out, 0.0)
    return np.maximum(out, 0.0)


def write_asc(path, matrix, grid):
    """写 ESRI ASCII 栅格（行 0 = 北侧，与前端 parseASC / DebrisFlow 一致）。"""
    header = (
        "ncols %d\nnrows %d\nxllcorner %.6f\nyllcorner %.6f\n"
        "cellsize %.10g\nNODATA_value %d\n"
        % (grid["ncols"], grid["nrows"], grid["xll"], grid["yll"],
           grid["dx"], -9999)
    )
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header)
        np.savetxt(f, matrix, fmt="%.3f", delimiter=" ")


def run_simulation(job_dir, args):
    """执行算法并把每个输出时刻写成 ASC 帧。"""
    input_dir = os.path.abspath(args.input_dir) if args.input_dir else os.path.join(job_dir, "inputs")
    inputs = resolve_inputs(input_dir)
    log("输入目录 %s -> %s"
        % (input_dir, ", ".join("%s=%s" % (k, os.path.basename(inputs[k])) for k in INPUT_KEYS)))
    arrays, grid = prepare_work_grid(
        inputs, target_crs_override=args.target_crs, default_crs=args.source_crs)

    # python_port 的 main() 从 basePath 下按 txt 读取输入
    work_dir = os.path.join(job_dir, "work")
    os.makedirs(work_dir, exist_ok=True)
    for key, name in (("zb", "zb"), ("zl", "zl"), ("hw", "hW")):
        np.savetxt(os.path.join(work_dir, name + ".txt"), arrays[key], fmt="%.6g")

    dx = float(args.dx) if float(args.dx) > 0 else grid["dx"]
    dy = float(args.dy) if float(args.dy) > 0 else grid["dy"]
    interval = float(args.interval) if float(args.interval) > 0 else 1.0
    tmax = float(args.tmax) if float(args.tmax) > 0 else 100.0
    params = [float(args.bed), float(args.nn), dx, dy,
              float(args.rous), float(args.rouf), interval, tmax]
    with open(os.path.join(work_dir, "p.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(" ".join("%.6g" % v for v in params) + "\n")

    log("计算网格 %dx%d, dx=%.3fm dy=%.3fm, 时长=%.1fs, 输出间隔=%.3fs, CRS=%s"
        % (grid["ncols"], grid["nrows"], dx, dy, tmax, interval, grid["crsName"]))

    frames_dir = os.path.join(args.static_dir, args.out_subdir, os.path.basename(job_dir.rstrip("/\\")), "frames")
    if args.frames_dir:
        frames_dir = args.frames_dir
    os.makedirs(frames_dir, exist_ok=True)
    # 清理上次运行残留的帧
    for name in os.listdir(frames_dir):
        if name.startswith(args.prefix + "_hflow") or name == "frames_meta.json":
            try:
                os.remove(os.path.join(frames_dir, name))
            except OSError:
                pass

    expected_calls = max(1, int(math.ceil(tmax / max(interval, 1e-6))) + 1)
    stride = max(1, int(math.ceil(expected_calls / max(1, int(args.max_frames)))))

    state = {"calls": 0, "written": 0, "global_max": 0.0, "last_field": None, "t0": time.time()}

    def write_frame(matrix):
        state["written"] += 1
        path = os.path.join(frames_dir, "%s_hflow%04d.asc" % (args.prefix, state["written"]))
        write_asc(path, matrix, grid)
        state["global_max"] = max(state["global_max"], float(np.max(matrix)) if matrix.size else 0.0)
        return path

    def output_fn(Uw, Us):
        state["calls"] += 1
        matrix = field_of(Uw, Us, args.field)
        state["last_field"] = matrix
        if stride > 1 and (state["calls"] - 1) % stride != 0:
            return
        if state["written"] >= int(args.max_frames):
            return
        write_frame(matrix)
        percent = min(96.0, 100.0 * state["calls"] / float(expected_calls))
        emit_progress(job_dir, stage="simulation", percent=round(percent, 1),
                      frame=state["written"],
                      elapsedSeconds=round(time.time() - state["t0"], 1),
                      message="已输出 %d 帧" % state["written"])

    port_main = load_port_modules()
    log("调用 python_port 数值内核 ...")
    emit_progress(job_dir, stage="simulation", percent=1.0, frame=0,
                  elapsedSeconds=0.0, message="数值计算中")
    port_main.main(work_dir, ".", "zb", "zl", "hW", "p", output_fn=output_fn)

    # 保证动画收尾在最终状态
    if state["last_field"] is not None and state["written"] > 0:
        write_asc(os.path.join(frames_dir, "%s_hflow%04d.asc" % (args.prefix, state["written"])),
                  state["last_field"], grid)
        state["global_max"] = max(state["global_max"], float(np.max(state["last_field"])))

    if state["written"] == 0:
        raise RuntimeError("算法未产生任何输出帧，请检查输入数据")

    meta = {
        "model": "pro",
        "field": args.field,
        "prefix": args.prefix,
        "ncols": grid["ncols"],
        "nrows": grid["nrows"],
        "cellsize": grid["dx"],
        "dlat": grid["dy"],
        "xllcorner": grid["xll"],
        "yllcorner": grid["yll"],
        "sourceCrs": grid["crsName"],
        "sourceCrsWkt": grid["crsWkt"],
        "globalMax": state["global_max"],
        "frameCount": state["written"],
        "dx": dx, "dy": dy,
        "interval": interval, "tmax": tmax,
        "params": params,
        "framesDir": frames_dir,
        "outBase": args.out_base,
    }
    dump_json(os.path.join(frames_dir, "frames_meta.json"), meta)
    log("计算完成，共 %d 帧，最大 %s = %.4f" % (state["written"], args.field, state["global_max"]))
    return meta


# --------------------------------------------------------------------------- #
# 输入探测（上传后立刻给出网格 / 坐标系摘要）
# --------------------------------------------------------------------------- #
def probe_inputs(job_dir, input_dir="", source_crs="", max_cells=1_000_000):
    target_dir = input_dir or os.path.join(job_dir, "inputs")
    inputs = resolve_inputs(target_dir)
    arrays, grid = prepare_work_grid(inputs, max_cells=max_cells, default_crs=source_crs)
    info = {}
    for key in ("zb", "zl", "hw"):
        arr = arrays[key]
        info[key] = {
            "shape": [int(arr.shape[0]), int(arr.shape[1])],
            "min": round(float(np.nanmin(arr)), 4) if arr.size else 0.0,
            "max": round(float(np.nanmax(arr)), 4) if arr.size else 0.0,
        }
    bbox = None
    try:
        from rasterio.warp import transform_bounds
        if grid["crs"] is not None and grid["transform"] is not None:
            import rasterio.transform as rio_transform
            left = grid["xll"]
            top = grid["yll"] + grid["dy"] * grid["nrows"]
            right = left + grid["dx"] * grid["ncols"]
            bottom = grid["yll"]
            bbox = [round(float(v), 6) for v in transform_bounds(
                grid["crs"], "EPSG:4326", left, bottom, right, top)]
    except Exception as exc:
        log("bbox 计算失败: %s" % exc)

    thickness = float(np.nanmax(np.maximum(arrays["zb"] - arrays["zl"], 0.0))) if arrays["zb"].size else 0.0
    water = float(np.nanmax(arrays["hw"])) if arrays["hw"].size else 0.0
    return {
        "status": "ok",
        "inputDir": os.path.abspath(target_dir),
        "files": {k: os.path.basename(inputs[k]) for k in INPUT_KEYS},
        "ncols": grid["ncols"],
        "nrows": grid["nrows"],
        "dx": round(grid["dx"], 6),
        "dy": round(grid["dy"], 6),
        "crs": grid["crsName"],
        "resampled": grid["resampled"],
        "bbox": bbox,
        "maxThickness": round(thickness, 4),
        "maxWaterDepth": round(water, 4),
        "inputs": info,
    }


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def parse_args(argv):
    parser = argparse.ArgumentParser(description="洪水泥石流启动动力学模型(python_port)")
    parser.add_argument("--job-dir", required=True)
    parser.add_argument("--probe", action="store_true")
    parser.add_argument("--input-dir", default="", help="输入数据目录（默认 <job-dir>/inputs）")
    parser.add_argument("--source-crs", default="", help="asc/txt 输入的坐标系，如 EPSG:32647")
    parser.add_argument("--static-dir", default="")
    parser.add_argument("--out-subdir", default="pro")
    parser.add_argument("--out-base", default="")
    parser.add_argument("--frames-dir", default="")
    parser.add_argument("--prefix", default="pro")
    parser.add_argument("--bed", default=0.2)
    parser.add_argument("--nn", default=0.0125)
    parser.add_argument("--dx", default=0)
    parser.add_argument("--dy", default=0)
    parser.add_argument("--rous", default=2700)
    parser.add_argument("--rouf", default=1000)
    parser.add_argument("--interval", default=10)
    parser.add_argument("--tmax", default=100)
    parser.add_argument("--max-frames", default=40)
    parser.add_argument("--field", default="total", choices=list(FIELD_CHOICES))
    parser.add_argument("--target-crs", default="")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv if argv is not None else sys.argv[1:])
    job_dir = os.path.abspath(args.job_dir)
    if not os.path.isdir(job_dir):
        print("PRO_RESULT_JSON=" + json.dumps({"status": "error", "message": "任务目录不存在: %s" % job_dir}, ensure_ascii=False))
        return 1

    if args.probe:
        try:
            result = probe_inputs(job_dir, args.input_dir, args.source_crs)
            print("PROBE_JSON=" + json.dumps(result, ensure_ascii=False))
            return 0
        except Exception as exc:
            traceback.print_exc()
            print("PROBE_JSON=" + json.dumps({"status": "error", "message": str(exc)}, ensure_ascii=False))
            return 1

    if not args.static_dir:
        print("PRO_RESULT_JSON=" + json.dumps({"status": "error", "message": "缺少 --static-dir"}, ensure_ascii=False))
        return 1

    try:
        meta = run_simulation(job_dir, args)
        emit_progress(job_dir, stage="done", percent=100.0, frame=meta["frameCount"],
                      message="完成，共 %d 帧" % meta["frameCount"])
        print("PRO_RESULT_JSON=" + json.dumps({"status": "ok", "meta": meta}, ensure_ascii=False))
        return 0
    except Exception as exc:
        traceback.print_exc()
        emit_progress(job_dir, stage="error", percent=0.0, message=str(exc))
        print("PRO_RESULT_JSON=" + json.dumps({"status": "error", "message": str(exc)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
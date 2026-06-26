"""TRIGRS 模型 + 含冰边坡 + 度日因子融化模型 — Python 优化版

对应 MATLAB: TRIGRS.m (含冰版本)

加速策略:
  - D8 径流路由用 numba JIT 编译（10-50x 加速）
  - 像元按高程预排序一次，消除每时段 O(n·m) 搜索
  - 深度层最小 FS 搜索向量化
"""

import os
import numpy as np
from pathlib import Path

# ---- erfc ----------------------------------------------------------------
try:
    from scipy.special import erfc as _erfc
except ImportError:
    def _erfc(x):
        p = np.array([0.3275911, 0.254829592, -0.284496736, 1.421413741,
                      -1.453152027, 1.061405429])
        t = 1.0 / (1.0 + p[0] * np.abs(x))
        poly = ((((p[5] * t + p[4]) * t + p[3]) * t + p[2]) * t + p[1]) * t
        erfc_x = poly * np.exp(-x * x)
        return np.where(x >= 0, erfc_x, 2.0 - erfc_x)

# ---- numba JIT -----------------------------------------------------------
try:
    from numba import njit
    _numba_ok = True
except ImportError:
    def njit(*args, **kwargs):
        return lambda f: f
    _numba_ok = False


# D8 流向 → (dr, dc) 偏移表，key=GDAL D8 code
D8_OFFSET = np.array([
    [0, 0],    # 0  - invalid
    [0, 1],    # 1  - E
    [1, 1],    # 2  - SE
    [0, 0],    # 3  - invalid
    [1, 0],    # 4  - S
    [0, 0],    # 5  -
    [0, 0],    # 6  -
    [0, 0],    # 7  -
    [1, -1],   # 8  - SW
    [0, 0],    # 9  -
    [0, 0],    # 10 -
    [0, 0],    # 11 -
    [0, 0],    # 12 -
    [0, 0],    # 13 -
    [0, 0],    # 14 -
    [0, 0],    # 15 -
    [0, -1],   # 16 - W
    [0, 0],    # 17 -
    [0, 0],    # .. (skip to 32)
], dtype=np.int32)
# 补充: 32=NW, 64=N, 128=NE
_D8_MAP = {1: (0, 1), 2: (1, 1), 4: (1, 0), 8: (1, -1),
           16: (0, -1), 32: (-1, -1), 64: (-1, 0), 128: (-1, 1)}


@njit(cache=True)
def _d8_route_period(Inz1_padded, Ks_depth, flowdir,
                     row_indices, col_indices):
    """D8 径流分配 — 单时段，numba JIT。

    按 row_indices/col_indices 顺序（必须是高程降序）处理每个像元。
    超渗部分沿 D8 流向分配给下游邻元。

    Parameters
    ----------
    Inz1_padded : ndarray (rows+2, cols+2)
        有效雨强（含 pad），原地修改。
    Ks_depth : ndarray (rows, cols)
        饱和导水率折算水深 (m/时段)。
    flowdir : ndarray (rows, cols)
        D8 流向编码。
    row_indices, col_indices : ndarray (n,)
        像元行列号（0-based，不含 pad），按高程降序排列。
    """
    for p in range(len(row_indices)):
        r0 = row_indices[p]
        c0 = col_indices[p]

        row = r0 + 1  # pad 偏移
        col = c0 + 1

        if Inz1_padded[row, col] < Ks_depth[r0, c0]:
            continue

        excess = Inz1_padded[row, col] - Ks_depth[r0, c0]
        Inz1_padded[row, col] = Ks_depth[r0, c0]
        fd = int(flowdir[r0, c0])

        # D8 路由
        if fd == 1:
            Inz1_padded[row, col + 1] += excess
        elif fd == 2:
            Inz1_padded[row + 1, col + 1] += excess
        elif fd == 4:
            Inz1_padded[row + 1, col] += excess
        elif fd == 8:
            Inz1_padded[row + 1, col - 1] += excess
        elif fd == 16:
            Inz1_padded[row, col - 1] += excess
        elif fd == 32:
            Inz1_padded[row - 1, col - 1] += excess
        elif fd == 64:
            Inz1_padded[row - 1, col] += excess
        elif fd == 128:
            Inz1_padded[row - 1, col + 1] += excess


# ---- TIFF I/O ------------------------------------------------------------
def _fix_nodata(arr):
    """将极端 NoData 值转为 NaN（兜底，等同 MATLAB GRIDobj 行为）。

    常见 GIS NoData: -3.4e+38 (float32 min), -9999, -32768 等。
    正常物理量不会 < -1e20。
    """
    arr[arr < -1e20] = np.nan
    return arr


def _read_tif(path, dtype=np.float64):
    try:
        import rasterio
        with rasterio.open(path) as src:
            arr = src.read(1).astype(dtype)
            # rasterio 路径也做 NoData→NaN
            if src.nodata is not None:
                arr[arr == src.nodata] = np.nan
            _fix_nodata(arr)
            return arr, src.transform, src.crs, src.width, src.height
    except (ImportError, Exception):
        pass
    try:
        from osgeo import gdal, osr
        ds = gdal.Open(str(path))
        if ds is None:
            raise IOError(f"无法打开: {path}")
        arr = ds.ReadAsArray().astype(dtype)
        band = ds.GetRasterBand(1)
        ndv = band.GetNoDataValue()
        if ndv is not None:
            arr[arr == ndv] = np.nan
        _fix_nodata(arr)
        gt = ds.GetGeoTransform()
        w, h = ds.RasterXSize, ds.RasterYSize
        prj = ds.GetProjection()
        crs = None
        if prj:
            srs = osr.SpatialReference()
            srs.ImportFromWkt(prj)
            crs = srs.GetAuthorityCode(None)
            if crs:
                crs = f"EPSG:{crs}"
        ds = None
        return arr, gt, crs, w, h
    except ImportError:
        pass
    try:
        import tifffile
        arr = tifffile.imread(str(path)).astype(dtype)
        _fix_nodata(arr)
        return arr, None, None, arr.shape[1], arr.shape[0]
    except ImportError:
        raise ImportError("需要 rasterio, GDAL 或 tifffile 来读取 GeoTIFF")


def _write_tif(path, arr, transform, crs, height, width):
    try:
        import rasterio
        with rasterio.open(str(path), 'w', driver='GTiff',
                           height=height, width=width, count=1,
                           dtype=arr.dtype, crs=crs, transform=transform) as dst:
            dst.write(arr, 1)
        return
    except (ImportError, Exception):
        pass
    try:
        from osgeo import gdal, osr
        if os.path.exists(str(path)):
            try:
                os.remove(str(path))
            except OSError:
                path = str(path).replace('.tif', f'_{os.getpid()}.tif')
        driver = gdal.GetDriverByName('GTiff')
        ds = driver.Create(str(path), width, height, 1, gdal.GDT_Float64)
        if transform is not None:
            ds.SetGeoTransform(transform)
        if crs is not None:
            srs = osr.SpatialReference()
            srs.SetFromUserInput(str(crs))
            ds.SetProjection(srs.ExportToWkt())
        band = ds.GetRasterBand(1)
        band.SetNoDataValue(-9999.0)
        band.WriteArray(np.where(np.isnan(arr), -9999.0, arr))
        ds.FlushCache()
        ds = None
        return
    except ImportError:
        pass
    np.save(str(path).replace('.tif', '.npy'), arr)


# ---- 核心函数 ------------------------------------------------------------
def _ierfc(x):
    return (1.0 / np.sqrt(np.pi)) * np.exp(-x ** 2) - x * _erfc(x)


def run_trigrs(time_nodes, rain_path, temp_3d, t,
               dem_arr, slope_arr, flowdirection_arr,
               zmax_arr, depthwt_arr, Ys_arr, Yw_arr,
               c_arr, f_arr, Ks_arr, Izlt_arr, D0_arr,
               ice_content=0.2):
    w1, w2 = dem_arr.shape
    n_cells = w1 * w2

    # ---- 1. 预处理 ----
    Dem     = dem_arr.astype(np.float64, copy=False)
    flowdir = flowdirection_arr.astype(np.float64, copy=False)
    zmax    = zmax_arr.astype(np.float64, copy=False)
    depthwt = depthwt_arr.astype(np.float64, copy=False)
    Ys      = Ys_arr.astype(np.float64, copy=False)
    Yw      = Yw_arr.astype(np.float64, copy=False)
    c       = c_arr.astype(np.float64, copy=False)
    f_ang   = f_arr.astype(np.float64, copy=False)
    Ks_mat  = Ks_arr.astype(np.float64, copy=False)
    Izlt    = Izlt_arr.astype(np.float64, copy=False)
    D0      = D0_arr.astype(np.float64, copy=False)

    # 展平
    depthwt_1d = depthwt.ravel()
    Ks_1d  = Ks_mat.ravel()
    D0_1d  = D0.ravel()
    c_1d   = c.ravel()
    Ys_1d  = Ys.ravel()
    Yw_1d  = Yw.ravel()
    zmax_1d = zmax.ravel()
    f_1d   = f_ang.ravel()
    Izlt_1d = Izlt.ravel()

    r_slope = slope_arr.ravel() * np.pi / 180.0
    beta = np.cos(r_slope)**2 - Izlt_1d / Ks_1d
    D1   = D0_1d / np.cos(r_slope)**2

    valid = ~(np.isnan(zmax_1d) | np.isnan(Ks_1d) | np.isnan(c_1d) |
              np.isnan(f_1d) | np.isnan(depthwt_1d))
    valid_idx = np.where(valid)[0]
    n_valid = len(valid_idx)
    if n_valid == 0:
        raise ValueError("没有有效像元")

    porosity = np.full(n_cells, 0.4, dtype=np.float64)
    theta_r, alpha_vg = 0.05, 1.0

    # ---- 2. 逐时段入渗分配 ----
    N = len(t)
    n_periods = N - 1
    Inz2 = np.full((n_cells, n_periods), np.nan, dtype=np.float64)

    remaining_ice = np.maximum(ice_content * zmax_1d, 0.0)
    DDF = 8.27 / 1000.0

    # 预排序：像元按高程降序排列（只排一次）
    print("  预排序像元高程...", flush=True)
    dem_1d = Dem.ravel()
    _dem_safe = np.where(np.isnan(dem_1d), -1e38, dem_1d)
    _elev_order = np.argsort(_dem_safe)[::-1]
    # 只保留有效高程
    elev_mask = _dem_safe[_elev_order] > -1e37
    elev_order = _elev_order[elev_mask]
    elev_rows = elev_order // w2
    elev_cols = elev_order % w2
    print(f"  排序完成 ({len(elev_order)} 像元)", flush=True)

    for ss in range(n_periods):
        rain, _, _, _, _ = _read_tif(Path(rain_path) / f"{ss + 1}.tif")
        temp = temp_3d[:, :, ss]
        dt_sec = t[ss + 1] - t[ss]
        dt_days = dt_sec / 86400.0

        # 度日融冰
        act_melt = np.minimum(DDF * np.maximum(temp, 0.0) * dt_days,
                              remaining_ice.reshape(w1, w2))
        remaining_ice = (remaining_ice.reshape(w1, w2) - act_melt).ravel()

        # D8 径流路由（padarray 默认 0-padding，匹配 MATLAB）
        Inz1_pad = np.pad(np.nan_to_num(rain + act_melt, nan=0.0), 1,
                          mode='constant', constant_values=0)
        _d8_route_period(Inz1_pad, Ks_mat * dt_sec, flowdir,
                         elev_rows, elev_cols)

        Inz2[:, ss] = Inz1_pad[1:-1, 1:-1].ravel()

        if ss % 10 == 0 or ss == n_periods - 1:
            print(f"  时段 {ss+1}/{n_periods}  剩余冰均值 {np.mean(remaining_ice):.4f}m", flush=True)

    # ---- 3. 稳定性计算 ----
    time_nodes = np.atleast_1d(time_nodes).ravel()
    nt = len(time_nodes)
    Zbin = 11

    Phead_all = np.full((w1, w2, nt), np.nan, dtype=np.float64)
    ZMAX_all  = np.full((w1, w2, nt), np.nan, dtype=np.float64)
    Fs_all    = np.full((w1, w2, nt), np.nan, dtype=np.float64)
    Theta_all = np.full((w1, w2, nt), np.nan, dtype=np.float64)

    # 预计算: 有效像元的深度层 (Zbin x n_valid)
    Z_valid = np.zeros((n_valid, Zbin), dtype=np.float64)
    for i in range(n_valid):
        Z_valid[i, :] = np.round(np.linspace(0, zmax_1d[valid_idx[i]], Zbin) * 1000) / 1000
    Z_valid[:, 0] = 0.005

    for kt in range(nt):
        Tk = time_nodes[kt]

        # 深度层数组: (n_cells, Zbin) 由 Z_valid 填充
        Z1 = np.full((n_cells, Zbin), np.nan, dtype=np.float64)
        Z1[valid_idx] = Z_valid

        Pdepth      = np.full((n_cells, Zbin), np.nan, dtype=np.float64)
        Fs2         = np.full((n_cells, Zbin), np.nan, dtype=np.float64)
        Theta_layer = np.full((n_cells, Zbin), np.nan, dtype=np.float64)

        for k in range(Zbin):
            Zk = Z1[:, k]
            Pzera = (Zk - depthwt_1d) * beta

            # 瞬态项 Ptran1
            Ptran1 = np.zeros(n_cells, dtype=np.float64)
            for i in range(N):
                delta_t = Tk - t[i]
                if delta_t <= 0:
                    break
                sqrt_dt = np.sqrt(np.maximum(D1, 1e-15) * delta_t)
                Ptran1 += (2.0 * Inz2[:, i] / Ks_1d) * sqrt_dt * _ierfc(Zk / (2.0 * sqrt_dt))

            # 瞬态项 Ptran2
            Ptran2 = np.zeros(n_cells, dtype=np.float64)
            for i in range(N - 1):
                delta_t = Tk - t[i + 1]
                if delta_t <= 0:
                    break
                sqrt_dt = np.sqrt(np.maximum(D1, 1e-15) * delta_t)
                Ptran2 += (2.0 * Inz2[:, i] / Ks_1d) * sqrt_dt * _ierfc(Zk / (2.0 * sqrt_dt))

            Ptran = Ptran1 - Ptran2
            GW1 = np.minimum(Ptran + Pzera, Zk * beta)

            # 含水率
            tk = np.where(GW1 >= 0, porosity,
                          theta_r + (porosity - theta_r) * np.exp(GW1 / alpha_vg))
            Theta_layer[:, k] = np.clip(tk, theta_r, porosity)

            # 无限斜坡 FS
            tan_phi = np.tan(f_1d * np.pi / 180.0)
            denom = Ys_1d * Zk * np.sin(r_slope) * np.cos(r_slope)
            denom[denom == 0] = 1e-15
            Fs2[:, k] = tan_phi / np.tan(r_slope) + (c_1d - GW1 * Yw_1d * tan_phi) / denom
            Pdepth[:, k] = GW1

        # 向量化：取每个有效像元的最小 FS 层
        FS_out = np.full(n_cells, np.nan, dtype=np.float64)
        Phead  = np.full(n_cells, np.nan, dtype=np.float64)
        Zz     = np.full(n_cells, np.nan, dtype=np.float64)
        Tmin   = np.full(n_cells, np.nan, dtype=np.float64)

        fsv = Fs2[valid_idx]        # (n_valid, Zbin)
        pdv = Pdepth[valid_idx]
        thv = Theta_layer[valid_idx]
        z1v = Z1[valid_idx]

        # 处理全 NaN 行的 nanargmin
        has_data = ~np.all(np.isnan(fsv), axis=1)
        min_idx = np.zeros(n_valid, dtype=np.intp)
        min_fs  = np.full(n_valid, np.nan, dtype=np.float64)

        if np.any(has_data):
            sub = fsv[has_data]
            si = np.nanargmin(sub, axis=1)
            min_idx[has_data] = si
            min_fs[has_data] = sub[np.arange(np.sum(has_data)), si]

        # FS>10 截断
        clip_mask = min_fs > 10.0
        min_fs_clipped = np.where(clip_mask, 10.0, min_fs)

        FS_out[valid_idx] = min_fs_clipped
        Zz[valid_idx]     = np.where(clip_mask, 0.005, z1v[np.arange(n_valid), min_idx])
        Phead[valid_idx]  = np.where(clip_mask, pdv[:, 0], pdv[np.arange(n_valid), min_idx])
        Tmin[valid_idx]   = np.where(clip_mask, thv[:, 0], thv[np.arange(n_valid), min_idx])

        Phead_all[:, :, kt] = Phead.reshape(w1, w2)
        ZMAX_all[:, :, kt]  = Zz.reshape(w1, w2)
        Fs_all[:, :, kt]    = FS_out.reshape(w1, w2)
        Theta_all[:, :, kt] = Tmin.reshape(w1, w2)

        print(f"  时间点 {kt+1}/{nt} (Tk={Tk:.0f}s)", flush=True)

    print(f"  numba JIT: {_numba_ok}")
    return Phead_all, ZMAX_all, Fs_all, Theta_all

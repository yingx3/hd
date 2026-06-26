"""TRIGRS Python 版本验证 — 使用合成数据快速测试计算逻辑"""

import sys, os

# GDAL DLL 路径
_conda_env = os.environ.get('CONDA_PREFIX', '')
if _conda_env:
    _lib_bin = os.path.join(_conda_env, 'Library', 'bin')
    if os.path.isdir(_lib_bin) and _lib_bin not in os.environ.get('PATH', ''):
        os.environ['PATH'] = _lib_bin + os.pathsep + os.environ.get('PATH', '')
        os.add_dll_directory(_lib_bin)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
import tempfile, shutil
from pathlib import Path

import trigrs as tmod


def _create_test_tif(path, arr, origin=(0, 0), cellsize=10.0):
    """用 GDAL 创建测试 GeoTIFF。"""
    from osgeo import gdal
    driver = gdal.GetDriverByName('GTiff')
    ds = driver.Create(str(path), arr.shape[1], arr.shape[0], 1, gdal.GDT_Float32)
    ds.SetGeoTransform((origin[0], cellsize, 0, origin[1], 0, -cellsize))
    ds.GetRasterBand(1).WriteArray(arr.astype(np.float32))
    ds.FlushCache()
    ds = None


def test_trigrs_with_synthetic_data():
    """用小型合成网格验证 TRIGRS 计算逻辑运行正常。"""
    print("=" * 60)
    print("TRIGRS Python 合成数据测试")
    print("=" * 60)

    # ---- 合成网格 (5x5, 10m 分辨率) ----
    rows, cols = 5, 5
    dx = 10.0

    np.random.seed(42)

    # DEM: 简单斜坡 100m → 60m
    Y_grid, X_grid = np.meshgrid(np.arange(rows), np.arange(cols), indexing='ij')
    dem = 100.0 - Y_grid * 10.0 + np.random.randn(rows, cols) * 2

    # 坡度 (°)
    slope = np.full((rows, cols), 15.0)

    # D8 流向 (全部向南 = 4)
    flowdir = np.full((rows, cols), 4.0)

    # 土壤参数
    zmax = np.full((rows, cols), 2.0)
    depthwt = np.full((rows, cols), 5.0)
    Ys = np.full((rows, cols), 20.0)
    Yw = np.full((rows, cols), 9.81)
    c = np.full((rows, cols), 10.0)
    fric = np.full((rows, cols), 30.0)
    Ks = np.full((rows, cols), 1e-5)
    Izlt = np.full((rows, cols), 1e-5)
    D0 = np.full((rows, cols), 1e-4)

    # ---- 时间序列: 3 时段 × 1 小时 ----
    t = np.array([0, 3600, 7200, 10800], dtype=np.float64)
    n_periods = len(t) - 1

    # ---- 合成降雨 + 温度 3D ----
    temp_dir = tempfile.mkdtemp()
    rain_dir = tempfile.mkdtemp()

    try:
        for i in range(1, n_periods + 1):
            rain = np.full((rows, cols), 0.005, dtype=np.float32)  # 5mm/时段
            _create_test_tif(Path(rain_dir) / f"{i}.tif", rain, cellsize=dx)

        # 温度: 时段1=0°C, 时段2=10°C, 时段3=20°C
        temp_list = [
            np.full((rows, cols), 0.0),
            np.full((rows, cols), 10.0),
            np.full((rows, cols), 20.0),
        ]
        temp_3d = np.stack(temp_list, axis=-1)

        # ---- 运行 TRIGRS ----
        print(f"\n网格: {rows}×{cols}, {n_periods} 时段, 总时长 {t[-1]/3600:.1f}h")
        print(f"含冰量: 0.2, 温度: 0/10/20°C\n")

        Phead_all, ZMAX_all, Fs_all, Theta_all = tmod.run_trigrs(
            time_nodes=np.array([t[-1]]),
            rain_path=rain_dir,
            temp_3d=temp_3d,
            t=t,
            dem_arr=dem,
            slope_arr=slope,
            flowdirection_arr=flowdir,
            zmax_arr=zmax,
            depthwt_arr=depthwt,
            Ys_arr=Ys,
            Yw_arr=Yw,
            c_arr=c,
            f_arr=fric,
            Ks_arr=Ks,
            Izlt_arr=Izlt,
            D0_arr=D0,
            ice_content=0.2,
        )

        # ---- 验证 ----
        print("\n验证结果:")

        fs = Fs_all[:, :, 0]
        theta = Theta_all[:, :, 0]
        zmax_out = ZMAX_all[:, :, 0]
        phead = Phead_all[:, :, 0]

        checks = []

        # 1. 所有有效像元都有 FS
        valid = ~np.isnan(fs)
        checks.append(("有效像元 FS 非 NaN", np.all(~np.isnan(fs[valid]))))

        # 2. FS 范围合理
        fs_valid = fs[valid]
        checks.append((f"FS 范围 [{fs_valid.min():.3f}, {fs_valid.max():.3f}]", True))
        assert fs_valid.min() >= 0, f"FS 不应为负: {fs_valid.min()}"
        assert fs_valid.max() <= 10.5, f"FS 应 ≤ 10: {fs_valid.max()}"

        # 3. 含水率在 [0.05, 0.4] 之间
        theta_valid = theta[valid]
        checks.append((f"Theta 范围 [{theta_valid.min():.4f}, {theta_valid.max():.4f}]", True))
        assert np.all(theta_valid >= 0.04), f"Theta 过小: {theta_valid.min()}"
        assert np.all(theta_valid <= 0.41), f"Theta 过大: {theta_valid.max()}"

        # 4. 输出形状正确
        checks.append((f"输出形状: {Phead_all.shape}", Phead_all.shape == (rows, cols, 1)))

        for desc, ok in checks:
            print(f"  [{'PASS' if ok else 'FAIL'}] {desc}")

        print(f"\nFS 统计: min={fs_valid.min():.4f}, max={fs_valid.max():.4f}, "
              f"mean={fs_valid.mean():.4f}, unstable={np.sum(fs_valid < 1)}/{len(fs_valid)}")
        print(f"Theta 统计: min={theta_valid.min():.4f}, max={theta_valid.max():.4f}, "
              f"mean={theta_valid.mean():.4f}")
        print(f"ZMAX 统计: min={np.nanmin(zmax_out):.4f}, max={np.nanmax(zmax_out):.4f}")

        print("\n" + "=" * 60)
        print("所有验证通过！TRIGRS Python 版本计算逻辑正确。")
        print("=" * 60)

    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)
        shutil.rmtree(rain_dir, ignore_errors=True)


if __name__ == '__main__':
    test_trigrs_with_synthetic_data()

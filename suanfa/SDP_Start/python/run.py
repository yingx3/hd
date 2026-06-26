"""泥石流起动模型 — 主程序入口（等同 INPUTDATA.m）

用法:
    python run.py
    python run.py --rain_path ... --temp_path ... --output_dir ...

数据路径和参数在 main() 中配置，支持命令行覆盖。
"""

import os
import sys

# GDAL DLL 路径（Windows conda 环境需要）
_conda_env = os.environ.get('CONDA_PREFIX', '')
if _conda_env:
    _lib_bin = os.path.join(_conda_env, 'Library', 'bin')
    if os.path.isdir(_lib_bin) and _lib_bin not in os.environ.get('PATH', ''):
        os.environ['PATH'] = _lib_bin + os.pathsep + os.environ.get('PATH', '')
        os.add_dll_directory(_lib_bin)

import argparse
from pathlib import Path
import numpy as np
import numpy as np

from trigrs import run_trigrs, _read_tif, _write_tif


def main():
    parser = argparse.ArgumentParser(description="色东普泥石流起动模型 (Python)")
    parser.add_argument("--rain_path", default=None, help="降雨栅格文件夹路径")
    parser.add_argument("--temp_path", default=None, help="温度栅格文件夹路径")
    parser.add_argument("--output_dir", default=None, help="输出目录")
    parser.add_argument("--ice_content", type=float, default=0.2, help="体积含冰量 (0~1)")
    parser.add_argument("--temp_pattern", default="temp_%d.tif", help="温度文件命名模板")
    args = parser.parse_args()

    # ========== 数据路径（根据实际路径修改） ==========
    base_dir = Path(__file__).resolve().parent.parent  # 色东普启动模型/

    rain_path = Path(args.rain_path) if args.rain_path else base_dir / "rainfall_tif"
    temp_path = Path(args.temp_path) if args.temp_path else base_dir / "tem_tif"
    output_dir = Path(args.output_dir) if args.output_dir else Path("E:/Projects/ZHLXT/backend/hd-mao_0322/data/SDP_Results")
    output_dir.mkdir(parents=True, exist_ok=True)

    temp_pattern = args.temp_pattern
    ice_content = args.ice_content

    # ========== 1. 读取时间序列 ==========
    t_path = base_dir / "t.txt"
    if not t_path.exists():
        raise FileNotFoundError(f"时间文件不存在: {t_path}")
    t = np.loadtxt(t_path)
    print(f"时间序列: {len(t)} 个节点, 总时长 {t[-1]:.0f}s ({t[-1]/86400:.1f} 天)")

    # ========== 2. 读取温度三维数组 ==========
    num_periods = len(t) - 1
    temp_list = []
    first_transform = None
    first_crs = None

    for i in range(1, num_periods + 1):
        temp_file = temp_path / (temp_pattern % i)
        if not temp_file.exists():
            raise FileNotFoundError(f"温度文件不存在: {temp_file}")
        arr, transform, crs, w, h = _read_tif(temp_file)
        temp_list.append(arr)
        if first_transform is None:
            first_transform = transform
            first_crs = crs

    temp_3d = np.stack(temp_list, axis=-1)  # (rows, cols, n_periods)
    print(f"温度数据: {temp_3d.shape[0]}×{temp_3d.shape[1]} × {num_periods} 时段")

    # ========== 3. 读取静态栅格 ==========
    tif_files = {
        'dem':          base_dir / 'dem.tif',
        'slope':        base_dir / 'slope.tif',
        'flowdirection':base_dir / 'flow_direction.tif',
        'zmax':         base_dir / 'Soil_depth.tif',
        'depthwt':      base_dir / 'depthwt.tif',
        'Ys':           base_dir / 'Weight.tif',
        'Yw':           base_dir / 'water_weight.tif',
        'c':            base_dir / 'cohesion.tif',
        'f':            base_dir / 'friction.tif',
        'Ks':           base_dir / 'Ks.tif',
        'Izlt':         base_dir / 'izlt.tif',
        'D0':           base_dir / 'D0.tif',
    }

    grids = {}
    for name, path in tif_files.items():
        if not path.exists():
            raise FileNotFoundError(f"栅格文件不存在: {path}")
        grids[name], _, _, _, _ = _read_tif(path)
        print(f"  {name}: {path.name} — {grids[name].shape}")

    # ========== 4. 输出时间节点 ==========
    time_nodes = np.array([t[-1]])

    # ========== 5. 调用 TRIGRS ==========
    print(f"\n开始 TRIGRS 计算 (含冰量={ice_content})...")
    Phead_all, ZMAX_all, Fs_all, Theta_all = run_trigrs(
        time_nodes=time_nodes,
        rain_path=str(rain_path),
        temp_3d=temp_3d,
        t=t,
        dem_arr=grids['dem'],
        slope_arr=grids['slope'],
        flowdirection_arr=grids['flowdirection'],
        zmax_arr=grids['zmax'],
        depthwt_arr=grids['depthwt'],
        Ys_arr=grids['Ys'],
        Yw_arr=grids['Yw'],
        c_arr=grids['c'],
        f_arr=grids['f'],
        Ks_arr=grids['Ks'],
        Izlt_arr=grids['Izlt'],
        D0_arr=grids['D0'],
        ice_content=ice_content,
    )

    # ========== 6. 后处理 ==========
    Phead_final = Phead_all[:, :, 0]
    Fs_original = Fs_all[:, :, 0]
    Theta_final = Theta_all[:, :, 0]

    # 安全系数缩放：除以10，映射到 [0,1]
    Fs_scaled = Fs_original / 10.0

    # 滑面深度 = 缩放后FS × 土壤厚度
    zmax_mat = grids['zmax']
    ZMAX_new = Fs_scaled * zmax_mat

    # ========== 7. 保存 GeoTIFF ==========
    h, w = grids['dem'].shape
    print("\n保存结果...")

    _write_tif(output_dir / 'Phead_final.tif',    Phead_final, first_transform, first_crs, h, w)
    _write_tif(output_dir / 'Fs_scaled.tif',      Fs_scaled,   first_transform, first_crs, h, w)
    _write_tif(output_dir / 'ZMAX_final.tif',     ZMAX_new,   first_transform, first_crs, h, w)
    _write_tif(output_dir / 'Theta_final.tif',    Theta_final, first_transform, first_crs, h, w)

    print(f"\n全部完成！结果保存在: {output_dir}")
    print(f"  ZMAX_final.tif    — 泥石流起动区深度 (m)")
    print(f"  Fs_scaled.tif     — 缩放后安全系数 [0,1]")
    print(f"  Phead_final.tif   — 滑面处压力水头 (m)")
    print(f"  Theta_final.tif   — 滑面处体积含水率 (-)")

    valid_zm = ZMAX_new[~np.isnan(ZMAX_new)]
    if len(valid_zm) > 0:
        print(f"  ZMAX 范围: [{valid_zm.min():.4f}, {valid_zm.max():.4f}] m")
    else:
        print(f"  ZMAX: 全部为 NaN（无效像元）")


if __name__ == '__main__':
    main()

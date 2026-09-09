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

# 强制使用新版 PROJ/GDAL 数据目录，避免系统 PostGIS 的旧 proj.db 覆盖
def _fix_proj_data():
    def has(d):
        return bool(d) and os.path.isfile(os.path.join(d, 'proj.db'))
    for base in sys.path:
        for rel in ('rasterio/proj_data', 'pyproj/proj_dir/share/proj', 'rasterio/proj', 'rasterio/data'):
            d = os.path.join(base, rel)
            if has(d):
                os.environ['PROJ_DATA'] = d
                os.environ['PROJ_LIB'] = d
                return
    pfx = os.environ.get('CONDA_PREFIX', '')
    if pfx:
        for rel in ('Library/share/proj', 'share/proj'):
            d = os.path.join(pfx, rel)
            if has(d):
                os.environ['PROJ_DATA'] = d
                os.environ['PROJ_LIB'] = d
                return

_fix_proj_data()

from trigrs import run_trigrs, _read_tif, _write_tif


def main():
    parser = argparse.ArgumentParser(description="色东普泥石流起动模型 (Python)")
    parser.add_argument("--rain_path", default=None, help="降雨栅格文件夹路径")
    parser.add_argument("--temp_path", default=None, help="温度栅格文件夹路径")
    parser.add_argument("--output_dir", default=None, help="输出目录")
    parser.add_argument("--ice_content", type=float, default=0.2, help="体积含冰量 (0~1)")
    parser.add_argument("--temp_pattern", default="temp_%d.tif", help="温度文件命名模板")
    parser.add_argument("--num_time_nodes", type=int, default=8, help="输出的代表性时间节点数")
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

    # ========== 4. 输出时间节点（等间隔取代表性节点，避免全时段内存爆炸） ==========
    n_out = max(1, args.num_time_nodes)
    _idx = np.unique(np.round(np.linspace(0, len(t) - 1, n_out)).astype(int))
    time_nodes = t[_idx]
    print(f"输出代表性时间节点 ({len(time_nodes)} 个): {time_nodes.astype(int)}")

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

    # ========== 6. 后处理：逐时间节点计算并输出 ZMAX 等结果 ==========
    zmax_mat = grids['zmax']
    h, w = grids['dem'].shape
    nt = len(time_nodes)
    print("\n保存结果...")

    time_list = []
    for kt in range(nt):
        tk = float(time_nodes[kt])
        Fs_scaled = Fs_all[:, :, kt] / 10.0
        ZMAX_node = Fs_scaled * zmax_mat
        _write_tif(output_dir / f'ZMAX_t{tk:.0f}.tif', ZMAX_node, first_transform, first_crs, h, w)
        _write_tif(output_dir / f'Phead_t{tk:.0f}.tif', Phead_all[:, :, kt], first_transform, first_crs, h, w)
        _write_tif(output_dir / f'Theta_t{tk:.0f}.tif', Theta_all[:, :, kt], first_transform, first_crs, h, w)
        time_list.append(tk)

    # 兼容：最后一个时间节点另存为 *_final.tif
    last = nt - 1
    Fs_scaled_last = Fs_all[:, :, last] / 10.0
    _write_tif(output_dir / 'Phead_final.tif', Phead_all[:, :, last], first_transform, first_crs, h, w)
    _write_tif(output_dir / 'Fs_scaled.tif', Fs_scaled_last, first_transform, first_crs, h, w)
    _write_tif(output_dir / 'ZMAX_final.tif', Fs_scaled_last * zmax_mat, first_transform, first_crs, h, w)
    _write_tif(output_dir / 'Theta_final.tif', Theta_all[:, :, last], first_transform, first_crs, h, w)

    # 供后端读取的时间节点序列
    print("TIME_NODES=" + ",".join(f"{tk:.0f}" for tk in time_list))

    print(f"\n全部完成！结果保存在: {output_dir}")
    print(f"  共 {nt} 个时间节点")
    for kt in range(nt):
        tk = float(time_nodes[kt])
        ZMAX_node = (Fs_all[:, :, kt] / 10.0) * zmax_mat
        valid = ZMAX_node[~np.isnan(ZMAX_node)]
        rng = f"[{valid.min():.4f}, {valid.max():.4f}] m" if len(valid) else "全部为 NaN（无效像元）"
        print(f"  ZMAX_t{tk:.0f}.tif — t={tk:.0f}s ({tk/86400:.2f} 天): {rng}")


if __name__ == '__main__':
    main()

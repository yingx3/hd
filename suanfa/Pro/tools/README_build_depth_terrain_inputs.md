# Depth + 灾后地形构造 Pro 输入集

对应脚本：`build_depth_terrain_inputs.py`

## 推荐构造方式

当前 `Depth.txt` 不是常规河道水深，而是集中分布的初始滑体/物源厚度。因此推荐：

```text
zL = 对齐后的灾后/滑床地形
zB = zL + Depth
hS = zB - zL = Depth
hW = 独立的初始水深场
```

这样 `Depth` 不参与 `hW`，而是直接决定模型的固体物源厚度。

## 当前易贡数据的对齐关系

- `Depth.txt`、`Terrain_Ob.txt` 原始网格为 352 × 379，方向与地理坐标相反；
- `yg2003.txt` 的对应窗口为 `row=12:391, col=56:408`；
- 窗口数据不做转置，`Depth` 需要转置，二者即可对齐；
- 输出网格：352 × 379、30 m；
- 左下角：`xllcorner=683391.33762234`、`yllcorner=3336878.9403417`；
- 坐标系：`EPSG:32646`。

## 使用示例

```powershell
python .\suanfa\Pro\tools\build_depth_terrain_inputs.py `
  --yg-file "E:\Projects\ZHLXT\算法\Pro\task1_geo\yg2003.txt" `
  --depth-file "E:\Projects\ZHLXT\算法\Pro\task1_geo\Depth.txt" `
  --out-dir "E:\Projects\ZHLXT\算法\Pro\task1_geo_depth_inputs" `
  --p-file "E:\Projects\ZHLXT\算法\Pro\task1\p.txt"
```

如果输出目录已经存在，需要明确加 `--overwrite`。

## 输出文件

| 文件 | 含义 |
|---|---|
| `zB.txt` | 按 Depth 物源厚度构造的灾前/滑体顶面 |
| `zL.txt` | 对齐后的灾后/滑床地形 |
| `hW.txt` | 初始水深；未指定 `--hw-file` 时为全 0 |
| `Depth_geo30m.txt` | 转置并带 ASCII 头的 Depth |
| `base_yg2003_geo30m.txt` | 用于构造的灾后基准地形 |
| `metadata.json` | 来源哈希、窗口、网格和统计信息 |
| `p.txt` | 可选复制到输出目录的参数文件 |

## 重要说明

1. `zB` 不是实测灾前 DEM，而是在灾后滑床上叠加 `Depth` 物源厚度后构造出来的模型初始地形。
2. 当前 `hW.txt` 全为 0。如果模型必须由真实水体驱动，需要另找一套同一 352 × 379、30 m 网格的水深数据，并通过 `--hw-file` 传入。
3. 平台调用该数据时应显式指定 `--source-crs EPSG:32646`。
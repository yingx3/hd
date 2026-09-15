# 拦挡后地形对齐工具（align_terrain_ob.py）

把「灾害链断链调控 / 冰川泥石流沿程调控」产生的**拦挡后地形**（例如 `Terrain_Ob.txt`）
对齐到 `洪水泥石流启动动力学模型`（Pro 内核 `suanfa/Pro/run_pro.py`）的计算网格，
换算成与 `zB` 相同的基准，并生成一套**可以直接计算的输入目录**。

---

## 1. 为什么需要这个工具

task1 输入目录里的四个文件，格式并不一致：

| 文件 | 头部 | 网格 | 数值含义 |
|---|---|---|---|
| `hW.txt` | ESRI ASCII 6 行头 | 444 × 247 @20m | 初始水深（0~59，仅 6651 格有水） |
| `zB.txt` | ESRI ASCII 6 行头 | 444 × 247 @20m | 灾前地形，**相对高程** 0~1570 |
| `zL.txt` | ESRI ASCII 6 行头 | 444 × 247 @20m | 灾后地形（= zB 在 105 格下切 30m） |
| `Terrain_Ob.txt` | **无头部** | **379 × 352** | 拦挡后地形，**绝对海拔** 2084~5535 |

差异点：
1. `Terrain_Ob.txt` 缺少 `ncols/nrows/xllcorner/yllcorner/cellsize/NODATA_value`，无法定位；
2. 网格尺寸与计算网格不同，内核的尺寸一致性检查会直接报错；
3. 数值基准不同（绝对海拔 vs 相对高程），且两者分位差不是常数，不能简单平移；
4. 实测配准：把 `Terrain_Ob` 缩放到 zB 尺寸后相关系数仅 **r≈0.29**，
   试过上下/左右翻转、转置后最高也只有 0.36 —— **两者不是同一块地形**。

因此：`Terrain_Ob.txt` 必须先拿到它的**地理参考**（左下角坐标 + 像元大小），
或者改用「在计算网格上直接加高」的方式生成拦挡地形。

---

## 2. 用法

```bash
# A) Terrain_Ob 有明确地理参考（最可靠）
python align_terrain_ob.py \
    --src-dir "E:\Projects\ZHLXT\算法\Pro\task1_geo" \
    --out-dir "E:\Projects\ZHLXT\算法\Pro\task1_geo_ob" \
    --ob-cellsize 30 --ob-xll 684000 --ob-yll 3339000 \
    --utm-zone 46

# B) 没有地理参考，但确认与 zB 同范围（会给出配准质量警告）
python align_terrain_ob.py --src-dir <目录> --out-dir <输出> --assume-same-extent

# C) 不用 Terrain_Ob：直接在计算网格上按手绘范围加高（等效平台「断链调控」）
python align_terrain_ob.py --src-dir <目录> --out-dir <输出> \
    --polygon "94.955,30.183; 94.975,30.183; 94.975,30.198; 94.955,30.198" \
    --raise 20 --utm-zone 46
```

`--polygon` 的坐标默认按**经纬度**（与前端手绘一致），用 `--utm-zone` 转成投影坐标；
若直接给投影坐标，加 `--polygon-crs proj`。

### 主要参数

| 参数 | 说明 |
|---|---|
| `--src-dir` | 含 `zB.txt / zL.txt / hW.txt` 的目录 |
| `--out-dir` | 输出目录（会生成一套可计算输入） |
| `--ob-file` | 拦挡地形文件名，默认 `Terrain_Ob.txt` |
| `--ob-cellsize / --ob-xll / --ob-yll` | Terrain_Ob 的地理参考（三个一起给） |
| `--ob-flip-y` | Terrain_Ob 第 0 行是最南时使用（numpy 直接保存的数据） |
| `--assume-same-extent` | 无地理参考时按同范围等比缩放 |
| `--polygon` + `--raise` | 手绘范围加高（不使用 Terrain_Ob） |
| `--mode delta` | （默认）`zL + max(增量, 0)`，安全 |
| `--mode keep` | 直接把对齐后的地形当作 `zL` |
| `--min-raise` | 小于该值的增量视为噪声，默认 0.5m |
| `--utm-zone` | 经纬度转 UTM 带号；**task1 数据为 46（EPSG:32646）** |
| `--allow-low-quality` | 配准质量不达标时仍以退出码 0 结束 |

### 输出

```
zB.txt                 灾前地形（原样复制）
hW.txt                 初始水深（原样复制）
zL.txt                 拦挡后地形 —— 计算输入
Terrain_Ob_aligned.txt 对齐 + 基准换算后的地形（核查用）
Terrain_Ob_delta.txt   相对 zB 的增量（拦挡范围）
report.txt             诊断报告（配准质量 / 拟合参数 / 增量统计 / 体积）
```

输出的三个栅格都带完整 ESRI ASCII 头部，`run_pro.py` 的 `read_matrix_file`
可直接读取；目录本身也满足 `resolve_inputs()` 的命名约定。

---

## 3. 配准质量判据

脚本用最小二乘拟合 `zB ≈ a·ob + b`，输出相关系数 `r`：

- **r ≥ 0.90**：可信，结果可直接用于计算；
- **r < 0.90**：强烈警告，说明两份地形形态不一致，
  多半不是同一区域、坐标系或基准不对。此时退出码为 2（除非 `--allow-low-quality`）。

本轮对 `Terrain_Ob.txt` 的实测：**r = 0.29（假定同范围）/ 0.48（假定 30m 地理参考）**，
均未通过，判定为不可直接使用。

---

## 4. 坐标系提示（重要）

`task1_geo` 的 `zB.txt` 头部为 `xllcorner=684528.48, yllcorner=3339838.16, cellsize=20`：

| 解释方式 | 网格中心换算位置 | 结论 |
|---|---|---|
| **UTM zone 46 / EPSG:32646** | **94.963°E, 30.198°N** | ✅ 易贡，与案例一致 |
| UTM zone 47 / EPSG:32647 | 100.963°E, 30.198°N | ❌ 偏东约 6°（约 570km，落到昌都/四川方向） |

因此调用 Pro 内核时，这批数据的 `--source-crs` 应为 **EPSG:32646**。
若平台 `application.yml` 中 `app.pro.source-crs` 仍为 `EPSG:32647`，
渲染位置会出现约 6° 的偏移，请注意核对（不同案例的数据可能不同）。

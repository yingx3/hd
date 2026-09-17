# 拦挡对照实验（冰川泥石流沿程调控）

用于生成"拦挡看得出效果"的案例数据，并用平台同一套流程（WSL + GRASS + r.avaflow 4.0G）
跑一组对照（无拦挡 / 有拦挡），最后做逐帧比对与出图。

## 脚本

| 脚本 | 作用 |
|---|---|
| `gen_case.py` | 生成对照数据：物源加厚到 15 m、影响范围收窄为沟道走廊（9.75 km²） |
| `place_barrier.py` | 把拦挡放在主沟道下游约 1.7 km 的沟底处（横跨流向） |
| `run_case.py` | 按后端同样的流程跑一次：拷输入 → （可选）加高高程 → 生成 `start_beta.sh` → `grass --exec bash` |
| `compare.py` | 读取两次运行的 ASC 帧做逐帧比对，并把末帧/掩膜存成 `.npy` |
| `make_figure.py` | 出对照图：无拦挡 / 有拦挡 / 差值 + 逐帧最大差值曲线 |

## 用法

```powershell
$py = "E:\Projects\ZHLXT\backend\hd-mao_0322\scripts\python\python.exe"
& $py suanfa\avaflow\ab_experiment\gen_case.py            # 生成数据（输出到交付案例目录）
& $py suanfa\avaflow\ab_experiment\place_barrier.py       # 选定拦挡位置
& $py suanfa\avaflow\ab_experiment\run_case.py --job-id ab_base --raise-m 0     # 对照组
& $py suanfa\avaflow\ab_experiment\run_case.py --job-id ab_dam  --raise-m 20    # 有拦挡
& $py suanfa\avaflow\ab_experiment\compare.py
& "D:\application\miniconda3\python.exe" suanfa\avaflow\ab_experiment\make_figure.py
```

## 首轮实测结果（色东普案例）

- 数据：物源 400 格 × 15 m = 540 万 m³；影响范围 9.75 km²（原 92.5 km²）
- 拦挡：源区下游 1.68 km，横跨 510 m（该处沟道宽约 960 m），加高 20 m
- 结果：前 4 帧完全相同；泥石流到达坝体后开始分叉，**逐帧最大差值 6.03 m**（此前薄物源仅 0.32 m）
- 末帧：最大流深 51.61 → 50.97 m；坝前最大淤积 +2.35 m；下游最大减薄 −1.94 m；体积守恒
- 结论：拦挡生效且可测量；想更明显需 **横跨整条沟道（≈960 m）** 并把加高值提到 **60 m 以上**

> 注意：`run_case.py` 会写入 WSL 的 `/home/wm/avaflow_jobs/<jobId>`，需要 WSL + GRASS 环境可用。

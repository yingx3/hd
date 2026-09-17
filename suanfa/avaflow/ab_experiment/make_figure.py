import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import ListedColormap

for name in ('Microsoft YaHei','SimHei'):
    try:
        font_manager.findfont(name, fallback_to_default=False)
        plt.rcParams['font.sans-serif']=[name]; plt.rcParams['axes.unicode_minus']=False; break
    except Exception: continue

T = r'E:\Projects\ZHLXT\backend\hd-mao_0322\target'
base = np.load(T + r'\ab_base_last.npy')
dam  = np.load(T + r'\ab_dam_last.npy')
mask = np.load(T + r'\ab_mask.npy')
allb = np.load(T + r'\ab_base_all.npy')
alld = np.load(T + r'\ab_dam_all.npy')
diff = dam - base

ys, xs = np.where((base > 0.05) | (dam > 0.05) | (np.abs(diff) > 0.5))
r0, r1 = max(0, ys.min()-10), min(base.shape[0], ys.max()+11)
c0, c1 = max(0, xs.min()-10), min(base.shape[1], xs.max()+11)
sl = (slice(r0, r1), slice(c0, c1))
b2, d2, m2 = base[sl], dam[sl], mask[sl]
diff2 = d2 - b2
per_frame = [float(np.abs(d - b).max()) for d, b in zip(alld, allb)]

fig = plt.figure(figsize=(15, 9.5), dpi=125)
gs = fig.add_gridspec(2, 3, height_ratios=[2.5, 1], hspace=0.22, wspace=0.12)

vmax = max(b2.max(), d2.max())
ax0 = fig.add_subplot(gs[0, 0]); im0 = ax0.imshow(b2, cmap='YlOrBr', vmin=0, vmax=vmax)
ax0.set_title('对照组：无拦挡（末帧流深 max=%.1f m）' % b2.max(), fontsize=12); ax0.axis('off')
ax1 = fig.add_subplot(gs[0, 1]); im1 = ax1.imshow(d2, cmap='YlOrBr', vmin=0, vmax=vmax)
ax1.set_title('有拦挡：下游1.7km处加高20m（末帧 max=%.1f m）' % d2.max(), fontsize=12); ax1.axis('off')
ax2 = fig.add_subplot(gs[0, 2]); lim = float(np.abs(diff2).max())
im2 = ax2.imshow(diff2, cmap='RdBu_r', vmin=-lim, vmax=lim)
ax2.set_title('差值（有拦挡 − 无拦挡）%.1f ~ +%.1f m' % (diff2.min(), diff2.max()), fontsize=12); ax2.axis('off')
for ax in (ax0, ax1, ax2):
    ax.contour(m2, levels=[0.5], colors=['#ff2d55'], linewidths=1.6)
ax2.text(*np.meshgrid(np.arange(m2.shape[1]), np.arange(m2.shape[0]))[0][m2][:1] if False else (0,0), '', color='w')
fig.colorbar(im0, ax=[ax0, ax1], fraction=0.03, pad=0.01, label='流深 (m)')
fig.colorbar(im2, ax=ax2, fraction=0.046, pad=0.01, label='Δ流深 (m)')

ax3 = fig.add_subplot(gs[1, :])
ax3.plot(range(1, len(per_frame)+1), per_frame, color='#d94801', lw=1.8)
ax3.set_xlabel('输出帧（每帧约 1.75 s，共 114 帧 ≈ 200 s）'); ax3.set_ylabel('全场最大差值 (m)')
ax3.set_title('逐帧最大差值：前 4 帧完全相同，泥石流到达坝体（约第 5 帧）后开始分叉，最大 %.2f m' % max(per_frame), fontsize=12)
ax3.grid(alpha=0.25)
fig.suptitle('色东普案例 断链防控对照实验：物源加厚至 15 m + 影响范围收窄到沟道走廊（9.75 km²）\n'
             '拦挡位于源区下游 1.68 km，横跨 510 m、加高 20 m（沟道在该处宽约 960 m）', fontsize=13)
out = r'C:\Users\user\.codex\visualizations\2026\09\07\01a07a93-1f3b-7d21-a43e-23d0fd5f2bf2\ab_barrier_compare.png'
fig.savefig(out, bbox_inches='tight'); print('saved', out)
print('末帧: 无拦挡 max=%.2f 有拦挡 max=%.2f 最大增厚 %.2f 最大减薄 %.2f' % (base.max(), dam.max(), diff.max(), diff.min()))
print('坝范围内末帧最大流深: 无拦挡 %.2f m / 有拦挡 %.2f m' % (base[mask].max(), dam[mask].max()))
print('逐帧最大差值: 前几个 %.3f %.3f %.3f %.3f %.3f' % tuple(per_frame[:5]), '峰值 %.2f m' % max(per_frame))

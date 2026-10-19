import numpy as np
import math
import sys
import json
import os

def build_time_axis(melt_hours):
    """与原始 ice-melt-software 一致：每小时 600 个采样点。

    上限 100 万点，防止极端融冰时长在服务端占满内存。
    """
    hours = float(melt_hours)
    if not (hours > 0):
        raise ValueError("融冰时长必须大于 0")
    count = int(round(hours) * 600)
    count = max(2, min(count, 1000000))
    return np.linspace(0.0, hours * 3600.0, count)


def downsample_curve(t, fos, max_points=1000):
    """服务端只需要极值与少量采样点，避免把上百万点写进 JSON。"""
    t = np.asarray(t, dtype=float)
    fos = np.asarray(fos, dtype=float)
    if t.size <= max_points:
        return t, fos
    idx = np.linspace(0, t.size - 1, max_points).astype(int)
    return t[idx], fos[idx]


class bedding():
    def __init__(self, melt_duration, slope_angle, slide_angle, ice_thickness,
                 fissure_height, slide_length, cohesion, friction_angle,
                 rock_density, permeability):
        # ===================== 直接定义所有计算参数 =====================
        self.t_total = melt_duration  # 融冰时长
        self.alpha = slope_angle  # 边坡坡度
        self.theta = slide_angle  # 滑面角
        self.hi = ice_thickness  # 冰层厚度
        self.hs = fissure_height  # 裂隙高度
        self.L0 = slide_length  # 滑面长度
        self.c_ = cohesion  # 有效粘聚力
        self.theta_ = friction_angle  # 有效内摩擦角
        self.rs = rock_density  # 饱和岩体重度
        self.Ks = permeability  # 渗透系数

        # 时间数组（小时转秒，高密度采样）
        self.t = build_time_axis(self.t_total)

        # 固定物理参数
        self.s1 = np.sin(np.radians(self.theta))
        self.c1 = np.cos(np.radians(self.theta))
        self.k1 = np.tan(np.radians(self.theta))
        self.k2 = np.tan(np.radians(self.alpha))
        self.rw = 10     # 水的重度
        self.ri = 9.15   # 冰的重度
        self.Af = 0.005  # 裂隙面积
        self.tc = 40     # 标准冰棱柱体融冰时长
        self.v0 = math.pi * 0.15 * 0.0038 ** 2  # 标准冰棱柱体体积
        self.vf = self.hi * self.L0 * self.c1  # 冰的初始体积
        self.ro = self.vf / self.v0  # 换算系数

    # 坡面函数
    def slope_surface(self, x):
        h = self.k1 * x + self.L0 * self.s1 + self.hs
        mask = x <= (self.L0 * (self.s1 - self.c1) + self.hs) / (self.k2 - self.k1)
        h[mask] = self.k2 * x[mask] + self.L0 * self.c1
        return h

    # 基岩表面函数
    def slope_base(self, x):
        h = self.k1 * x + self.L0 * self.s1
        return h

    # 融冰体积随时间变化
    def volume(self, t):
        ratio = t / (self.ro * self.tc)
        v = 1.26 * self.vf * (np.exp(-0.2 * ratio) - np.exp(-3.73 * np.square(ratio)))
        return v

    # 融冰水流强度
    def H(self, t, v):
        Lambda = self.Ks / self.L0
        dt = t[1] - t[0]
        df = np.gradient(v, dt)
        h = (self.ri * df / (self.Af * self.rw) - self.Ks * self.s1) * np.exp(-Lambda * t)
        return h

    # 安全系数计算
    def factor_of_safety(self, t):
        # 200 点 + 梯形积分，与原始软件的 quad 数值积分一致
        x = np.linspace(-self.L0 * self.c1 / self.k2, 0, 200)
        v = self.volume(t)
        h_ice = (self.vf - v) / (self.L0 * self.c1)

        surf1 = self.slope_surface(x)
        surf2 = self.slope_base(x)
        dh = surf1 - surf2
        area = float(np.sum((dh[:-1] + dh[1:]) * 0.5 * (x[1] - x[0])))

        # 滑体重量
        m = self.rs * area + self.ri * h_ice * self.L0 * self.c1
        HH = -self.H(t, v)
        # 注意：H 的单位是 m/s，乘时间步 dt 才是水头高度 m；
        # 原实现误用空间步长 dx，量纲变成 m²/s。
        ht = (t[1] - t[0]) * np.cumsum(HH, axis=0)

        # 抗滑力 & 下滑力
        resist = self.c_ * self.L0 + (m * self.c1 - 0.5 * self.rw * ht * self.L0 -
                                      0.5 * self.s1 * self.rw * np.square(ht)) * np.tan(np.radians(self.theta_))
        drive = m * self.s1 + 0.5 * self.c1 * self.rw * np.square(ht)
        fos = resist / drive
        return fos

    # 主运行函数
    def run(self):
        t = self.t
        fos = self.factor_of_safety(t)
        # 输出完整数组（无省略、无格式、Java可直接解析）
        t_out, fos_out = downsample_curve(t, fos)
        finite = fos[np.isfinite(fos)]
        data = {
            "t": t_out.tolist(),
            "fos": fos_out.tolist(),
            "minFos": float(np.min(finite)) if finite.size else 1.0,
            "maxFos": float(np.max(finite)) if finite.size else 1.0,
            "iceVolume": float(self.vf),
            "debrisVolume": float(self.vf * 3.0)
        }
        # 写入静态文件（兼容旧版前端）
        os.makedirs('src/main/resources/static', exist_ok=True)
        with open(r'src/main/resources/static/output_bedding.txt', 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)
        # 单行标记，Java 用 extractSentinel 读取
        print("RESULT_JSON=" + json.dumps(data, ensure_ascii=False))

        # 打印 t 数组
    #     print("t_array:", t)
    # # 打印 fos 数组
    #     print("fos_array:", fos)
    #     print("result:",result)

# ===================== 直接运行 =====================
if __name__ == '__main__':
    params = {
        "melt_duration": float(sys.argv[1]),
        "slope_angle": float(sys.argv[2]),
        "slide_angle": float(sys.argv[3]),
        "ice_thickness": float(sys.argv[4]),
        "fissure_height": float(sys.argv[5]),
        "slide_length": float(sys.argv[6]),
        "cohesion": float(sys.argv[7]),
        "friction_angle": float(sys.argv[8]),
        "rock_density": float(sys.argv[9]),
        "permeability": float(sys.argv[10])
    }

    model = bedding(**params)
    model.run()
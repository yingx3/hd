# -*- coding: utf-8 -*-
"""反倾（反倾斜坡）冰岩崩启动模型。

算法与交付的 ice-melt-software.py 中 anti 类保持一致：
每小时 600 个时间采样点、孔压水头按时间步累积、按结构面逐层叠加抗滑力/下滑力。
"""
import json
import math
import os
import sys

import numpy as np


def build_time_axis(melt_hours):
    """与原始 ice-melt-software 一致：每小时 600 个采样点（上限 100 万）。"""
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


class Anti:
    def __init__(self, melt_duration, slope_angle, inverse_angle, ice_thickness,
                 slope_height, bedding_space, cohesion, friction_angle,
                 rock_density, permeability):
        self.t_total = float(melt_duration)
        self.alpha = float(slope_angle)
        self.beta = float(inverse_angle)
        self.hi = float(ice_thickness)
        self.hs = float(slope_height)
        self.b = float(bedding_space)
        self.c_ = float(cohesion)
        self.theta_ = float(friction_angle)
        self.rs = float(rock_density)
        self.Ks = float(permeability)

        self.t = build_time_axis(self.t_total)
        self.rw = 10
        self.ri = 9.15
        self.tc = 40
        self.v0 = math.pi * 0.15 * 0.0038 ** 2
        self.vf = self.hi * self.hs * np.tan(np.radians(self.beta))
        self.ro = self.vf / self.v0 if self.v0 else 0.0
        self.L0 = self.hs / np.sin(np.radians(90 - self.beta))
        self.s1 = np.sin(np.radians(90 - self.beta))
        self.Af = 0.005

    def anti_inclined_slope(self, x, b, beta):
        threshold = self.hs * np.sin(np.radians(self.alpha + beta)) / (
            b * np.sin(np.radians(self.alpha)))
        if x <= 0:
            return -x * b / np.tan(np.radians(beta))
        elif x <= threshold:
            return -x * b / np.tan(np.radians(self.alpha + beta))
        return self.hs / np.sin(np.radians(beta)) - x * b / np.tan(np.radians(beta))

    def ice_surface(self, x, b, beta, h):
        threshold = self.hs * np.sin(np.radians(self.alpha + beta)) / (
            b * np.sin(np.radians(self.alpha)))
        base = h.all() * np.cos(np.radians(beta)) if hasattr(h, "all") else h * np.cos(np.radians(beta))
        if x <= base:
            return -x * b / np.tan(np.radians(self.alpha)) + h * (
                np.sin(np.radians(beta)) - np.cos(np.radians(beta)) / np.tan(np.radians(beta)))
        elif x <= base + threshold:
            return -x * b / np.tan(np.radians(self.alpha + beta)) + h * (
                np.sin(np.radians(beta)) - np.cos(np.radians(beta)) / np.tan(np.radians(self.alpha + beta)))
        return self.hs / np.sin(np.radians(beta)) - x * b / np.tan(np.radians(self.alpha)) + h * (
            np.sin(np.radians(beta)) - np.cos(np.radians(beta)) / np.tan(np.radians(beta)))

    def volume(self, t):
        return 1.26 * self.vf * (
            np.e ** (-0.2 * (t / (self.ro * self.tc)))
            - np.e ** (-3.73 * np.square(t / (self.ro * self.tc))))

    def H(self, t, l):
        if l <= 0:
            return np.zeros_like(t)
        Lambda = self.Ks * np.sin(np.radians(self.beta)) / l
        v = self.volume(t)
        df = np.gradient(v, t[1] - t[0])
        q = self.ri * df / (self.Af * self.rw)
        return (q - self.Ks * self.s1) * np.exp(-Lambda * t)

    def factor_of_safety(self, t):
        L = self.hs / np.cos(np.radians(self.beta))
        n = max(int(L / self.b), 1)
        v = self.volume(t)
        h = (self.vf - v) / (self.hs * np.tan(np.radians(self.beta)))

        beta_rad = np.radians(self.beta)
        theta_rad = np.radians(self.theta_)
        r_total = np.zeros_like(t)
        d_total = np.zeros_like(t)

        for i in range(n):
            # 参考实现里剖面函数的 x 是层序号（步长 b 在公式内相乘），
            # 只有渗流长度 H(t, l) 才用米。
            h1 = self.anti_inclined_slope(i, self.b, self.beta)
            h2 = self.anti_inclined_slope(i + 1, self.b, self.beta)
            h_ice1 = self.ice_surface(i, self.b, self.beta, h) - h1
            h_ice2 = self.ice_surface(i + 1, self.b, self.beta, h) - h2

            hh1 = 0
            if i * self.b > 0:
                hh1 = -self.H(t, i * self.b)
            hh2 = -self.H(t, (i + 1) * self.b)
            dt = t[1] - t[0]
            ht1 = dt * np.cumsum(hh1, axis=0)
            ht2 = dt * np.cumsum(hh2, axis=0)

            r_total += (
                0.5 * self.rw * np.square(ht1) * np.sin(beta_rad)
                + self.c_ * self.b
                + (self.rs * 0.5 * (h1 + h2) * self.b
                   + self.ri * 0.5 * (h_ice1 + h_ice2) * self.b
                   - 0.5 * self.rw * (ht1 + ht2) * self.b)
                * np.sin(beta_rad) * np.tan(theta_rad)
            )
            d_total += (
                (self.rs * 0.5 * (h1 + h2) * self.b
                 + self.ri * 0.5 * (h_ice1 + h_ice2) * self.b) * np.cos(beta_rad)
                + 0.5 * self.rw * np.square(ht2) * np.sin(beta_rad)
            )

        safe = np.where(np.abs(d_total) < 1e-12, 1e-12, d_total)
        return v, r_total / safe

    def run(self):
        if self.alpha + self.beta < 90:
            print("RESULT_JSON=" + json.dumps({"error": "坡度角+反倾角必须≥90°"}, ensure_ascii=False))
            return

        _, fos = self.factor_of_safety(self.t)
        flag = 0 if np.any(fos < 1) else 1
        np.savetxt(r'./output_anti_flag.txt', np.array([flag]), fmt='%d')

        t_out, fos_out = downsample_curve(self.t, fos)
        finite = fos[np.isfinite(fos)]
        data = {
            "t": t_out.tolist(),
            "fos": fos_out.tolist(),
            "minFos": float(np.min(finite)) if finite.size else 1.0,
            "maxFos": float(np.max(finite)) if finite.size else 1.0,
            "iceVolume": float(self.vf),
            "debrisVolume": float(self.vf * 3.0),
        }
        os.makedirs('src/main/resources/static', exist_ok=True)
        with open(r'src/main/resources/static/output_anti.txt', 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)
        print("RESULT_JSON=" + json.dumps(data, ensure_ascii=False))


if __name__ == '__main__':
    params = {
        "melt_duration": float(sys.argv[1]),
        "slope_angle": float(sys.argv[2]),
        "inverse_angle": float(sys.argv[3]),
        "ice_thickness": float(sys.argv[4]),
        "slope_height": float(sys.argv[5]),
        "bedding_space": float(sys.argv[6]),
        "cohesion": float(sys.argv[7]),
        "friction_angle": float(sys.argv[8]),
        "rock_density": float(sys.argv[9]),
        "permeability": float(sys.argv[10]),
    }
    Anti(**params).run()

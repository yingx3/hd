import numpy as np
from scipy.integrate import quad
import math
import sys
import json
import os


def build_time_axis(melt_hours):
    """与参考软件一致：每小时 600 个采样点。"""
    hours = float(melt_hours)
    if not (hours > 0):
        raise ValueError("融冰时长必须大于 0")
    count = int(round(hours) * 600)
    count = max(2, count)
    return np.linspace(0.0, hours * 3600.0, count)


def downsample_curve(t, fos, max_points=1000):
    """服务端只返回极值与有限采样点，避免大量点写入 JSON。"""
    t = np.asarray(t, dtype=float)
    fos = np.asarray(fos, dtype=float)
    if t.size <= max_points:
        return t, fos
    idx = np.linspace(0, t.size - 1, max_points).astype(int)
    return t[idx], fos[idx]


class bedding():
    """顺层冰岩体稳定系数模型，按新版参考软件直接移植。

    参数约定：
    - theta：滑面倾角（度）；cohesion/friction_angle：岩体内聚力（MPa）与内摩擦角（度）
    - L0：滑面长度；rb：岩桥长度，L=L0+rb
    - 滑面强度由程序内部按参考软件系数自动派生
    """

    def __init__(self, melt_duration, slope_angle, slide_angle, ice_thickness,
                 fissure_height, slide_length, cohesion, friction_angle,
                 rock_density, permeability, rock_bridge_length=0.0):
        self.t_total = float(melt_duration)
        self.alpha = float(slope_angle)
        self.theta = float(slide_angle)
        self.hi = float(ice_thickness)
        self.hs = float(fissure_height)
        self.L0 = max(0.0, float(slide_length))
        self.rb = max(0.0, float(rock_bridge_length))
        self.L = self.rb + self.L0
        # 新版参考软件只输入岩体强度，滑面强度在程序内部派生。
        self.c = float(cohesion) * 1e6
        self.c_ = self.c * 0.2
        self.theta2 = float(friction_angle)
        self.theta_ = np.degrees(np.arctan(np.tan(np.radians(self.theta2)) * 0.5))
        self.rs = float(rock_density) * 1000.0
        self.Ks = float(permeability)

        if not (self.L > 0):
            raise ValueError("滑面长度与岩桥长度之和必须大于 0")
        self.t = build_time_axis(self.t_total)
        self.s1 = np.sin(np.radians(self.theta))
        self.c1 = np.cos(np.radians(self.theta))
        self.k1 = np.tan(np.radians(self.theta))
        self.k2 = np.tan(np.radians(self.alpha))
        if abs(self.k2 - self.k1) < 1e-12:
            raise ValueError("边坡角度与滑面角不能相同")
        self.rw = 10000.0
        self.ri = 9150.0
        self.Af = 0.005
        self.tc = 40.0
        self.v0 = math.pi * 0.15 * 0.0038 ** 2
        self.vf = self.hi * self.L * self.c1
        self.ro = self.vf / self.v0 if self.v0 else 0.0

    def slope_surface(self, x):
        h = self.k1 * x + self.L * self.s1 + self.hs
        mask = x <= (self.L * (self.s1 - self.c1) + self.hs) / (self.k2 - self.k1)
        h[mask] = self.k2 * x[mask] + self.L * self.c1
        return h

    def _surface_scalar(self, x):
        if x <= (self.L * (self.s1 - self.c1) + self.hs) / (self.k2 - self.k1):
            return self.k2 * x + self.L * self.c1
        return self.k1 * x + self.L * self.s1 + self.hs

    def _base_scalar(self, x):
        return self.k1 * x + self.L * self.s1

    def slope_base(self, x):
        return self.k1 * x + self.L * self.s1

    def volume(self, t):
        if self.ro <= 0:
            return np.zeros_like(np.asarray(t, dtype=float))
        ratio = t / (self.ro * self.tc)
        return 1.26 * self.vf * (np.exp(-0.2 * ratio) - np.exp(-3.73 * np.square(ratio)))

    def H(self, t, v):
        Lambda = self.Ks / self.L
        dt = t[1] - t[0]
        df = np.gradient(v, dt)
        return (self.ri * df / (self.Af * self.rw) - self.Ks * self.s1) * np.exp(-Lambda * t)

    def factor_of_safety(self, t):
        x0 = -self.L * self.c1 / self.k2
        v = self.volume(t)
        h_ice = (self.vf - v) / (self.L * self.c1)
        area = (quad(self._surface_scalar, x0, 0)[0]
                - quad(self._base_scalar, x0, 0)[0])
        m = self.rs * area + self.ri * h_ice * self.L * self.c1
        HH = -self.H(t, v)
        ht = (t[1] - t[0]) * np.cumsum(HH, axis=0)

        if self.rb == 0:
            resist = self.c_ * self.L + (
                m * self.c1
                - 0.5 * self.rw * ht * self.L
                - 0.5 * self.s1 * self.rw * np.square(ht)
            ) * np.tan(np.radians(self.theta_))
            drive = m * self.s1 + 0.5 * self.c1 * self.rw * np.square(ht)
        else:
            n2 = self.rs * (
                quad(self._surface_scalar, x0, -self.rb * self.c1)[0]
                - quad(self._base_scalar, x0, -self.rb * self.c1)[0]
            ) + self.ri * h_ice * self.rb * self.c1 - 0.5 * self.rw * ht * np.square(self.rb) / self.L
            n1 = self.rs * (
                quad(self._surface_scalar, -self.rb * self.c1, 0)[0]
                - quad(self._base_scalar, -self.rb * self.c1, 0)[0]
            ) + self.ri * h_ice * self.L0 * self.c1 - 0.5 * (
                self.rw * ht * self.rb / self.L + self.rw * ht
            ) * self.L0 - 0.5 * self.s1 * self.rw * np.square(ht)
            resist = (self.c_ * self.L0 + self.c * self.rb
                      + np.tan(np.radians(self.theta_)) * n1
                      + np.tan(np.radians(self.theta2)) * n2)
            drive = m * self.s1 + 0.5 * self.c1 * self.rw * np.square(ht)

        with np.errstate(divide='ignore', invalid='ignore'):
            return resist / drive

    def run(self):
        t = self.t
        fos = self.factor_of_safety(t)
        t_out, fos_out = downsample_curve(t, fos)
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
        with open(r'src/main/resources/static/output_bedding.txt', 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)
        print("RESULT_JSON=" + json.dumps(data, ensure_ascii=False))


if __name__ == '__main__':
    if len(sys.argv) < 11:
        raise SystemExit("参数不足：需要至少 10 个参数")
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
        "permeability": float(sys.argv[10]),
        "rock_bridge_length": float(sys.argv[11]) if len(sys.argv) > 11 else 0.0,
    }
    bedding(**params).run()
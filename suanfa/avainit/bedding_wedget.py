import json
import math
import os
import sys

import numpy as np


def build_time_axis(melt_hours):
    hours = float(melt_hours)
    if not (hours > 0):
        raise ValueError("融冰时长必须大于 0")
    count = int(round(hours) * 600)
    count = max(2, count)
    return np.linspace(0.0, hours * 3600.0, count)


def downsample_curve(t, fos, max_points=1000):
    t = np.asarray(t, dtype=float)
    fos = np.asarray(fos, dtype=float)
    if t.size <= max_points:
        return t, fos
    idx = np.linspace(0, t.size - 1, max_points).astype(int)
    return t[idx], fos[idx]


def json_values(values):
    return [float(v) if np.isfinite(v) else None for v in np.asarray(values, dtype=float)]


class wedge:
    """楔形冰岩体稳定系数模型，按新版参考软件直接移植。"""

    def __init__(self, melt_duration, slope_angle, normal_vector, ice_thickness,
                 square, slope_height, fracture, cohesion, friction_angle,
                 rock_density, permeability, rock_bridge_length=0.0,
                 bridge_cohesion=0.0, bridge_friction_angle=0.0):
        try:
            self.n1 = np.array([float(v) for v in str(normal_vector).split(',')])
        except Exception as exc:
            raise ValueError("normal_vector 需为逗号分隔的三维向量，例：1,2,1.3") from exc
        if self.n1.size != 3:
            raise ValueError("normal_vector 需为三维向量，例：1,2,1.3")

        self.n2 = np.array([-self.n1[0], self.n1[1], self.n1[2]])
        self.hi = float(ice_thickness)
        self.hs = float(slope_height)
        self.a1 = float(square)
        self.a2 = self.a1
        self.t_total = float(melt_duration)
        self.alpha = float(slope_angle)
        self.c_ = float(cohesion) * 1e6
        self.theta_ = float(friction_angle)
        self.c = float(bridge_cohesion) * 1e6
        self.theta = float(bridge_friction_angle)
        self.rs = float(rock_density) * 1000.0
        self.Ks = float(permeability)
        self.ra = max(0.0, float(rock_bridge_length))
        self.rw = 10000.0
        self.ri = 9150.0
        self.tc = 40.0
        self.v0 = math.pi * 0.15 * 0.0038 ** 2
        self.t = build_time_axis(self.t_total)

        if not (self.a1 > 0):
            raise ValueError("面积必须大于 0")
        if not (self.hs > 0):
            raise ValueError("边坡高度必须大于 0")

        z = np.array([0.0, 0.0, 1.0])
        normal1, normal2 = self.unit(self.n1), self.unit(self.n2)
        line = np.cross(normal1, normal2)
        direction = self.unit(line)
        if direction[2] > 0:
            direction = -direction
        self.omga = np.radians(180 - np.degrees(np.arccos(np.dot(direction, z))))
        self.norm_d = np.array([0.0, np.cos(self.omga), np.sin(self.omga)])
        self.d0 = abs(self.hs * np.tan(self.omga))
        if abs(self.d0) < 1e-12:
            raise ValueError("楔体几何参数 d0 不能为 0，请检查法向量与边坡高度")
        d2 = abs(self.hs / np.tan(np.radians(self.alpha)))
        d1 = self.d0 - d2
        ls = 2 * (abs(self.a1 * np.dot(normal1, z)) + abs(self.a2 * np.dot(normal2, z))) / self.d0
        self.s1 = 0.5 * ls * d1
        self.s2 = 0.5 * ls * d2 / np.cos(np.radians(self.alpha))
        self.vf = self.hi * (self.s1 + np.cos(np.radians(self.alpha)) * self.s2)
        self.ro = self.vf / self.v0 if self.v0 else 0.0
        self.ll = np.sqrt(np.square(self.d0) + np.square(self.hs))
        self.l1 = np.sqrt(np.square(0.5 * ls) + np.square(d1))
        self.ratio = self.ra / self.ll if self.ll else 0.0
        self.af = self.a1 * (1 - np.square(self.ratio))
        if abs(self.l1) < 1e-12:
            raise ValueError("楔体几何参数 l1 不能为 0")
        self.hf = 2 * self.af / ((1 + self.ratio) * self.l1)
        self.sb = self.af
        self.sbz = abs(self.sb * np.dot(normal1, z))
        self.Af = 0.005

    @staticmethod
    def unit(vector):
        norm = np.linalg.norm(vector)
        if norm == 0:
            raise ValueError("zero vector")
        return vector / norm

    def volume(self, t):
        if self.ro <= 0:
            return np.zeros_like(np.asarray(t, dtype=float))
        return 1.26 * self.vf * (
            np.exp(-0.2 * (t / (self.ro * self.tc)))
            - np.exp(-3.73 * np.square(t / (self.ro * self.tc)))
        )

    def H(self, t):
        if abs(self.sb) < 1e-12:
            raise ValueError("楔体岩桥面积过小，无法计算渗透水头")
        Lambda = self.Ks / self.sbz if abs(self.sbz) > 1e-12 else 0.0
        v = self.volume(t)
        df = np.gradient(v, t[1] - t[0])
        q = self.ri * df / (self.Af * self.l1 * 2 * self.rw)
        return (q - self.Ks * self.sbz / self.sb) * np.exp(-Lambda * t)

    def factor_of_safety(self, t):
        volume = self.volume(t)
        height = (self.vf - volume) / (
            self.s1 + np.cos(np.radians(self.alpha)) * self.s2
        )
        mass = self.rs * self.s1 * self.hs / 3.0
        hydraulic = -self.H(t)
        pressure_head = (t[1] - t[0]) * np.array(np.cumsum(hydraulic, 0))
        water_pressure = (
            self.rw * pressure_head * self.hf * (1 + self.ratio) * self.l1 / 6.0
        )
        drive = (mass + self.ri * height) * np.cos(self.omga)
        if self.ra == 0:
            resistance = (
                (mass + self.ri * height) * np.sin(self.omga)
                * np.tan(np.radians(self.theta_))
                + 2 * self.c_ * self.a1
                - water_pressure * np.dot(self.n1, self.norm_d)
            )
        else:
            ratio3 = self.ratio ** 3
            resistance = (
                ratio3 * (mass + self.ri * height) * np.sin(self.omga)
                * np.tan(np.radians(self.theta))
                + (1 - ratio3) * (mass + self.ri * height) * np.sin(self.omga)
                * np.tan(np.radians(self.theta_))
                + 2 * self.c_ * self.af
                + 2 * self.c * (self.a1 - self.af)
                - water_pressure * np.dot(self.n1, self.norm_d)
            )
        with np.errstate(divide='ignore', invalid='ignore'):
            return resistance / drive

    def run(self):
        if self.ratio < 0.05 or self.ratio > 1:
            print("RESULT_JSON=" + json.dumps(
                {"error": "岩桥长度比例需在 0.05~1 之间"}, ensure_ascii=False
            ))
            return
        if 90 - np.degrees(self.omga) - self.alpha >= 0:
            print("RESULT_JSON=" + json.dumps(
                {"error": "楔体滑动倾角(%.1f°) > 边坡倾角(%.1f°)，请输入正确的法向量或增大边坡倾角"
                 % (np.round(90 - np.degrees(self.omga), 1), self.alpha)},
                ensure_ascii=False,
            ))
            return

        fos = self.factor_of_safety(self.t)
        t_out, fos_out = downsample_curve(self.t, fos)
        finite = fos[np.isfinite(fos)]
        data = {
            "t": json_values(t_out),
            "fos": json_values(fos_out),
            "minFos": float(np.min(finite)) if finite.size else 1.0,
            "maxFos": float(np.max(finite)) if finite.size else 1.0,
            "iceVolume": float(self.vf),
            "debrisVolume": float(self.vf * 3.0),
        }
        os.makedirs('src/main/resources/static', exist_ok=True)
        with open(r'src/main/resources/static/output_wedge.txt', 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)
        print("RESULT_JSON=" + json.dumps(data, ensure_ascii=False))


if __name__ == '__main__':
    if len(sys.argv) < 12:
        raise SystemExit("参数不足：需要至少 11 个参数")
    params = {
        "melt_duration": float(sys.argv[1]),
        "slope_angle": float(sys.argv[2]),
        "normal_vector": sys.argv[3],
        "ice_thickness": float(sys.argv[4]),
        "square": float(sys.argv[5]),
        "slope_height": float(sys.argv[6]),
        "fracture": float(sys.argv[7]),
        "cohesion": float(sys.argv[8]),
        "friction_angle": float(sys.argv[9]),
        "rock_density": float(sys.argv[10]),
        "permeability": float(sys.argv[11]),
        "rock_bridge_length": float(sys.argv[12]) if len(sys.argv) > 12 else 0.0,
        "bridge_cohesion": float(sys.argv[13]) if len(sys.argv) > 13 else 0.0,
        "bridge_friction_angle": float(sys.argv[14]) if len(sys.argv) > 14 else 0.0,
    }
    wedge(**params).run()
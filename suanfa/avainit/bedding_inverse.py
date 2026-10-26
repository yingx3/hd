# -*- coding: utf-8 -*-
"""反倾冰岩崩启动模型。

按新版参考软件 anti 类的倾倒递推公式移植：
- 按结构面条带分层；
- 岩桥段与裂隙段使用不同抗拉强度；
- 通过 P_(n+1)=0 递推并求 P_1(Fs)=0；
- 默认不透水条件下计算稳定系数曲线。
"""
import json
import math
import os
import sys
from dataclasses import dataclass

import numpy as np
from scipy.optimize import brentq


def downsample_curve(t, fos, max_points=1000):
    t = np.asarray(t, dtype=float)
    fos = np.asarray(fos, dtype=float)
    if t.size <= max_points:
        return t, fos
    idx = np.linspace(0, t.size - 1, max_points).astype(int)
    return t[idx], fos[idx]


def json_values(values):
    return [float(v) if np.isfinite(v) else None for v in np.asarray(values, dtype=float)]


@dataclass
class Layer:
    width: float
    h_right: float
    h_left: float
    rock_weight: float
    ice_weight: float
    tensile_strength: float
    uplift: float = 0.0
    uplift_eccentricity: float = 0.0


class Anti:
    def __init__(self, melt_duration, slope_angle, inverse_angle, ice_thickness,
                 slope_height, bedding_space, fracture_tensile_strength,
                 rock_tensile_strength, rock_density, permeability,
                 rock_bridge_length=0.0):
        self.mu = 0.3
        self.eta = 0.5
        self.gamma_water_N_m3 = 10.0
        self.gamma_ice_N_m3 = 9.15
        self.anti1_tc_seconds = 40.0
        self.water_model = 'none'
        self.root_bracket = (1e-6, 1e6)

        self.gamma_rock_N_m3 = float(rock_density)
        self.t = float(melt_duration)
        self.hydraulic_conductivity_m_s = float(permeability)
        self.rock_tensile_strength_Pa = float(rock_tensile_strength)
        self.fracture_tensile_strength_Pa = float(fracture_tensile_strength)
        self.alpha = math.radians(float(slope_angle))
        self.beta = math.radians(float(inverse_angle))
        self.sliding_dip_deg = 90 - self.beta
        self.initial_ice_height_m = float(ice_thickness)
        self.slope_height_m = float(slope_height)
        self.strip_width_m = float(bedding_space)
        self.rock_bridge_length_m = max(0.0, float(rock_bridge_length))

        if not (self.t > 0):
            raise ValueError("融冰时长必须大于 0")
        if not (self.slope_height_m > 0):
            raise ValueError("边坡高度必须大于 0")
        if not (self.strip_width_m > 0):
            raise ValueError("层面间隔必须大于 0")
        if not (0 < self.beta < math.pi / 2):
            raise ValueError("反倾角必须在 0~90 度之间")

        self.length = self.slope_height_m / math.cos(self.beta)
        self.crest = self.slope_height_m * math.sin(self.alpha + self.beta) / math.sin(self.alpha)
        self.fracture_flow_area_m2 = 0.005

        edges = np.arange(0, self.length, self.strip_width_m)
        edges = np.r_[edges, self.crest, self.length]
        if 0 < self.rock_bridge_length_m < self.length:
            edges = np.r_[edges, self.rock_bridge_length_m]
        edges = np.sort(edges)
        tolerance = 1e-10 * max(1.0, self.length)
        self.edges = edges[np.r_[True, np.diff(edges) > tolerance]]
        self.edges[0] = 0.0
        self.edges[-1] = self.length
        self.widths = np.diff(self.edges)
        centers = 0.5 * (self.edges[:-1] + self.edges[1:])
        self.bridge_mask = centers < self.rock_bridge_length_m
        self.tensile_strengths = np.where(
            self.bridge_mask,
            self.rock_tensile_strength_Pa,
            self.fracture_tensile_strength_Pa,
        ) * 1e6
        self.fracture_length_m = self.length - self.rock_bridge_length_m
        self.heights = np.maximum(self.rock_surface(self.edges), 0.0)
        self.times = np.linspace(0, self.t * 3600.0, 600)
        self.initial_ice_volume = self.initial_ice_height_m * self.slope_height_m * math.tan(self.beta)
        self.reference_volume = math.pi * 0.15 * 0.0038 ** 2
        self.melt_scale_seconds = self.t * 3600.0

    @staticmethod
    def angle(deg):
        return math.radians(deg)

    def toppling_coefficients(self, layers, alpha_deg, mu, eta):
        alpha = self.angle(alpha_deg)
        coeffs = []
        for layer in layers:
            width, h_right, h_left = layer.width, layer.h_right, layer.h_left
            denominator = 6 * (eta * h_right + mu * width / 2)
            if abs(denominator) < 1e-12:
                raise ValueError("反倾递推分母为零，请检查层面间隔与边坡高度")
            normal = -(layer.rock_weight + layer.ice_weight) * math.cos(alpha) + layer.uplift
            moment = (
                layer.rock_weight * math.sin(alpha) * (h_right + h_left) / 4
                + layer.ice_weight * math.sin(alpha) * (h_right + h_left) / 2
                + layer.uplift * layer.uplift_eccentricity
            )
            c1 = (width * normal + 6 * moment) / denominator
            c2 = width * width * layer.tensile_strength / denominator
            c3 = (eta * h_left - mu * width / 2) / (eta * h_right + mu * width / 2)
            coeffs.append((c1, c2, c3))
        return np.asarray(coeffs)

    def toppling(self, layers, alpha_deg, mu, eta, bracket=(1e-6, 1e6)):
        coeffs = self.toppling_coefficients(layers, alpha_deg, mu, eta)

        def thrust(fs):
            pressure = np.zeros(len(layers) + 1)
            for i in range(len(layers) - 1, -1, -1):
                c1, c2, c3 = coeffs[i]
                pressure[i] = c1 - c2 / fs + c3 * pressure[i + 1]
            return pressure

        lo, hi = bracket
        a = b = 0.0
        for c1, c2, c3 in coeffs[::-1]:
            a, b = c1 + c3 * a, c2 + c3 * b
        if a == 0 and b == 0:
            return np.nan
        if a == 0 or b / a <= 0:
            return np.nan
        if thrust(lo)[0] * thrust(hi)[0] > 0:
            return np.nan
        return float(brentq(lambda fs: thrust(fs)[0], lo, hi, xtol=1e-12, rtol=1e-12))

    def rock_surface(self, s):
        s = np.asarray(s, dtype=float)
        return np.where(
            s <= 0,
            -s / math.tan(self.beta),
            np.where(
                s <= self.crest,
                -s / math.tan(self.alpha + self.beta),
                self.slope_height_m / math.sin(self.beta) - s / math.tan(self.beta),
            ),
        )

    def ice_surface(self, s, h):
        return self.rock_surface(np.asarray(s) - h * math.cos(self.beta)) + h * math.sin(self.beta)

    def ice_history(self):
        if self.initial_ice_volume == 0:
            return np.zeros_like(self.times), np.zeros_like(self.times)
        tau = self.times / self.melt_scale_seconds
        fraction = 1.26 * (np.exp(-0.2 * tau) - np.exp(-3.73 * tau * tau))
        fraction = np.where(tau >= 1, 1.0, np.clip(fraction, 0.0, 1.0))
        melted = self.initial_ice_volume * fraction
        height = self.initial_ice_height_m * (1 - fraction)
        return height, melted

    def _integrate_ice_area(self, left, right, h):
        shift = h * math.cos(self.beta)
        knots = np.r_[left, right, 0.0, self.crest, shift, self.crest + shift]
        knots = np.unique(knots[(knots >= left) & (knots <= right)])
        thickness = self.ice_surface(knots, h) - self.rock_surface(knots)
        return float(np.sum(np.diff(knots) * (
            np.maximum(thickness[:-1], 0.0) + np.maximum(thickness[1:], 0.0)
        ) / 2))

    def water_heads(self, melted_volume, prescribed_heads_m=None):
        shape = (len(self.times), len(self.edges))
        if self.water_model == 'none':
            return np.zeros(shape)
        if self.water_model == 'prescribed':
            return np.asarray(prescribed_heads_m, dtype=float)
        q = (
            (self.gamma_ice_N_m3 / self.gamma_water_N_m3)
            * np.gradient(melted_volume, self.times)
            / self.fracture_flow_area_m2
        )
        heads = np.zeros(shape)
        for k, s in enumerate(self.edges[1:], 1):
            decay = self.hydraulic_conductivity_m_s * math.sin(self.beta) / s
            hydraulic = (
                q - self.hydraulic_conductivity_m_s * math.cos(self.beta)
            ) * np.exp(-decay * self.times)
            heads[1:, k] = np.cumsum(
                -0.5 * (hydraulic[1:] + hydraulic[:-1]) * np.diff(self.times)
            )
        return heads

    def solve(self, prescribed_heads_m=None):
        ice, melted = self.ice_history()
        heads = np.maximum(self.water_heads(melted, prescribed_heads_m), 0.0)
        fos = np.empty(len(self.times), dtype=float)

        for j in range(len(self.times)):
            layers = []
            for i, width in enumerate(self.widths):
                left, right = self.edges[i:i + 2]
                h_right, h_left = self.heights[i:i + 2]
                rock_area = 0.5 * (h_right + h_left) * width
                ice_area = self._integrate_ice_area(left, right, ice[j])
                rock_weight = self.gamma_rock_N_m3 * 1000 * rock_area
                ice_weight = self.gamma_ice_N_m3 * 1000 * ice_area
                pa, pb = self.gamma_water_N_m3 * 1000 * heads[j, i:i + 2]
                uplift = 0.5 * (pa + pb) * width
                moment = width ** 2 * (pb - pa) / 12
                eccentricity = moment / uplift if uplift > 0 else 0.0
                layers.append(Layer(
                    float(width), float(h_right), float(h_left),
                    float(rock_weight), float(ice_weight),
                    float(self.tensile_strengths[i]), float(uplift), float(eccentricity),
                ))
            fos[j] = self.toppling(
                layers, self.sliding_dip_deg, self.mu, self.eta, self.root_bracket
            )
        return self.times, fos

    def run(self):
        _, fos = self.solve()
        flag = 0 if np.any(fos < 1) else 1
        np.savetxt(r'./output_anti_flag.txt', np.array([flag]), fmt='%d')

        t_out, fos_out = downsample_curve(self.times, fos)
        finite = fos[np.isfinite(fos)]
        data = {
            "t": json_values(t_out),
            "fos": json_values(fos_out),
            "minFos": float(np.min(finite)) if finite.size else 1.0,
            "maxFos": float(np.max(finite)) if finite.size else 1.0,
            "iceVolume": float(self.initial_ice_volume),
            "debrisVolume": float(self.initial_ice_volume * 3.0),
        }
        os.makedirs('src/main/resources/static', exist_ok=True)
        with open(r'src/main/resources/static/output_anti.txt', 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)
        print("RESULT_JSON=" + json.dumps(data, ensure_ascii=False))


if __name__ == '__main__':
    if len(sys.argv) < 11:
        raise SystemExit("参数不足：需要至少 10 个参数")
    params = {
        "melt_duration": float(sys.argv[1]),
        "slope_angle": float(sys.argv[2]),
        "inverse_angle": float(sys.argv[3]),
        "ice_thickness": float(sys.argv[4]),
        "slope_height": float(sys.argv[5]),
        "bedding_space": float(sys.argv[6]),
        "fracture_tensile_strength": float(sys.argv[7]),
        "rock_tensile_strength": float(sys.argv[8]),
        "rock_density": float(sys.argv[9]),
        "permeability": float(sys.argv[10]),
        "rock_bridge_length": float(sys.argv[11]) if len(sys.argv) > 11 else 0.0,
    }
    Anti(**params).run()
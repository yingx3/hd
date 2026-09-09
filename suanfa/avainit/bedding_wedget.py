import numpy as np
import math
import sys
import json
import os


class wedge:
    def __init__(self, melt_duration, slope_angle, normal_vector, ice_thickness,
                 square, slope_height, fracture, cohesion, friction_angle,
                 rock_density, permeability):
        # normal_vector: 逗号分隔三维向量，如 "1,2,1.3"
        try:
            self.n1 = np.array([float(i) for i in str(normal_vector).split(',')])
        except Exception:
            raise ValueError("normal_vector 需为逗号分隔的三维向量，例：1,2,1.3")
        if len(self.n1) != 3:
            raise ValueError("normal_vector 需为三维向量，例：1,2,1.3")
        self.n2 = np.array([-self.n1[0], self.n1[1], self.n1[2]])
        self.hi = float(ice_thickness)
        self.hs = float(slope_height)
        self.a1 = float(square)
        self.a2 = self.a1
        self.t_total = float(melt_duration)
        self.alpha = float(slope_angle)
        self.c_ = float(cohesion)
        self.theta_ = float(friction_angle)
        self.rs = float(rock_density)
        self.Ks = float(permeability)
        self.ratio = float(fracture)
        self.rw = 10.0
        self.ri = 9.15
        self.tc = 40.0
        self.v0 = math.pi * 0.15 * 0.0038 ** 2
        self.t = np.linspace(0, self.t_total * 3600, 240)

        z = np.array([0, 0, 1])
        N1, N2 = self.unit(self.n1), self.unit(self.n2)
        l = np.cross(N1, N2)
        d = self.unit(l)
        if d[2] > 0:
            d = -d
        self.omga = np.radians(180 - np.degrees(np.arccos(np.dot(d, z))))
        self.norm_d = np.array([0, np.cos(self.omga), np.sin(self.omga)])
        self.d0 = abs(self.hs * np.tan(self.omga))
        d2 = abs(self.hs / np.tan(np.radians(self.alpha)))
        d1 = self.d0 - d2
        ls = 2 * (abs(self.a1 * np.dot(N1, z)) + abs(self.a2 * np.dot(N2, z))) / self.d0
        self.s1 = 0.5 * ls * d1
        self.s2 = 0.5 * ls * d2 / np.cos(np.radians(self.alpha))
        self.vf = self.hi * (self.s1 + np.cos(np.radians(self.alpha)) * self.s2)
        self.ro = self.vf / self.v0 if self.v0 else 0.0
        self.ll = np.sqrt(np.square(self.d0) + np.square(self.hs))
        self.l1 = np.sqrt(np.square(0.5 * ls) + np.square(d1))
        self.af = self.a1 * (1 - np.square(self.ratio))
        self.hf = 2 * self.af / ((1 + self.ratio) * self.l1)
        self.sb = np.square(self.ratio)
        self.sbz = abs(self.sb * np.dot(N1, z))
        self.Af = 0.005

    def unit(self, N):
        n = np.linalg.norm(N)
        if n == 0:
            raise ValueError("zero vector")
        return N / n

    def volume(self, t):
        v = 1.26 * self.vf * (np.e ** (-0.2 * (t / (self.ro * self.tc))) - np.e ** (-3.73 * np.square(t / (self.ro * self.tc))))
        return np.maximum(v, 0)

    def H(self, t):
        Lambda = self.Ks / self.sb
        v = self.volume(t)
        df = np.gradient(v, t[1] - t[0])
        q = self.ri * df / (self.Af * self.l1 * 2 * self.rw)
        h = (q - self.Ks * self.sbz / self.sb) * np.exp(-Lambda * t)
        return h

    def factor_of_safety(self, t):
        v = self.volume(t)
        h = (self.vf - v) / (self.s1 + np.cos(np.radians(self.alpha)) * self.s2)
        mass = self.rs * self.s1 * self.hs / 3.0
        HH = -self.H(t)
        ht = (t[1] - t[0]) * np.array(np.cumsum(HH, 0))
        wp = self.rw * ht * self.hf * (1 + 2 * self.ratio) * self.l1 / 6.0 + self.rw * ht * self.sb / 3.0
        drive = (mass + self.ri * h) * np.cos(self.omga)
        resis = (mass + self.ri * h) * np.sin(self.omga) * np.tan(np.radians(self.theta_)) + 2 * self.c_ * (self.a1 - self.af) - 2 * wp * np.dot(self.n1, self.norm_d)
        drive = np.maximum(drive, 1e-8)
        fos = resis / drive
        return fos

    def run(self):
        t = self.t
        if self.ratio < 0.05 or self.ratio > 1:
            print("RESULT_JSON=" + json.dumps({"error": "Ra(裂隙比例)需在0.05~1之间"}, ensure_ascii=False))
            return
        if 90 - np.degrees(self.omga) - self.alpha >= 0:
            print("RESULT_JSON=" + json.dumps({"error": "楔体滑动倾角(%.1f°) > 边坡倾角(%.1f°)，请输入正确的法向量或增大边坡倾角" % (np.round(90 - np.degrees(self.omga), 1), self.alpha)}, ensure_ascii=False))
            return
        fos = self.factor_of_safety(t)
        data = {"t": t.tolist(), "fos": fos.tolist()}
        os.makedirs('src/main/resources/static', exist_ok=True)
        with open(r'src/main/resources/static/output_wedge.txt', 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)
        print("RESULT_JSON=" + json.dumps(data, ensure_ascii=False))


if __name__ == '__main__':
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
    }
    model = wedge(**params)
    model.run()

import numpy as np
import math
import matplotlib
matplotlib.use('TkAgg')
import matplotlib.pyplot as plt
import sys

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
        self.t = np.linspace(0, self.t_total * 3600, self.t_total * 600)

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
        x = np.linspace(-self.L0 * self.c1 / self.k2, 0, 10)
        v = self.volume(t)
        h_ice = (self.vf - v) / (self.L0 * self.c1)

        surf1 = self.slope_surface(x)
        surf2 = self.slope_base(x)
        dx = x[1] - x[0]

        # 滑体重量
        m = self.rs * (dx * np.sum(surf1) - dx * np.sum(surf2)) + self.ri * h_ice * self.L0 * self.c1
        HH = -self.H(t, v)
        ht = dx * np.cumsum(HH, axis=0)

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


        # 保存结果到txt
        result = 0 if np.any(fos < 1) else 1
        np.savetxt(r'.\output_bedding.txt', np.array([result]))

        # 绘图
        fig, ax = plt.subplots(layout='constrained')
        ax.plot(t / (3600 * 24), fos, 'b', label='FOS')
        ax.set_xlabel('t (days)')
        ax.set_ylabel('Factor Of Safety')
        ax.set_xlim(left=0)
        ax.set_ylim(bottom=np.min(fos))
        plt.legend()
        plt.title('Bedding Slope Stability - Factor of Safety')
        plt.show()
        # 打印 t 数组
        print("t_array:", t)
    # 打印 fos 数组
        print("fos_array:", fos)
        print("result:",result)

# ===================== 直接运行 =====================
if __name__ == '__main__':
    params = {
        "slope_angle": int(sys.argv[1]),
        "slide_angle": int(sys.argv[2]),
        "cohesion": int(sys.argv[3]),
        "friction_angle": int(sys.argv[4]),
        "rock_density": int(sys.argv[5]),
        "permeability": float(sys.argv[6]),
        "ice_thickness": int(sys.argv[7]),
        "fissure_height": int(sys.argv[8]),
        "melt_duration": int(sys.argv[9]),
        "slide_length": int(sys.argv[10])
    }

    model = bedding(**params)
    model.run()
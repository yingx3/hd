import numpy as np
import math
import matplotlib
matplotlib.use('TkAgg')
import matplotlib.pyplot as plt
import sys
import json
import os  # 新增：用于路径处理

class Anti():  # 类名规范：首字母大写
    def __init__(self, melt_duration, slope_angle, inverse_angle, ice_thickness, slope_height,bedding_space, cohesion, friction_angle, rock_density, permeability):
        self.t_total = melt_duration  # 总融化时长（重命名避免与时间数组冲突）
        self.alpha = slope_angle
        self.beta = inverse_angle
        self.hi = ice_thickness
        self.hs = slope_height
        self.c_ = cohesion
        self.theta_ = friction_angle
        self.rs = rock_density
        self.Ks = permeability
        self.b = bedding_space
        self.rw = 10.00
        self.ri = 9.15
        self.tc = 40
        self.v0 = math.pi * 0.15 * (0.0038 ** 2)
        self.vf = self.hi * self.hs * np.tan(np.radians(self.beta))
        self.ro = self.vf / self.v0
        self.L0 = self.hs / np.sin(np.radians(90 - self.beta))
        self.s1 = np.sin(np.radians(90 - self.beta))
        self.Af = 0.005

        # 生成均匀时间数组（核心修复：原代码无时间数组）
        self.t = np.linspace(0, self.t_total, 100)  # 0~总时长，100个点

    # 反倾边坡坡面函数（修复：标量/数组兼容，三角函数计算）
    def anti_inclined_slope(self, x):
        beta_rad = np.radians(self.beta)
        alpha_rad = np.radians(self.alpha)
        alpha_beta_rad = np.radians(self.alpha + self.beta)

        # 分段计算（向量化，兼容数组输入）
        y = np.zeros_like(x, dtype=float)
        cond1 = x <= 0
        y[cond1] = -x[cond1] * self.b / np.tan(beta_rad)

        x_threshold = self.hs * np.sin(alpha_beta_rad) / (self.b * np.sin(alpha_rad))
        cond2 = (x > 0) & (x <= x_threshold)
        y[cond2] = -x[cond2] * self.b / np.tan(alpha_beta_rad)

        cond3 = x >= x_threshold
        y[cond3] = self.hs / np.sin(beta_rad) - x[cond3] * self.b / np.tan(beta_rad)
        return y

    # 冰层表面函数（修复：h.all()错误、三角函数、参数冗余）
    # def ice_surface(self, x, h):
    #     alpha_rad = np.radians(self.alpha)
    #     beta_rad = np.radians(self.beta)
    #     alpha_beta_rad = np.radians(self.alpha + self.beta)
    #
    #     y = np.zeros_like(x, dtype=float)
    #     x1 = h * np.cos(beta_rad)
    #     cond1 = x <= x1
    #     term1 = h * (np.sin(beta_rad) - np.cos(beta_rad)/np.tan(alpha_rad))
    #     y[cond1] = -x[cond1] * self.b / np.tan(alpha_rad) + term1
    #
    #     x_threshold = self.hs * np.sin(alpha_beta_rad) / (self.b * np.sin(alpha_rad))
    #     x2 = x1 + x_threshold
    #     cond2 = (x > x1) & (x <= x2)
    #     term2 = h * (np.sin(beta_rad) - np.cos(beta_rad)/np.tan(alpha_beta_rad))
    #     y[cond2] = -x[cond2] * self.b / np.tan(alpha_beta_rad) + term2
    #
    #     cond3 = x >= x2
    #     term3 = h * (np.sin(beta_rad) - np.cos(beta_rad)/np.tan(beta_rad))
    #     y[cond3] = self.hs/np.sin(beta_rad) - x[cond3]*self.b/np.tan(alpha_rad) + term3
    #     return y
    def ice_surface(self, x, h):
        alpha_rad = np.radians(self.alpha)
        beta_rad = np.radians(self.beta)
        alpha_beta_rad = np.radians(self.alpha + self.beta)

        h = np.asarray(h, dtype=float)
        x_arr = np.full_like(h, x, dtype=float)   # 把标量x扩展成与h同形状数组
        y = np.zeros_like(h, dtype=float)

        x1 = h * np.cos(beta_rad)
        cond1 = x_arr <= x1
        term1 = h * (np.sin(beta_rad) - np.cos(beta_rad) / np.tan(alpha_rad))
        y[cond1] = -x_arr[cond1] * self.b / np.tan(alpha_rad) + term1[cond1]

        x_threshold = self.hs * np.sin(alpha_beta_rad) / (self.b * np.sin(alpha_rad))
        x2 = x1 + x_threshold
        cond2 = (x_arr > x1) & (x_arr <= x2)
        term2 = h * (np.sin(beta_rad) - np.cos(beta_rad) / np.tan(alpha_beta_rad))
        y[cond2] = -x_arr[cond2] * self.b / np.tan(alpha_beta_rad) + term2[cond2]

        cond3 = x_arr >= x2
        term3 = h * (np.sin(beta_rad) - np.cos(beta_rad) / np.tan(beta_rad))
        y[cond3] = self.hs / np.sin(beta_rad) - x_arr[cond3] * self.b / np.tan(alpha_rad) + term3[cond3]

        return y


    # 融冰体积函数（修复：指数计算）
    def volume(self, t):
        t_ratio = t / (self.ro * self.tc)
        v = 1.26 * self.vf * (np.exp(-0.2 * t_ratio) - np.exp(-3.73 * (t_ratio ** 2)))
        # 限制体积非负
        v = np.maximum(v, 0)
        return v

    # 融冰水头计算（修复：梯度计算、逻辑、数组维度）
    def H(self, t, l):
        if l <= 0:
            return np.zeros_like(t)
        Lambda = self.Ks * np.sin(np.radians(self.beta)) / l
        v = self.volume(t)
        dt = t[1] - t[0]
        df = np.gradient(v, dt)
        q = self.ri * df / (self.Af * self.rw)
        h = (q - self.Ks * self.s1) * np.exp(-Lambda * t)
        return h

    # 安全系数计算（核心修复：循环逻辑、水头计算、除零保护）
    def factor_of_safety(self):
        t = self.t
        L = self.hs / np.cos(np.radians(self.beta))
        n = max(int(L / self.b), 1)  # 至少1个结构体
        v = self.volume(t)
        # 实时冰层厚度
        h = (self.vf - v) / (self.hs * np.tan(np.radians(self.beta)))
        h = np.maximum(h, 0)  # 厚度非负

        r_total = np.zeros_like(t)
        d_total = np.zeros_like(t)

        for i in range(n):
            x_i = i * self.b
            x_i1 = (i + 1) * self.b

            # 坡面高度
            h1 = self.anti_inclined_slope(np.array([x_i]))[0]
            h2 = self.anti_inclined_slope(np.array([x_i1]))[0]

            # 冰层高度
            # h_ice1 = self.ice_surface(np.array([x_i]), h) - h1
            # h_ice2 = self.ice_surface(np.array([x_i1]), h) - h2
            h_ice1 = self.ice_surface(x_i, h) - h1
            h_ice2 = self.ice_surface(x_i1, h) - h2

            h_ice1 = np.maximum(h_ice1, 0)
            h_ice2 = np.maximum(h_ice2, 0)

            # 融冰水头
            hh1 = self.H(t, x_i)
            hh2 = self.H(t, x_i1)
            dt = t[1] - t[0]
            ht1 = dt * np.cumsum(hh1, axis=0)
            ht2 = dt * np.cumsum(hh2, axis=0)

            # 抗滑力 & 下滑力
            beta_rad = np.radians(self.beta)
            theta_rad = np.radians(self.theta_)

            # 抗滑力
            term_r1 = 0.5 * self.rw * (ht1 ** 2) * np.sin(beta_rad)
            term_r2 = self.c_ * self.b
            term_r3 = (self.rs*0.5*(h1+h2)*self.b + self.ri*0.5*(h_ice1+h_ice2)*self.b
                       - 0.5*self.rw*(ht1+ht2)*self.b) * np.sin(beta_rad) * np.tan(theta_rad)
            r_total += term_r1 + term_r2 + term_r3

            # 下滑力
            term_d1 = (self.rs*0.5*(h1+h2)*self.b + self.ri*0.5*(h_ice1+h_ice2)*self.b) * np.cos(beta_rad)
            term_d2 = 0.5 * self.rw * (ht2 ** 2) * np.sin(beta_rad)
            d_total += term_d1 + term_d2

        # 安全系数（除零保护）
        d_total = np.maximum(d_total, 1e-8)
        fos = r_total / d_total
        return fos

    # 运行主函数（修复：tkinter缺失、路径、输出逻辑）
    def run(self):
        # 参数校验
        if self.alpha + self.beta < 90:
            print("t_total=", self.t_total,"alpha=", self.alpha,
                  "beta=", self.beta,
                  "hi=", self.hi,
                  "hs=", self.hs,
                  "b=", self.b,
                  "c_=", self.c_,
                  "theta_=", self.theta_,
                  "rs=", self.rs,
                  "Ks=", self.Ks,
                  )
            print("ERROR: 坡度角+反倾角必须≥90°")
            return

        # 计算安全系数
        fos = self.factor_of_safety()

        # 输出标记：1=稳定，0=失稳
        flag = 0 if np.any(fos < 1) else 1
        np.savetxt(r'./output_anti_flag.txt', np.array([flag]), fmt='%d')

        # 输出JSON数据
        data = {
            "t": self.t.tolist(),
            "fos": fos.tolist()
        }
        # 确保目录存在
        os.makedirs('src/main/resources/static', exist_ok=True)
        with open(r'src/main/resources/static/output_anti.txt', 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False)

        print("FILE_SAVED: output_anti.txt")

        # 绘图
        # fig, ax = plt.subplots(figsize=(8, 4), layout='constrained')
        # ax.plot(self.t / (3600 * 24), fos, 'b-', linewidth=2, label='Safety Factor')
        # ax.axhline(y=1, color='r', linestyle='--', label='Critical Value (1.0)')
        # ax.set_xlabel('Time (days)', fontsize=12)
        # ax.set_ylabel('Factor Of Safety', fontsize=12)
        # ax.set_xlim(left=0)
        # ax.set_ylim(bottom=np.min(fos)-0.1)
        # ax.grid(alpha=0.3)
        # ax.legend()
        # plt.savefig('./anti_slope_fos.png', dpi=300, bbox_inches='tight')
        # plt.close()

if __name__ == '__main__':
    # 命令行参数读取（兼容浮点数）
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

    model = Anti(**params)
    model.run()
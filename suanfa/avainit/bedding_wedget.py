import numpy as np
import math
import matplotlib
matplotlib.use('TkAgg')
import matplotlib.pyplot as plt
import sys
import json

class wedge():
    def __init__(self,n1, hi, hs, a, t, alpha, c_, theta_, Ks, rs, ratio):  #初始函数获取计算参数 n1:楔形体单侧底面法向量, hi:冰层厚度, hs:裂隙高度, a:楔形体单侧底面面积, t:时间, alpha:坡度角, c_:有效粘聚力, theta_:有效内摩擦角, Ks:饱和岩体渗透系数, rs:饱和岩体重度, ratio:裂隙长度与楔体底部中线长度比值
        self.ratio=float(ratio.get())  #裂隙长度与楔体底部中线长度比值
        if len(n1.get().split(','))!=3:  #判断输入的向量格式是否正确
            tk.messagebox.showerror(title='提示', message='输入向量不正确，请在英文状态下输入（例：1,2,1.3）')
        self.n1 = np.array([float(i) for i in tuple(n1.get().split(','))])  #楔形体单侧底面法向量
        self.n2 = np.array([-self.n1[0], self.n1[1], self.n1[2]])  #楔形体单侧底面法向量
        self.hi=float(hi.get())  #冰层厚度
        self.hs=float(hs.get())  #裂隙高度
        self.a1=float(a.get())  #楔形体单侧底面面积
        self.a2 =  self.a1  #楔形体单侧底面面积
        self.t = np.linspace(0, float(t.get()) * 3600, round(float(t.get())) * 600)  #时间参数
        self.alpha = float(alpha.get())  #坡度角
        self.c_=float(c_.get())  #有效粘聚力
        self.theta_=float(theta_.get())  #有效内摩擦角
        self.rs=float(rs.get())  #饱和岩体重度
        self.Ks=float(Ks.get())  #饱和岩体渗透系数
        self.rw = 10  #水的重度
        self.ri = 9.15  #冰的重度
        self.tc = 40  #标准冰棱柱体的融冰时长
        self.v0 = math.pi * 0.15 * 0.0038 ** 2  #标准冰棱柱体的体积
        z = np.array([0, 0, 1])  #竖直向上的向量
        N1, N2 = self.unit(self.n1), self.unit(self.n2)  #楔形体两侧底面法向量的单位向量
        l = np.cross(N1, N2)  #楔形体两侧底面交线的向量
        d = self.unit(l)  #楔形体两侧底面交线向量的单位向量
        if d[2] > 0:  #判断交线向量的方向
            d = -d  #设置交线向量指向滑动方向
        self.omga = np.radians(180-np.degrees(np.arccos(np.dot(d, z))))  #交线向向量与Z轴的夹角
        self.norm_d = np.array([0, np.cos(self.omga), np.sin(self.omga)])  #与交线向量垂直的向量
        self.d0 = abs(self.hs * np.tan(self.omga))  #交线向量在水平面上的投影长度
        d2 = abs(self.hs / np.tan(np.radians(self.alpha)))  #坡面中线在水平面上的投影长度
        d1 = self.d0 - d2  #交线向量与坡面中线在水平面上的投影长度差值
        ls = 2 * (abs(self.a1 * np.dot(N1, z)) + abs(self.a2 * np.dot(N2, z))) / self.d0  #楔形体与披肩相交的线段长度
        self.s1 = 0.5 * ls * d1  #楔形体与坡度相交处在水平面上的面积
        self.s2 = 0.5 * ls * d2 / np.cos(np.radians(self.alpha))  #楔形体与斜坡面相交处在水平面上的投影面积
        self.vf = self.hi * (self.s1 + np.cos(np.radians(self.alpha)) * self.s2)  #楔形体上的冰层体积
        self.ro = self.vf / self.v0  #换算系数
        self.ll=np.sqrt(np.square(self.d0)+np.square(self.hs))  #交线向量的长度
        self.l1=np.sqrt(np.square(0.5*ls)+np.square(d1))  #楔形体与坡顶相交形成的等腰三角形腰长
        self.af=self.a1*(1-np.square(self.ratio))  #岩桥面积
        self.hf=2*self.af/((1+self.ratio)*self.l1)  #裂隙长度在Z轴上的投影长度
        self.sb=np.square(self.ratio)
        self.sbz=abs(self.sb*np.dot(N1, z))  #裂隙面积比在数值方向上的投影
        self.Af=0.005  #裂隙面积

    #融冰过程中冰体积随时间变化的函数
    def volume(self, t):  #t:时间参数
        v = 1.26 * self.vf * (np.e**(-0.2 * (t / (self.ro*self.tc))) - np.e**(-3.73 * np.square(t / (self.ro*self.tc))))
        return v  #返回融冰体积

    #计算融冰水流强度随时间变化的函数
    def H(self, t):  #t:时间参数
        Lambda = self.Ks/self.sb
        v = self.volume(t)  #调用体积函数求解融冰体积
        df = np.gradient(v, t[1]-t[0])  #求解体积变化的速率
        q = self.ri * df / (self.Af*self.l1*2 * self.rw)
        h = (q - self.Ks * self.sbz/self.sb) * np.exp(-Lambda * t)  #求解融冰水水流强度
        return h  #输出不同时刻融冰水的水流强度

    #将普通向量转换为单位向量的函数
    def unit(self, N):  #N:向量
        n = np.linalg.norm(N)  #计算向量的模长
        if n == 0:
            raise ValueError("zero vector")
        return N / n  #将向量转化为模长为一的单位向量

    #计算安全系数的函数
    def factor_of_safety(self, t):
        v = self.volume(t)  #调用体积函数求解融冰体积
        h = (self.vf - v) / (self.s1 + np.cos(np.radians(self.alpha)) * self.s2)  #计算冰层厚度
        mass = self.rs * self.s1 * self.hs/3  #计算楔形滑体的重力
        HH = -self.H(t)  #调用函数计算融冰水水流强度
        ht = (t[1] - t[0]) * np.array(np.cumsum(HH, 0))  #计算不同时刻裂隙中融冰水水头高度
        wp = self.rw*ht*self.hf*(1+2*self.ratio)*self.l1/6 + self.rw*ht*self.sb/3  #计算不同时刻裂隙中的孔隙水压力
        drive = (mass + self.ri*h) * np.cos(self.omga)  #计算不同时刻的下滑力
        resis = (mass + self.ri*h) * np.sin(self.omga)*np.tan(np.radians(self.theta_)) + 2*self.c_*(self.a1-self.af) - 2*wp*np.dot(self.n1, self.norm_d)  #计算不同时刻的抗滑力
        fos = resis/drive  #计算不同时刻的安全系数
        return fos  #输出不同时刻的安全系数

    #执行运算的函数
    def run(self):
        t, ratio, omga, alpha=self.t, self.ratio, self.omga, self.alpha  #时间, 裂隙与楔形滑体中线长度比值, 楔体中线与竖向的夹角, 坡面倾角
        if ratio == 0 or ratio < 0.05 or ratio > 1:  #判断输入的比例参数是否符合计算要求
            tk.messagebox.showwarning(title='提示', message='Ra取值过小或过大，请在0.05至1范围内取值')
        elif 90-np.degrees(omga)-alpha>=0:
            tk.messagebox.showwarning(title='提示', message=f'楔体滑动倾角({np.round(90-np.degrees(omga),1)}°)>边坡倾角({alpha}°)，请输入正确的法向量或增大边坡倾角')
        else:
            fos = self.factor_of_safety(t)  #调用函数计算安全系数
            if np.any(fos < 1):  # 判断是否存在安全系数小于1
                # 满足存在安全系数小于1的条件输出0
                np.savetxt(r'.\output_wedge.txt', (np.array([0])))
            else:
                # 不满足存在安全系数小于1的条件输出1
                np.savetxt(r'.\output_wedge.txt', np.array([1]))

                # 写入文件：路径 .\output_bedding.txt，自动覆写
            with open(r'src/main/resources/static/output_wedge.txt', 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False)

            # 只输出一行标记（Java 可以用来判断是否完成）
            print("FILE_SAVED: output_wedge.txt")
            fig, ax = plt.subplots(layout='constrained')  #创建画布
            ax.plot(t / (3600 * 24), fos, 'b', label='FOS')  #绘制安全系数图
            ax.set_xlabel('t (days)')  #设置x轴名称
            ax.set_ylabel('Facter Of Safety')  #设置y轴名称
            ax.set_ylim(np.min(fos))  #设置y轴最小值
            ax.set_xlim(0)  #设置x轴最小值
            plt.legend()  #绘制图例
            # plt.show()  #显示安全系数图片
if __name__ == '__main__':
    params = {
        "slope_angle": int(sys.argv[1]),
        "normal_vector": int(sys.argv[2]),
        "cohesion": int(sys.argv[3]),
        "friction_angle": int(sys.argv[4]),
        "rock_density": int(sys.argv[5]),
        "permeability": float(sys.argv[6]),
        "ice_thickness": int(sys.argv[7]),
        "slope_height": int(sys.argv[8]),
        "melt_duration": int(sys.argv[9]),
        "square": int(sys.argv[10]),
        "fracture": int(sys.argv[10]),
    }

    model = wedge(**params)
    model.run()
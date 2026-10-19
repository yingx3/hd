"""Python port of ``main.m`` (faithful translation)."""
import os
import math
import numpy as np
np.seterr(divide="ignore", invalid="ignore")  # dry-cell 0/0: MATLAB silently yields Inf/NaN then zeroes them

import global_state as g
from doput import doput
from process import process
from solver import (
    Init,
    parameters,
    time as time_step,
    spcro,
    spcrp,
    velocity,
)


def main(userName, taskName, sufB, sufL, sufW, sufP, output_fn=None):
    print("start")
    # 输出回调：默认写 txt，Pro 链路传入回调直接写 ASC 帧
    write = output_fn or doput

    g.outputIndex = 1
    g.basePath = os.path.join(userName, taskName) + os.sep

    file_name_B = os.path.join(g.basePath, sufB + ".txt")
    file_name_L = os.path.join(g.basePath, sufL + ".txt")
    file_name_W = os.path.join(g.basePath, sufW + ".txt")
    file_name_P = os.path.join(g.basePath, sufP + ".txt")

    zB = np.loadtxt(file_name_B)
    zL = np.loadtxt(file_name_L)
    hW = np.loadtxt(file_name_W)
    Par = np.loadtxt(file_name_P)

    Uw, Us = Init(zB, zL, hW)
    parameters(Par)

    k = 1
    T = [0.0]
    # 收敛判据需要两层同时接近静止，并连续保持若干步；
    # 原判据 ``max(A)>0 且 max(B)<1`` 会在泥石流层仍在运动时被水层触发而提前退出。
    still_steps = 0
    started = False
    # 速度阈值（m/s）：低于它视为静止。用 1 m/s 会把缓慢启动的工况误判为"未启动"。
    still_speed = 0.02
    still_steps_limit = 10

    while max(T) < Par[7]:

        dt = time_step(Uw, Us)
        T.append(T[k - 1] + dt)

        Uw, Us = spcro(Uw, Us, dt)
        Uw, Us = spcrp(Uw, Us, dt)

        A, B = velocity(Us, Uw)

        process(T, Par[4])

        k += 1

        t1 = math.floor(T[k - 2] / g.Interval)
        t2 = math.floor(T[k - 1] / g.Interval)

        if T[k - 1] - T[k - 2] > g.Interval or t2 != t1:
            if output_fn is None:
                write(Uw, Us)
            else:
                # 自定义回调带上当前模拟时刻，便于按时间抽帧（doput 路径行为不变）
                output_fn(Uw, Us, T[k - 1])

        # velocity() 返回 (水相速度, 固相速度)；固相从静止起步，
        # 必须先确认流动已经启动，之后才允许用"连续若干步静止"判定收敛，
        # 否则起步阶段速度尚未越过 1 m/s 就会被误判为已停止。
        water_speed = float(np.max(A)) if A.size else 0.0
        solid_speed = float(np.max(B)) if B.size else 0.0
        max_speed = max(water_speed, solid_speed)
        if max_speed >= still_speed:
            started = True
            still_steps = 0
        else:
            still_steps += 1
            if still_steps >= still_steps_limit:
                # 已启动过 => 流动收敛；从未启动 => 跑到 1/4 时长后放弃
                if started or max(T) > max(30.0, 0.25 * Par[7]):
                    break

    print("end")

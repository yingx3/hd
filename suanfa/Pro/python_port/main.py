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

        if T[k - 1] - T[k - 2] > g.Interval:
            write(Uw, Us)
        else:
            if t2 != t1:
                write(Uw, Us)

        if bool(np.max(A)) and (np.max(B) < 1):
            break

    print("end")

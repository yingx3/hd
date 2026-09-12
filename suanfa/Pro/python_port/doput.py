"""Python port of ``doput.m`` (faithful translation).

Writes ``depth1/depth2/speed1/speed2/<index>.txt`` for one output step.
"""
import os
import numpy as np

import global_state as g


def doput(Uw, Us):
    hw = Uw[:, :, 0]
    uw = Uw[:, :, 1] / Uw[:, :, 0]
    vw = Uw[:, :, 2] / Uw[:, :, 0]

    hs = Us[:, :, 0]
    us = Us[:, :, 1] / Us[:, :, 0]
    vs = Us[:, :, 2] / Us[:, :, 0]

    I = hw <= g.db
    uw[I] = 0.0
    vw[I] = 0.0
    I = hs <= g.db
    us[I] = 0.0
    vs[I] = 0.0

    A = np.sqrt(us ** 2 + vs ** 2)
    B = np.sqrt(uw ** 2 + vw ** 2)

    index = str(g.outputIndex)

    def write_matrix(folder, M):
        path = os.path.join(g.basePath, folder, index + ".txt")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            for i in range(g.n):
                for j in range(g.m):
                    if j == g.m - 1:
                        f.write("%.4f \n" % M[i, j])
                    else:
                        f.write("%.4f " % M[i, j])

    write_matrix("depth1", hs)
    write_matrix("depth2", hw)
    write_matrix("speed1", A)
    write_matrix("speed2", B)

    g.outputIndex += 1

"""Python translation of the MATLAB numerical core (the former *.p files).

All functions are faithful 1:1 ports of the corresponding .m source now
present in the project root.
"""
import numpy as np

import global_state as g


# --------------------------------------------------------------------------- #
# initialisation / parameters
# --------------------------------------------------------------------------- #
def Init(zB, zL, hW):
    g.hG = hW.copy()
    hS = zB - zL
    g.z = zL.copy()

    n, m = g.z.shape
    g.n = n
    g.m = m

    Us = np.zeros((n, m, 3))
    Us[:, :, 0] = hS

    Uw = np.zeros((n, m, 3))
    Uw[:, :, 0] = hW

    X, Y = np.meshgrid(np.arange(1, m + 1), np.arange(1, n + 1))
    g.x = X
    g.y = Y

    return Uw, Us


def parameters(Par):
    g.bed = Par[0]
    g.nn = Par[1]
    g.dx = Par[2]
    g.dy = Par[3]
    rous = Par[4]
    rouf = Par[5]
    g.r = rouf / rous
    g.Interval = Par[6]
    g.g = 9.8
    g.tol = 0.5
    g.db = 0.0


# --------------------------------------------------------------------------- #
# time step / velocity
# --------------------------------------------------------------------------- #
def time(Uw, Us):
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

    cw = np.sqrt(g.g * hw)
    cs = np.sqrt(g.g * hs)
    uW = np.sqrt(uw ** 2 + vw ** 2)
    uS = np.sqrt(us ** 2 + vs ** 2)

    c = np.maximum(cw, cs)
    u = np.maximum(uW, uS)

    kmax = max(0.0, float(np.max(u + c)))
    dt = g.tol * g.dx * g.dy / (2.0 * kmax * (g.dx + g.dy))
    return dt


def velocity(Us, Uw):
    hw = Uw[:, :, 0]
    uw = Uw[:, :, 1] / Uw[:, :, 0]
    vw = Uw[:, :, 2] / Uw[:, :, 0]
    hs = Us[:, :, 0]
    us = Us[:, :, 1] / Us[:, :, 0]
    vs = Us[:, :, 2] / Us[:, :, 0]

    I = hw <= 0
    uw[I] = 0.0
    vw[I] = 0.0
    I = hs <= 0
    us[I] = 0.0
    vs[I] = 0.0

    A = np.sqrt(uw ** 2 + vw ** 2)
    B = np.sqrt(us ** 2 + vs ** 2)
    return A, B


# --------------------------------------------------------------------------- #
# split space operators
# --------------------------------------------------------------------------- #
def spcro(Uw, Us, dt):
    Uw, Us = area_x(Uw, Us, dt)
    Uw, Us = x_stps(Uw, Us, dt)
    return Uw, Us


def spcrp(Uw, Us, dt):
    Uw, Us = area_y(Uw, Us, dt)
    Uw, Us = y_stps(Uw, Us, dt)
    return Uw, Us


# --------------------------------------------------------------------------- #
# boundary conditions (zeroth-order extrapolation at the edges)
# --------------------------------------------------------------------------- #
def x_boundary(Q):
    Q_1 = Q[0:1, :, :].copy()
    Q_2 = Q[1:2, :, :].copy()
    Q_n1 = Q[g.n - 1:g.n, :, :].copy()
    Q_n2 = Q[g.n - 2:g.n - 1, :, :].copy()
    return Q_1, Q_2, Q_n1, Q_n2


def y_boundary(Q):
    Q_1 = Q[:, 0:1, :].copy()
    Q_2 = Q[:, 1:2, :].copy()
    Q_m1 = Q[:, g.m - 1:g.m, :].copy()
    Q_m2 = Q[:, g.m - 2:g.m - 1, :].copy()
    return Q_1, Q_2, Q_m1, Q_m2


# --------------------------------------------------------------------------- #
# HLLC Riemann solvers
# --------------------------------------------------------------------------- #
def x_HLLC(hL, uL, vL, hR, uR, vR):
    F = np.zeros((hL.shape[0], hL.shape[1], 3))

    with np.errstate(divide="ignore", invalid="ignore"):
        ug = 0.5 * (uL + uR) + np.sqrt(g.g * hL) - np.sqrt(g.g * hR)
        hg = (0.5 * (np.sqrt(g.g * hL) + np.sqrt(g.g * hR)) + 0.25 * (uL - uR)) ** 2 / g.g

        cmL = np.sqrt(g.g * hL)
        cmR = np.sqrt(g.g * hR)
        cmg = np.sqrt(g.g * hg)

        sL = np.minimum(uL - cmL, ug - cmg)
        sR = np.maximum(uR + cmR, ug + cmg)

        I = (hL <= g.db) & (hR > g.db)
        sL[I] = uR[I] - 2.0 * np.sqrt(g.g * hR[I])
        sR[I] = uR[I] + np.sqrt(g.g * hR[I])

        I = (hR <= g.db) & (hL > g.db)
        sR[I] = uL[I] + 2.0 * np.sqrt(g.g * hL[I])
        sL[I] = uL[I] - np.sqrt(g.g * hL[I])

        sG = (sL * hR * (uR - sR) - sR * hL * (uL - sL)) / (hR * (uR - sR) - hL * (uL - sL))
        sG[(hL <= g.db) & (hR > g.db)] = sL[(hL <= g.db) & (hR > g.db)]
        sG[(hR <= g.db) & (hL > g.db)] = sR[(hR <= g.db) & (hL > g.db)]

        FL = np.zeros_like(F)
        FL[:, :, 0] = hL * uL
        FL[:, :, 1] = hL * uL ** 2 + 0.5 * g.g * hL ** 2
        FL[:, :, 2] = hL * uL * vL

        FR = np.zeros_like(F)
        FR[:, :, 0] = hR * uR
        FR[:, :, 1] = hR * uR ** 2 + 0.5 * g.g * hR ** 2
        FR[:, :, 2] = hR * uR * vR

        M1 = F[:, :, 0].copy()
        M2 = F[:, :, 1].copy()
        M3 = F[:, :, 2].copy()
        M11 = FL[:, :, 0]
        M12 = FL[:, :, 1]
        M13 = FL[:, :, 2]
        M21 = FR[:, :, 0]
        M22 = FR[:, :, 1]
        M23 = FR[:, :, 2]
        M31 = hL
        M32 = hL * uL
        M41 = hR
        M42 = hR * uR

        K1 = (sR * M11 - sL * M21 + sR * sL * (M41 - M31)) / (sR - sL)
        K2 = (sR * M12 - sL * M22 + sR * sL * (M42 - M32)) / (sR - sL)

        I = sL >= 0
        M1[I] = M11[I]
        M2[I] = M12[I]
        M3[I] = M13[I]

        I = (sL <= 0) & (sG >= 0)
        M1[I] = K1[I]
        M2[I] = K2[I]
        M3[I] = K1[I] * vL[I]

        I = (sG <= 0) & (sR >= 0)
        M1[I] = K1[I]
        M2[I] = K2[I]
        M3[I] = K1[I] * vR[I]

        I = sR <= 0
        M1[I] = M21[I]
        M2[I] = M22[I]
        M3[I] = M23[I]

        F[:, :, 0] = M1
        F[:, :, 1] = M2
        F[:, :, 2] = M3
    return F


def y_HLLC(hL, uL, vL, hR, uR, vR):
    F = np.zeros((hL.shape[0], hL.shape[1], 3))

    with np.errstate(divide="ignore", invalid="ignore"):
        vg = 0.5 * (vL + vR) + np.sqrt(g.g * hL) - np.sqrt(g.g * hR)
        hg = (0.5 * (np.sqrt(g.g * hL) + np.sqrt(g.g * hR)) + 0.25 * (vL - vR)) ** 2 / g.g

        cmL = np.sqrt(g.g * hL)
        cmR = np.sqrt(g.g * hR)
        cmg = np.sqrt(g.g * hg)

        sL = np.minimum(vL - cmL, vg - cmg)
        sR = np.maximum(vR + cmR, vg + cmg)

        I = (hL <= g.db) & (hR > g.db)
        sL[I] = vR[I] - 2.0 * np.sqrt(g.g * hR[I])
        sR[I] = vR[I] + np.sqrt(g.g * hR[I])

        I = (hR <= g.db) & (hL > g.db)
        sR[I] = vL[I] + 2.0 * np.sqrt(g.g * hL[I])
        sL[I] = vL[I] - np.sqrt(g.g * hL[I])

        sG = (sL * hR * (vR - sR) - sR * hL * (vL - sL)) / (hR * (vR - sR) - hL * (vL - sL))
        sG[(hL <= g.db) & (hR > g.db)] = sL[(hL <= g.db) & (hR > g.db)]
        sG[(hR <= g.db) & (hL > g.db)] = sR[(hR <= g.db) & (hL > g.db)]

        FL = np.zeros_like(F)
        FL[:, :, 0] = hL * vL
        FL[:, :, 1] = hL * uL * vL
        FL[:, :, 2] = hL * vL ** 2 + 0.5 * g.g * hL ** 2

        FR = np.zeros_like(F)
        FR[:, :, 0] = hR * vR
        FR[:, :, 1] = hR * uR * vR
        FR[:, :, 2] = hR * vR ** 2 + 0.5 * g.g * hR ** 2

        M1 = F[:, :, 0].copy()
        M2 = F[:, :, 1].copy()
        M3 = F[:, :, 2].copy()
        M11 = FL[:, :, 0]
        M12 = FL[:, :, 1]
        M13 = FL[:, :, 2]
        M21 = FR[:, :, 0]
        M22 = FR[:, :, 1]
        M23 = FR[:, :, 2]
        M31 = hL
        M33 = hL * vL
        M41 = hR
        M43 = hR * vR

        K1 = (sR * M11 - sL * M21 + sR * sL * (M41 - M31)) / (sR - sL)
        K3 = (sR * M13 - sL * M23 + sR * sL * (M43 - M33)) / (sR - sL)

        I = sL >= 0
        M1[I] = M11[I]
        M2[I] = M12[I]
        M3[I] = M13[I]

        I = (sL <= 0) & (sG >= 0)
        M1[I] = K1[I]
        M2[I] = K1[I] * uL[I]
        M3[I] = K3[I]

        I = (sG <= 0) & (sR >= 0)
        M1[I] = K1[I]
        M2[I] = K1[I] * uR[I]
        M3[I] = K3[I]

        I = sR <= 0
        M1[I] = M21[I]
        M2[I] = M22[I]
        M3[I] = M23[I]

        F[:, :, 0] = M1
        F[:, :, 1] = M2
        F[:, :, 2] = M3
    return F


# --------------------------------------------------------------------------- #
# reconstructed face fluxes + hydrostatic corrections
# --------------------------------------------------------------------------- #
def _face_x(QL, QR):
    hL = QL[:, :, 0]
    quL = QL[:, :, 1]
    qvL = QL[:, :, 2]
    neL = QL[:, :, 3]
    hR = QR[:, :, 0]
    quR = QR[:, :, 1]
    qvR = QR[:, :, 2]
    neR = QR[:, :, 3]

    uL = quL / hL
    uR = quR / hR
    vL = qvL / hL
    vR = qvR / hR

    I = hL <= g.db
    uL[I] = 0.0
    vL[I] = 0.0
    I = hR <= g.db
    uR[I] = 0.0
    vR[I] = 0.0

    zL = neL - hL
    zR = neR - hR
    zstar = np.maximum(zL, zR)

    hLc = np.maximum(0.0, neL - zstar)
    hRc = np.maximum(0.0, neR - zstar)

    F = x_HLLC(hLc, uL, vL, hRc, uR, vR)

    corr_right = np.zeros_like(F)
    corr_right[:, :, 1] = 0.5 * g.g * (hR ** 2 - hRc ** 2)
    corr_left = np.zeros_like(F)
    corr_left[:, :, 1] = 0.5 * g.g * (hL ** 2 - hLc ** 2)

    return F, corr_right, corr_left


def _face_y(QL, QR):
    hL = QL[:, :, 0]
    quL = QL[:, :, 1]
    qvL = QL[:, :, 2]
    neL = QL[:, :, 3]
    hR = QR[:, :, 0]
    quR = QR[:, :, 1]
    qvR = QR[:, :, 2]
    neR = QR[:, :, 3]

    uL = quL / hL
    uR = quR / hR
    vL = qvL / hL
    vR = qvR / hR

    I = hL <= g.db
    uL[I] = 0.0
    vL[I] = 0.0
    I = hR <= g.db
    uR[I] = 0.0
    vR[I] = 0.0

    zL = neL - hL
    zR = neR - hR
    zstar = np.maximum(zL, zR)

    hLc = np.maximum(0.0, neL - zstar)
    hRc = np.maximum(0.0, neR - zstar)

    F = y_HLLC(hLc, uL, vL, hRc, uR, vR)

    corr_right = np.zeros_like(F)
    corr_right[:, :, 2] = 0.5 * g.g * (hR ** 2 - hRc ** 2)
    corr_left = np.zeros_like(F)
    corr_left[:, :, 2] = 0.5 * g.g * (hL ** 2 - hLc ** 2)

    return F, corr_right, corr_left


# --------------------------------------------------------------------------- #
# x / y flux updates (well-balanced finite-volume step)
# --------------------------------------------------------------------------- #
def area_x(Uw, Us, dt):
    n, m, k = Uw.shape

    # solid layer
    Q = np.zeros((n, m, k + 1))
    Q[:, :, 0:3] = Us
    Q[:, :, 3] = g.z + Us[:, :, 0]

    Q_1, _, Q_n1, _ = x_boundary(Q)
    Q_alef = np.concatenate((Q_1, Q[0:n - 1, :, :]), axis=0)
    Q_brig = np.concatenate((Q[1:n, :, :], Q_n1), axis=0)

    Fa, A, _ = _face_x(Q_alef, Q)
    Fb, _, B = _face_x(Q, Q_brig)

    Us = Us - (dt / g.dx) * (Fb - Fa + B - A)

    # water layer (free surface sits on bed + solid layer)
    Q = np.zeros((n, m, k + 1))
    Q[:, :, 0:3] = Uw
    Q[:, :, 3] = g.z + Us[:, :, 0] + Uw[:, :, 0]

    Q_1, _, Q_n1, _ = x_boundary(Q)
    Q_alef = np.concatenate((Q_1, Q[0:n - 1, :, :]), axis=0)
    Q_brig = np.concatenate((Q[1:n, :, :], Q_n1), axis=0)

    Fa, A, _ = _face_x(Q_alef, Q)
    Fb, _, B = _face_x(Q, Q_brig)

    Uw = Uw - (dt / g.dx) * (Fb - Fa + B - A)

    return Uw, Us


def area_y(Uw, Us, dt):
    n, m, k = Uw.shape

    # solid layer
    Q = np.zeros((n, m, k + 1))
    Q[:, :, 0:3] = Us
    Q[:, :, 3] = g.z + Us[:, :, 0]

    Q_1, _, Q_m1, _ = y_boundary(Q)
    Q_alef = np.concatenate((Q_1, Q[:, 0:m - 1, :]), axis=1)
    Q_brig = np.concatenate((Q[:, 1:m, :], Q_m1), axis=1)

    Fa, A, _ = _face_y(Q_alef, Q)
    Fb, _, B = _face_y(Q, Q_brig)

    Us = Us - (dt / g.dy) * (Fb - Fa + B - A)

    # water layer
    Q = np.zeros((n, m, k + 1))
    Q[:, :, 0:3] = Uw
    Q[:, :, 3] = g.z + Us[:, :, 0] + Uw[:, :, 0]

    Q_1, _, Q_m1, _ = y_boundary(Q)
    Q_alef = np.concatenate((Q_1, Q[:, 0:m - 1, :]), axis=1)
    Q_brig = np.concatenate((Q[:, 1:m, :], Q_m1), axis=1)

    Fa, A, _ = _face_y(Q_alef, Q)
    Fb, _, B = _face_y(Q, Q_brig)

    Uw = Uw - (dt / g.dy) * (Fb - Fa + B - A)

    return Uw, Us


# --------------------------------------------------------------------------- #
# source-term steps (drag + friction + slope)
# --------------------------------------------------------------------------- #
def x_stps(Uw, Us, dt):
    n, m = g.n, g.m

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

    with np.errstate(divide="ignore", invalid="ignore"):
        k = 1
        Dtx = k * (uw - us) * np.abs(uw - us)
        Dtx[hs <= 1] = 0.0
        Dtx[hw <= 1] = 0.0

        N = np.zeros((n, m)) + g.nn
        N[g.hG == 0] = 0.3
        w1 = -Dtx
        w2 = -uw * np.sqrt(uw ** 2 + vw ** 2) * g.g * N ** 2 / (hw ** (1.0 / 3.0))
        w_s = w1 + w2
        w_s[uw ** 2 + vw ** 2 == 0] = 0.0
        w_s[hw <= g.db] = 0.0

        A = Uw[:, :, 1]
        I = (A >= 0) & (w_s < -(A / dt))
        w_s[I] = -A[I] / dt
        I = (A < 0) & (w_s > -(A / dt))
        w_s[I] = -A[I] / dt
        Uw[:, :, 1] = Uw[:, :, 1] + dt * w_s

        s1 = g.r * Dtx
        s2 = -us / np.sqrt(us ** 2 + vs ** 2) * g.g * hs * np.tan(g.bed)
        s3 = -g.r * g.g * hs * (
            np.vstack((hw[1:n, :], hw[n - 1:n, :]))
            - np.vstack((hw[0:1, :], hw[0:n - 1, :]))
        ) / (2.0 * g.dx)
        s3[hs <= 1] = 0.0
        s_s = s1 + s2 + s3
        s_s[us ** 2 + vs ** 2 == 0] = 0.0
        s_s[hs <= g.db] = 0.0

        A = Us[:, :, 1]
        I = (A >= 0) & (s_s < -(A / dt))
        s_s[I] = -A[I] / dt
        I = (A < 0) & (s_s > -(A / dt))
        s_s[I] = -A[I] / dt
        Us[:, :, 1] = Us[:, :, 1] + dt * s_s

    return Uw, Us


def y_stps(Uw, Us, dt):
    n, m = g.n, g.m

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

    with np.errstate(divide="ignore", invalid="ignore"):
        k = 1
        Dty = k * (vw - vs) * np.abs(vw - vs)
        Dty[hs <= 1] = 0.0
        Dty[hw <= 1] = 0.0

        N = np.zeros((n, m)) + g.nn
        N[g.hG == 0] = 0.3
        w1 = -Dty
        w1[hs <= g.db] = 0.0
        w2 = -vw * np.sqrt(uw ** 2 + vw ** 2) * g.g * N ** 2 / (hw ** (1.0 / 3.0))
        w_s = w1 + w2
        w_s[uw ** 2 + vw ** 2 == 0] = 0.0
        w_s[hw <= g.db] = 0.0

        A = Uw[:, :, 2]
        I = (A >= 0) & (w_s < -(A / dt))
        w_s[I] = -A[I] / dt
        I = (A < 0) & (w_s > -(A / dt))
        w_s[I] = -A[I] / dt
        Uw[:, :, 2] = Uw[:, :, 2] + dt * w_s

        s1 = g.r * Dty
        s2 = -vs / np.sqrt(us ** 2 + vs ** 2) * g.g * hs * np.tan(g.bed)
        s3 = -g.r * g.g * hs * (
            np.hstack((hw[:, 1:m], hw[:, m - 1:m]))
            - np.hstack((hw[:, 0:1], hw[:, 0:m - 1]))
        ) / (2.0 * g.dy)
        s3[hs <= 1] = 0.0
        s_s = s1 + s2 + s3
        s_s[us ** 2 + vs ** 2 == 0] = 0.0
        s_s[hs <= g.db] = 0.0

        A = Us[:, :, 2]
        I = (A >= 0) & (s_s < -(A / dt))
        s_s[I] = -A[I] / dt
        I = (A < 0) & (s_s > -(A / dt))
        s_s[I] = -A[I] / dt
        Us[:, :, 2] = Us[:, :, 2] + dt * s_s

    return Uw, Us

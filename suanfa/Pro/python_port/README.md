# python_port

Full Python port of the MATLAB shallow-water / two-layer debris-flow code.

## Status: complete
Every function from the MATLAB source (all `.m` files) is translated 1:1.

| MATLAB | Python |
|--------|--------|
| `main.m` | `main.py` |
| `doput.m` | `doput.py` |
| `process.m` | `process.py` |
| `test.m` | `test.py` |
| `Init.m` | `solver.py` -> `Init` |
| `parameters.m` | `solver.py` -> `parameters` |
| `time.m` | `solver.py` -> `time` |
| `velocity.m` | `solver.py` -> `velocity` |
| `spcro.m` / `spcrp.m` | `solver.py` -> `spcro` / `spcrp` |
| `area_x.m` / `area_y.m` | `solver.py` -> `area_x` / `area_y` |
| `x_HLLC.m` / `y_HLLC.m` | `solver.py` -> `x_HLLC` / `y_HLLC` |
| `x_stps.m` / `y_stps.m` | `solver.py` -> `x_stps` / `y_stps` |
| `x_boundary.m` / `y_boundary.m` | `solver.py` -> `x_boundary` / `y_boundary` |

Shared global state lives in `global_state.py` (mirrors the MATLAB `global`
variables).

## Run
From the project root (`Pro`):

    python python_port\test.py

Inputs are read from `user1/task1/` (`zb.txt`, `zl.txt`, `hW.txt`, `p.txt`).
Outputs are written to `user1/task1/depth1|depth2|speed1|speed2/`.

"""Mirrors the MATLAB ``global`` variables used across the project."""
db = 0.0          # dry-bed threshold
n = 0             # grid rows
m = 0             # grid columns
basePath = ""     # "<userName>/<taskName>/"
outputIndex = 1
Interval = 1.0

dx = 1.0
dy = 1.0
g = 9.8           # gravity
z = None          # bed elevation (post-slide)
bed = 0.0         # bed slope angle (radians)
r = 1.0           # density ratio rouf/rous
tol = 0.5         # CFL coefficient
nn = 0.0          # Manning coefficient
hG = None         # initial water depth
x = None
y = None

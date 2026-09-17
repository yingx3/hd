# -*- coding: utf-8 -*-
"""按后端同样的流程在 WSL/GRASS 里跑一次 r.avaflow（对照实验用）。"""
import argparse, io, json, os, shutil, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'E:\Projects\ZHLXT\交付\案例数据\断链防控\冰川泥石流沿程调控技术\对照实验'
WSL_HOME_WIN = r'\\wsl.localhost\Ubuntu-20.04\home\wm'
WSL_HOME_LIN = '/home/wm'
GRASS_DB = '/home/wm/grassdata/demo1/PERMANENT'
PROFILE = '689505.0,3300315.0,689925.0,3300735.0,690045.0,3301155.0,690045.0,3301575.0,690045.0,3301965.0,690045.0,3302385.0,690045.0,3302805.0,690045.0,3303225.0'
VIS = '0,1.0,5.0,5.0,1,200,5,0,3000,50,0.30,0.30,0.60,0.2,1.0,None,None,None'
FRICTION = '15,0,0,15,0,0,0,0,0.05'

ap = argparse.ArgumentParser()
ap.add_argument('--job-id', required=True)
ap.add_argument('--raise-m', dest='raise_m', type=float, default=0.0)
ap.add_argument('--profile', default=PROFILE)
ap.add_argument('--barrier', default='barrier.json')
a = ap.parse_args()

job_win = os.path.join(WSL_HOME_WIN, 'avaflow_jobs', a.job_id)
job_lin = '%s/avaflow_jobs/%s' % (WSL_HOME_LIN, a.job_id)
inputs_win = os.path.join(job_win, 'inputs')
os.makedirs(inputs_win, exist_ok=True)
for name in ('elev.tif', 'debris.tif', 'impact_area.tif'):
    shutil.copyfile(os.path.join(SRC, name), os.path.join(inputs_win, name))
print('[1/4] 输入已就位:', inputs_win)

prefix = 'beta_' + a.job_id
elev_ref_lin = job_lin + '/inputs/elev.tif'
if a.raise_m > 0:
    edits = json.load(io.open(os.path.join(SRC, a.barrier), encoding='utf-8-sig'))
    edits[0]['raise'] = a.raise_m
    edits_win = os.path.join(job_win, 'terrain_edits.json')
    with io.open(edits_win, 'w', encoding='utf-8') as f:
        json.dump(edits, f, ensure_ascii=False)
    script = os.path.join(HERE, '..', 'suanfa', 'avaflow', 'apply_terrain_edits.py')
    r = subprocess.run([sys.executable, os.path.abspath(script),
                        '--input', os.path.join(inputs_win, 'elev.tif'),
                        '--edits', edits_win,
                        '--output', os.path.join(inputs_win, 'elev_regulated.tif')],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    line = [l for l in (r.stdout or '').splitlines() if l.startswith('[avaflow_terrain]')]
    print('[2/4] 地形调控:', line[-1] if line else (r.stderr or '')[-200:])
    elev_ref_lin = job_lin + '/inputs/elev_regulated.tif'
else:
    print('[2/4] 对照组：不改地形（无拦挡）')

body = '''# r.avaflow beta script (auto-generated)
r.in.gdal -o --overwrite input='%s' output=beta_elev_%s
r.in.gdal -o --overwrite input='%s/inputs/debris.tif' output=beta_debris_%s
r.in.gdal -o --overwrite input='%s/inputs/impact_area.tif' output=beta_impact_%s
g.region -s rast=beta_elev_%s
r.avaflow.40G prefix=%s phases=3 elevation=beta_elev_%s hrelease=beta_debris_%s rhrelease1=0.8 friction=%s time=10,200 impactarea=beta_impact_%s profile=%s visualization=%s
g.region -d
''' % (elev_ref_lin, a.job_id, job_lin, a.job_id, job_lin, a.job_id, a.job_id, prefix,
       a.job_id, a.job_id, FRICTION, a.job_id, a.profile, VIS)
io.open(os.path.join(job_win, 'start_beta.sh'), 'w', encoding='utf-8', newline='\n').write(body)
print('[3/4] start_beta.sh 已生成，elevation =', elev_ref_lin)

cmd = ['wsl', '-d', 'Ubuntu-20.04', '--', 'bash', '-c',
       "cd %s && chmod +x '%s' && grass %s --exec bash '%s'"
       % (WSL_HOME_LIN, job_lin + '/start_beta.sh', GRASS_DB, job_lin + '/start_beta.sh')]
r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
print('[4/4] grass 退出码 =', r.returncode)
for l in [x for x in (r.stdout or '').splitlines() if x.strip()][-8:]:
    print('   ', l[:150])
res_dir = os.path.join(WSL_HOME_WIN, prefix + '_results', prefix + '_ascii')
frames = sorted(f for f in os.listdir(res_dir) if f.endswith('.asc') and '_hflow' in f) if os.path.isdir(res_dir) else []
print('结果帧数:', len(frames), ' 目录:', res_dir)

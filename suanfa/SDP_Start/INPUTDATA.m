% main program for TRIGRS with ice‑containing slope and degree‑day factor melting
% 新增输出：最终时刻滑面处的体积含水率 (Theta_final.tif)
clc; clear;

% 读取时间序列（累积时间，单位：秒）
t = readmatrix('t.txt');          % e.g., [0, 3600, 7200, 10800] for hourly data
T_total = t(end);

% ========== 数据路径 ==========
rain_path = 'D:\工作\水文模型\色东普启动模型\rainfall_tif\';          % 降雨文件：1.tif, 2.tif, ...
temp_path = 'D:\工作\色东普启动模型\tem_tif\';       % 温度文件夹
output_dir = 'D:\工作\色东普启动模型\results\';              % 输出目录

% ========== 温度文件命名模板（根据实际情况修改） ==========
temp_filename_pattern = 'temp_%d.tif';   % 默认与降雨文件同名，若为 temp_1.tif 则改为 'temp_%d.tif'

% ========== 读取所有温度栅格 ==========
num_periods = length(t) - 1;                 % 时段数
temp_cell = cell(1, num_periods);
for i = 1:num_periods
    temp_file = fullfile(temp_path, sprintf(temp_filename_pattern, i));
    if ~exist(temp_file, 'file')
        error('温度文件不存在：%s\n请检查路径或文件名模板。', temp_file);
    end
    temp_grid = GRIDobj(temp_file);
    temp_cell{i} = double(temp_grid.Z);
end
temp_3d = cat(3, temp_cell{:});               % 三维数组 [行, 列, 时段数]

% ========== 读取静态栅格参数 ==========
dem = GRIDobj('dem.tif');
slope = GRIDobj('slope.tif');
flowdirection = GRIDobj('flow_direction.tif');
zmax = GRIDobj('Soil_depth.tif');             % 土壤厚度 (m)
depthwt = GRIDobj('depthwt.tif');             % 初始地下水位深度 (m)
Ys = GRIDobj('Weight.tif');                   % 土壤容重 (kN/m³)
Yw = GRIDobj('water_weight.tif');             % 水容重 (kN/m³)
c = GRIDobj('cohesion.tif');                  % 粘聚力 (kPa)
f = GRIDobj('friction.tif');                  % 内摩擦角 (度)
Ks = GRIDobj('Ks.tif');                       % 饱和导水率 (m/s)
Izlt = GRIDobj('izlt.tif');                   % 初始入渗能力 (m/s)
D0 = GRIDobj('D0.tif');                       % 扩散系数 (m²/s)

% 含冰量（体积含冰量，0~1）
ice_content = 0.2;   % 20%

% 要输出的时间节点：只输出最终时刻
time_nodes = t(end);   % 标量，仅最后一个时刻

% ===== 调用 TRIGRS 模型（新增输出 Theta_all） =====
[Phead_all, ZMAX_all, Fs_all, Theta_all] = TRIGRS(time_nodes, rain_path, temp_3d, t, ...
    dem, slope, flowdirection, zmax, depthwt, Ys, Yw, c, f, Ks, Izlt, D0, ice_content);

% 提取最终时刻的数据（二维矩阵）
Phead_final = Phead_all(:, :, 1);
Fs_original = Fs_all(:, :, 1);
Theta_final = Theta_all(:, :, 1);   % 新增

% 1. 安全系数除以10（将原范围[0,10]映射到[0,1]）
Fs_scaled = Fs_original / 10;
% 处理 NaN（无效像元保持 NaN）
% 2. 新的滑面深度 = 缩放后的安全系数 × 土壤厚度
zmax_mat = double(zmax.Z);   % 土壤厚度矩阵
ZMAX_new = Fs_scaled .* zmax_mat;

% 保存结果 GeoTIFF

% 泥石流起动区深度（米）
dem.Z = ZMAX_new;
GRIDobj2geotiff(dem, fullfile(output_dir, 'ZMAX_final.tif'));
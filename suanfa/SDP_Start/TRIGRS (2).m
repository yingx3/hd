function [Phead_all, ZMAX_all, Fs_all, Theta_all] = TRIGRS(T_vec, rain_path, temp_3d, t, dem, slope, ...
    flowdirection, zmax, depthwt, Ys, Yw, c, f, Ks, Izlt, D0, ice_content)
% TRIGRS 模型 + 含冰边坡 + 度日因子融化模型
% 新增输出:
%   Theta_all : 三维数组 [行,列,时间点]，对应每个像元最小安全系数层的体积含水率

% ========== 1. 数据预处理 ==========
w1 = dem.size(1);
w2 = dem.size(2);

% 提取双精度矩阵
Dem = double(dem.Z);
slope = double(slope.Z);
flowdirection = double(flowdirection.Z);
zmax = double(zmax.Z);
depthwt = double(depthwt.Z);
Ys = double(Ys.Z);
Yw = double(Yw.Z);
c = double(c.Z);
f = double(f.Z);
Ks = double(Ks.Z);
Izlt = double(Izlt.Z);
D0 = double(D0.Z);

% 向量化（便于逐像元计算）
depthwt = depthwt(:);
Kss_mat = Ks;                     % 保留二维矩阵用于流向分配中的索引
Ks = Ks(:);
D0 = D0(:);
c = c(:);
Ys = Ys(:);
Yw = Yw(:);
zmax = zmax(:);
f = f(:);
Izlt = Izlt(:);
r = slope(:) * pi/180;            % 坡度 (弧度)
beta = cos(r).^2 - (Izlt ./ Ks);
D1 = D0 ./ cos(r).^2;

% ===== 新增：估算孔隙度（用于含水率计算） =====
porosity = 0.4 * ones(size(Ys));   % 所有像元相同
theta_r = 0.05;                               % 残余含水率
alpha = 1;                                  % 进气吸力参数 (m)

% 全局有效像元掩膜：只要 zmax, Ks, c, f 中有 NaN 则该像元无效
valid = ~(isnan(zmax) | isnan(Ks) | isnan(c) | isnan(f) | isnan(depthwt));
valid_idx = find(valid);
n_valid = length(valid_idx);
if n_valid == 0
    error('没有有效的像元，请检查输入栅格中的 NoData 定义。');
end

% ========== 2. 逐时段入渗分配（含冰融化、超渗产流、径流分配） ==========
N = length(t);                % 时间节点数 (包括初始0)
n_periods = N - 1;            % 降雨时段数
undem = sort(unique(Dem(:)), 'descend');
undem = undem(~isnan(undem)); % 剔除 NaN
n1 = length(undem);

% 预存储每个时段每个像元的实际入渗率 (米/时段)
Inz2 = NaN(w1*w2, n_periods);   % 无效像元初始为 NaN

% 初始化剩余冰水深 (米)
remaining_ice = ice_content * zmax;
remaining_ice = max(remaining_ice, 0);

% 度日因子：8.27 mm/(day·°C) -> m/(day·°C)
DDF = 8.27 / 1000;   % 0.00827 m/(day·°C)

for ss = 1:n_periods
    % ---- 读取降雨栅格 ----
    rain_file = fullfile(rain_path, [num2str(ss), '.tif']);
    rain_grid = GRIDobj(rain_file);
    rain = double(rain_grid.Z);           % 单位：米/时段
    
    % ---- 读取温度栅格 ----
    temp = temp_3d(:, :, ss);             % 单位：°C
    
    % ---- 时段长度（秒） ----
    dt_sec = t(ss+1) - t(ss);
    if dt_sec <= 0
        error('时段长度必须为正 (t(%d)=%.2f, t(%d)=%.2f)', ss, t(ss), ss+1, t(ss+1));
    end
    dt_days = dt_sec / 86400;             % 转换为天
    
    % ---- 度日因子计算融化水深 ----
    positive_temp = max(0, temp);         % 只考虑正温
    POT = positive_temp * dt_days;        % 正积温 (°C·天)
    potential_melt = DDF * POT;           % 潜在融水深 (米)
    
    % 获取剩余冰水深 (二维)
    remaining_ice_2d = reshape(remaining_ice, w1, w2);
    % 实际融化水深不能超过剩余冰
    actual_melt = min(potential_melt, remaining_ice_2d);
    % 更新剩余冰
    remaining_ice_2d = remaining_ice_2d - actual_melt;
    remaining_ice = remaining_ice_2d(:);
    
    % ---- 有效雨强 = 降雨 + 融化水 ----
    effective_rain = rain + actual_melt;   % 单位：米/时段
    
    % ---- 超渗产流与径流分配（仅对有效像元加速，但此处为保持逻辑清晰仍全局处理） ----
    % 扩展边界
    Inz1 = padarray(effective_rain, [1 1]);
    
    % 预先计算该时段每个像元的饱和导水率对应的水深（米/时段）
    Ks_depth = Kss_mat * dt_sec;           % 单位：米/时段，与 effective_rain 一致
    
    % 按高程从高到低处理
    for j = 1:n1
        [c1, c2] = find(Dem == undem(j));
        n2 = length(c1);
        for i = 1:n2
            row = c1(i) + 1;
            col = c2(i) + 1;
            if Inz1(row, col) < Ks_depth(c1(i), c2(i))
                % 未超渗，保持原值
                % 无需操作
            else
                % 超渗，多余水量按 D8 流向分配
                excess = Inz1(row, col) - Ks_depth(c1(i), c2(i));
                Inz1(row, col) = Ks_depth(c1(i), c2(i));
                dir = flowdirection(c1(i), c2(i));
                switch dir
                    case 1   % 东
                        Inz1(row, col+1) = Inz1(row, col+1) + excess;
                    case 2   % 东南
                        Inz1(row+1, col+1) = Inz1(row+1, col+1) + excess;
                    case 4   % 南
                        Inz1(row+1, col) = Inz1(row+1, col) + excess;
                    case 8   % 西南
                        Inz1(row+1, col-1) = Inz1(row+1, col-1) + excess;
                    case 16  % 西
                        Inz1(row, col-1) = Inz1(row, col-1) + excess;
                    case 32  % 西北
                        Inz1(row-1, col-1) = Inz1(row-1, col-1) + excess;
                    case 64  % 北
                        Inz1(row-1, col) = Inz1(row-1, col) + excess;
                    case 128 % 东北
                        Inz1(row-1, col+1) = Inz1(row-1, col+1) + excess;
                end
            end
        end
    end
    
    % 裁剪回原始尺寸，得到实际入渗率（米/时段）
    Inz = Inz1(2:end-1, 2:end-1);
    Inz2(:, ss) = Inz(:);
    
    fprintf('时段 %d/%d 完成，剩余冰平均水深: %.4f m\n', ss, n_periods, mean(remaining_ice));
end

% ========== 3. 稳定性计算（每个输出时间节点） ==========
time_points = T_vec(:)';          % 确保行向量
nt = length(time_points);
Phead_all = NaN(w1, w2, nt);      % 预填充 NaN
ZMAX_all  = NaN(w1, w2, nt);
Fs_all    = NaN(w1, w2, nt);
Theta_all = NaN(w1, w2, nt);      % 新增：含水率输出

Zbin = 11;   % 深度离散层数

% 仅对有效像元进行计算，提高效率
for kt = 1:nt
    Tk = time_points(kt);
    
    % ---- 深度离散化（仅对有效像元） ----
    Z1 = NaN(w1*w2, Zbin);
    for idx = 1:n_valid
        i = valid_idx(idx);
        bin1 = linspace(0, zmax(i), Zbin);
        Z1(i, :) = round(bin1 * 1000) / 1000;
    end
    Z1(:, 1) = 0.005;   % 最浅层设为小正值
    
    Pdepth = NaN(w1*w2, Zbin);
    Fs2    = NaN(w1*w2, Zbin);
    Theta_layer = NaN(w1*w2, Zbin);   % 新增：存储每层的含水率
    
    % ---- 逐层计算压力水头和安全系数 ----
    for k = 1:Zbin
        Z = Z1(:, k);
        % 稳态部分
        Pzera = (Z - depthwt) .* beta;
        
        % 瞬态部分 Ptran = Ptran1 - Ptran2
        Ptran1 = zeros(w1*w2, 1);
        Ptran2 = zeros(w1*w2, 1);
        
        % Ptran1: 累加 t(i) <= Tk 的项
        for i = 1:N
            if Tk - t(i) > 0
                Inz = Inz2(:, i);
                ff2 = Inz ./ Ks;
                sqrt_term = sqrt(max(D1, eps) * (Tk - t(i)));
                ff3 = Z ./ (2 * sqrt_term);
                ierfc1 = (1/sqrt(pi)) * exp(-ff3.^2) - ff3 .* erfc(ff3);
                yy = 2 * ff2 .* sqrt_term .* ierfc1;
                Ptran1 = Ptran1 + yy;
            else
                break;
            end
        end
        
        % Ptran2: 累加 t(i+1) <= Tk 的项
        for i = 1:N-1
            if Tk - t(i+1) > 0
                Inz = Inz2(:, i);
                ff22 = Inz ./ Ks;
                sqrt_term2 = sqrt(max(D1, eps) * (Tk - t(i+1)));
                ff4 = Z ./ (2 * sqrt_term2);
                ierfc2 = (1/sqrt(pi)) * exp(-ff4.^2) - ff4 .* erfc(ff4);
                yy2 = 2 * ff22 .* sqrt_term2 .* ierfc2;
                Ptran2 = Ptran2 + yy2;
            else
                break;
            end
        end
        
        Ptran = Ptran1 - Ptran2;
        PP = Ptran + Pzera;
        Pbeta = Z .* beta;
        GW1 = min(PP, Pbeta);      % 压力水头不超过静水压力
        
        % ===== 新增：计算该深度层的体积含水率 =====
        theta_k = zeros(size(GW1));
        sat_idx = GW1 >= 0;                          % 饱和判断
        theta_k(sat_idx) = porosity(sat_idx);        % 饱和区 = 孔隙度
        unsat_idx = GW1 < 0;                         % 非饱和判断
        theta_k(unsat_idx) = theta_r + (porosity(unsat_idx) - theta_r) .* exp(GW1(unsat_idx) ./ alpha);
        theta_k = max(theta_r, min(porosity, theta_k)); % 边界截断
        Theta_layer(:, k) = theta_k;                % 存储该层
        
        % 无限斜坡安全系数
        tan_phi = tan(f * pi/180);
        denom = Ys .* Z .* sin(r) .* cos(r);
        denom(denom == 0) = eps;
        Fs1 = tan_phi ./ tan(r) + (c - GW1 .* Yw .* tan_phi) ./ denom;
        
        Pdepth(:, k) = GW1;
        Fs2(:, k) = Fs1;
    end
    
    % ---- 选取每个像元的最小安全系数及其对应深度（仅对有效像元） ----
    FS = NaN(w1*w2, 1);
    Phead = NaN(w1*w2, 1);
    Zz = NaN(w1*w2, 1);
    Theta_min = NaN(w1*w2, 1);   % 新增：存储滑面处的含水率
    
    for idx = 1:n_valid
        i = valid_idx(idx);
        % 忽略深度层中的 NaN（如果有）
        fscol = Fs2(i, :);
        if all(isnan(fscol))
            FS(i) = NaN;
            Zz(i) = NaN;
            Phead(i) = NaN;
            Theta_min(i) = NaN;
        else
            [minFs, minIdx] = min(fscol);
            if minFs > 10
                FS(i) = 10;
                Zz(i) = 0.005;
                Phead(i) = Pdepth(i, 1);
                Theta_min(i) = Theta_layer(i, 1);
            else
                FS(i) = minFs;
                Zz(i) = Z1(i, minIdx);
                Phead(i) = Pdepth(i, minIdx);
                Theta_min(i) = Theta_layer(i, minIdx);
            end
        end
    end
    
    % 重排为二维并存储（无效像元自动为 NaN）
    Phead_all(:, :, kt) = reshape(Phead, w1, w2);
    ZMAX_all(:, :, kt)  = reshape(Zz, w1, w2);
    Fs_all(:, :, kt)    = reshape(FS, w1, w2);
    Theta_all(:, :, kt) = reshape(Theta_min, w1, w2);   % 新增
end

end
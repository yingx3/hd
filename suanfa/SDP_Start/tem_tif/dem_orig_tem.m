% ========================================================================
% 完整代码（兼容旧版 MATLAB，无 MissingData 参数）
% 功能：将原始 DEM 有效像元清零后分别加上一系列常数，生成多个 GeoTIFF
% ========================================================================

clear; clc; close all;

%% 1. 设置输入文件
input_file = 'dem.tif';   % 请修改为您的 DEM 文件路径

%% 2. 读取原始 DEM 数据及空间参考信息
[dem, refmat] = geotiffread(input_file);   % refmat 是仿射变换矩阵
info = geotiffinfo(input_file);             % 包含坐标系、NoData 等信息

% 获取原始 NoData 值（如果存在）
if isfield(info, 'MissingData')
    nodata_orig = info.MissingData;
else
    nodata_orig = NaN;
end

% 创建有效像元掩码（非 NoData 的区域）
if isnan(nodata_orig)
    valid_mask = ~isnan(dem);
else
    valid_mask = (dem ~= nodata_orig);
end

% 获取数据类型和尺寸
data_type = class(dem);
[rows, cols] = size(dem);

fprintf('原始 DEM 信息：%d 行 x %d 列，数据类型 %s\n', rows, cols, data_type);
if isnan(nodata_orig)
    fprintf('NoData 值：NaN\n');
else
    fprintf('NoData 值：%f\n', nodata_orig);
end

%% 3. 定义要添加的数值列表（您提供的所有数值）
add_values = [
    5.9 
6.9 
6.4 
6.3 
4.7 
6.8 
7.8 
5.4 
7.9 
8.1 
8.2 
9.5 
7.5 
7.6 
7.3 
6.3 
6.0 
6.8 
7.8 
9.4 
8.4 
9.0 
9.4 
9.2 
9.8 
10.0 
10.5 
10.3 
9.2 
9.9 
9.9 
9.5 
8.7 
9.3 
8.8 
9.9 
11.9 
10.8 
11.8 
9.3 
8.2 
8.4 
7.4 
9.1 
8.7 
9.5 
9.0 
9.4 
9.4 
9.1 
9.4 
9.5 
8.9 
7.9 
8.6 
9.6 
9.7 
10.6 
10.1 
9.7 
9.1 
8.5 
6.8 
7.2 
9.9 
7.5 
7.6 
7.2 
8.3 
9.8 
9.8 
8.5 
9.0 
7.1 
8.0 
8.6 
9.7 
8.7 
9.5 
10.3 
9.9 
9.7 
9.3 
8.4 
9.0 
8.1 
7.7 
9.0 
9.0 
8.7 
9.5 
8.6 
9.1 
9.6 
9.3 
9.6 
9.6 
9.8 
10.9 
10.1 
10.1 
8.3 
7.3 
5.3 
5.5 
3.5 
4.4 
5.3 
6.4 
6.9 
6.9 
6.5 
6.5 
6.5 
5.7 
7.4 
7.6 
7.5 
7.0 
5.5 
6.2 
5.8 
4.8 
5.3 
3.3 
2.9 
1.8 
1.1 
1.5 
0.1 
(0.2)
(0.4)
0.0 
1.2 
0.2 
2.3 
2.0 
0.8 
1.7 

];

num_files = length(add_values);
fprintf('准备生成 %d 个 GeoTIFF 文件。\n', num_files);

%% 4. 定义写入时使用的 NoData 标记（GeoTIFF 不支持 NaN，统一用数值）
if isnan(nodata_orig)
    nodata_write = -9999;      % 可自定义，确保不在有效数据范围内
    % 将原始 DEM 中的 NaN 替换为此数值，以便生成正确的掩码
    dem(isnan(dem)) = nodata_write;
    valid_mask = (dem ~= nodata_write);
    fprintf('原始 NoData 为 NaN，写入时将使用 %d 作为 NoData 标记。\n', nodata_write);
else
    nodata_write = nodata_orig;
    fprintf('使用原始 NoData 值：%f\n', nodata_write);
end

%% 5. 循环生成每个文件
% 获取坐标系标签（用于 geotiffwrite）
geoKeyTag = info.GeoTIFFTags.GeoKeyDirectoryTag;

for k = 1:num_files
    % 创建一个全零矩阵（保持原始数据类型）
    new_dem = zeros(rows, cols, data_type);
    
    % 对有效像元赋值为当前常数
    new_dem(valid_mask) = add_values(k);
    
    % 无效区域赋值为 NoData 标记
    new_dem(~valid_mask) = nodata_write;
    
    % 输出文件名
    out_filename = sprintf('%d.tif', k);
    
    % 写入 GeoTIFF（使用 refmat 和 GeoKeyDirectoryTag）
    geotiffwrite(out_filename, new_dem, refmat, 'GeoKeyDirectoryTag', geoKeyTag);
    
    fprintf('已生成文件 %s (常数 = %.8f)\n', out_filename, add_values(k));
end

fprintf('\n所有文件生成完毕！共 %d 个文件。\n', num_files);
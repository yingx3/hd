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
    0
0
0
0
0.000439815
0
0
5.90278E-05
0.000121528
0
0
0
0
0
0.000636574
5.55556E-05
2.19907E-05
1.15741E-05
1.50463E-05
2.19907E-05
0.000150463
1.86661E-05
7.75463E-05
5.43981E-05
4.16667E-05
0.000113426
1.50463E-05
2.19907E-05
1.15741E-05
1.15741E-05
4.16667E-05
2.19907E-05
1.15741E-05
6.13426E-05
1.15741E-05
5.43981E-05
5.09259E-05
7.40741E-05
1.15741E-05
0.000487269
0.000326389
0.000263889
0.000126157
0.000185185
7.63889E-05
0.000168981
5.6713E-05
0.000217593
7.63889E-05
5.6713E-05
0
0
3.35648E-05
0.000119213
1.38889E-05
1.38889E-05
3.35648E-05
0
1.96759E-05
0.000164352
7.29167E-05
5.32407E-05
0.000342593
0.000289352
3.00926E-05
0.000114583
5.90278E-05
0.000118056
2.31481E-05
0
5.90278E-05
0.000341435
0.000125
0.000774306
0.000380787
2.89352E-05
9.14352E-05
0.000104167
0.000111111
0.000234954
0.000166667
0
0.000175926
0.000511574
3.24074E-05
0.000140046
3.81944E-05
1.27315E-05
2.5463E-05
6.48148E-05
0.000123843
0.00003125
9.25926E-06
0
0
0
0
0
0
0
0.000261574
0.000113426
0.000234954
0.001361111
1.15741E-05
0.000122685
8.10185E-06
0
1.15741E-05
1.15741E-05
1.73611E-05
1.38889E-05
1.73611E-05
0.000233796
0.000832176
0.000119213
5.6713E-05
8.10185E-06
1.04167E-05
3.7037E-05
1.73611E-05
2.66204E-05
7.63889E-05
0.000135417
0.000493056
0.000108796
0.000240741
0.000101852
6.94444E-06
1.04167E-05
1.04167E-05
3.47222E-06
4.97685E-05
5.55556E-05
1.04167E-05
1.04167E-05
3.93519E-05
0.0000625
0

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
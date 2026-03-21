import sys
import numpy as np
import pandas as pd
import json

def calculate_debris_flow(data, threshold, short_window, long_window, segment_duration, total_duration, sampling_rate):
    """
    Algorithm to detect debris flow based on STA/LTA (short-term average/long-term average) ratios.
    """

    short_window_samples = short_window * sampling_rate
    long_window_samples = long_window * sampling_rate
    segment_samples = segment_duration * sampling_rate
    total_samples = total_duration * sampling_rate

    # Precompute the STA and LTA using rolling windows with squared values
    sta = np.convolve(data ** 2, np.ones(short_window_samples), 'valid') / short_window_samples
    lta = np.convolve(data ** 2, np.ones(long_window_samples), 'valid') / long_window_samples

    # Pad the STA and LTA to match the original data length
    sta = np.pad(sta, (short_window_samples - 1, 0), mode='constant', constant_values=0)
    lta = np.pad(lta, (long_window_samples - 1, 0), mode='constant', constant_values=0)

    result = np.zeros_like(data)

    for i in range(len(data)):
        # Skip if STA/LTA cannot be computed
        if i < long_window_samples or lta[i] == 0:
            continue

        # Compute the STA/LTA ratio
        ratio = sta[i] / lta[i]

        # Check if the ratio exceeds the threshold
        if ratio > threshold:
            long_window_value = lta[i]
            segment_ratios = []

            for j in range(i, min(i + total_samples, len(data)), segment_samples):
                segment_mean = np.mean(data[j:j + segment_samples] ** 2)
                segment_ratios.append(segment_mean / long_window_value if long_window_value != 0 else 0)

            # Compute changes between segments
            segment_changes = [segment_ratios[k + 1] > segment_ratios[k] for k in range(len(segment_ratios) - 1)]

            # Check conditions for debris flow
            if all(r > threshold for r in segment_ratios) and sum(segment_changes) >= 3:
                result[i] = 1

    # Check if any element in result is 1
    detected = np.any(result == 1)

    return result, detected

# 主程序
if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Error: Missing file path argument")
        sys.exit(1)

    filepath = sys.argv[1]
    print(f"读取文件：{filepath}")

    # 解析命令行参数，设置默认值
    threshold = 2.5
    short_window = 30
    long_window = 240
    segment_duration = 10
    total_duration = 60
    sampling_rate = 100

    if len(sys.argv) >= 3:
        threshold = float(sys.argv[2])
    if len(sys.argv) >= 4:
        short_window = int(sys.argv[3])
    if len(sys.argv) >= 5:
        long_window = int(sys.argv[4])
    if len(sys.argv) >= 6:
        segment_duration = int(sys.argv[5])
    if len(sys.argv) >= 7:
        total_duration = int(sys.argv[6])
    if len(sys.argv) >= 8:
        sampling_rate = int(sys.argv[7])

    print(f"参数设置：threshold={threshold}, short_window={short_window}, long_window={long_window}, segment_duration={segment_duration}, total_duration={total_duration}, sampling_rate={sampling_rate}")

    try:
        df = pd.read_excel(filepath, skiprows=1)
        print(f"CSV 列名：{df.columns.tolist()}")

        data_column = df.iloc[:, 0]  # 假设第一列是信号数据
        data_array = data_column.values.astype(float)
        print(f"读取数据长度：{len(data_array)} 点")

        result_array, detected = calculate_debris_flow(
            data_array, threshold, short_window, long_window,
            segment_duration, total_duration, sampling_rate
        )

        # 输出JSON结果，便于Java捕获
        result = {"detected": bool(detected)}   # 再保险一次转成 bool
        print(f"RESULT_JSON={json.dumps(result)}")

    except Exception as e:
        print(f"Error: {str(e)}")
        sys.exit(1)
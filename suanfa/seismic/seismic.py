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

    # Precompute the STA and LTA using rolling windows with squared values.
    # 用累积和做滑动平均（O(n)）：原来是 np.convolve(ones(24000))，72 万样本要算几分钟。
    def _movavg_sq(x, w):
        w = int(max(1, w))
        if w <= 1:
            return x ** 2
        cum = np.concatenate(([0.0], np.cumsum(x)))
        avg = (cum[w:] - cum[:-w]) / float(w)
        return np.pad(avg, (w - 1, 0), mode='constant', constant_values=0)

    sta = _movavg_sq(data ** 2, short_window_samples)
    lta = _movavg_sq(data ** 2, long_window_samples)

    result = np.zeros_like(data)
    # 先把 ratio 整体算出来（向量化），再只对「超过阈值」的位置做分段校验；
    # 原实现对每个样本都跑一次 Python 循环，72 万样本要上百秒，这里结果完全等价但快得多。
    with np.errstate(divide='ignore', invalid='ignore'):
        ratio = np.where(lta > 0, sta / np.where(lta > 0, lta, 1.0), 0.0)
    ratio[:long_window_samples] = 0.0

    candidates = np.where((ratio > threshold) & (lta > 0))[0]
    print("candidates:%d" % len(candidates), flush=True)
    for i in candidates:
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

    return data,sta,lta,ratio,result, detected

# 主程序
if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Error: Missing file path argument")
        sys.exit(1)

    filepath = sys.argv[1]
    print(f"file_path:{filepath}")

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

    print(f"params:threshold={threshold}, short_window={short_window}, long_window={long_window}, segment_duration={segment_duration}, total_duration={total_duration}, sampling_rate={sampling_rate}")

    try:
        import time as _time
        _t0 = _time.time()
        df = pd.read_excel(filepath, skiprows=1)
        print("read_excel_seconds:%.1f" % (_time.time() - _t0), flush=True)
        print(f"CSV_column_name:{df.columns.tolist()}")

        data_column = df.iloc[:, 0]  # 假设第一列是信号数据
        data_array = data_column.values.astype(float)
        print(f"data_length:{len(data_array)} ")

        _t1 = _time.time()
        data,sta,lta,ratio,result_array, detected = calculate_debris_flow(
            data_array, threshold, short_window, long_window,
            segment_duration, total_duration, sampling_rate
        )
        print("calculate_seconds:%.1f" % (_time.time() - _t1), flush=True)
        
        # 输出JSON结果，便于Java捕获
        # 大幅降采样：绘图只需要几千个点，原样返回 72 万点会生成 100MB+ 的 JSON，
        # 后端序列化/前端解析都会崩（此前 500 的原因）。
        MAX_POINTS = 4000
        n_total = len(data)

        def _downsample(arr, max_points=MAX_POINTS):
            arr = np.asarray(arr)
            if arr.size <= max_points:
                return arr
            idx = np.linspace(0, arr.size - 1, max_points).astype(int)
            return arr[idx]

        result = {"detected": bool(detected)}   # 再保险一次转成 bool
        result_data = {
            "data": _downsample(data).tolist(),
            "sta": _downsample(sta).tolist(),
            "lta": _downsample(lta).tolist(),
            "ratio": _downsample(ratio).tolist(),
            "result_array": _downsample(result_array).tolist(),
            "detected": bool(detected),
            "totalSamples": int(n_total),
            "drawnSamples": int(min(n_total, MAX_POINTS)),
        }
        # echarts 数据写到临时文件（输入目录不一定可写），stdout 只输出文件路径，
        # 避免后端控制台刷几百 KB
        import os as _os
        import tempfile as _tempfile
        echarts_path = _os.path.join(_tempfile.gettempdir(),
                                     "seismic_echarts_%d.json" % _os.getpid())
        with open(echarts_path, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(result_data, ensure_ascii=False))
        print(f"echarts_file={echarts_path}", flush=True)
        print(f"RESULT_JSON={json.dumps(result)}")

    except Exception as e:
        print(f"Error: {str(e)}")
        sys.exit(1)
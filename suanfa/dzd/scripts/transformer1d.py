import torch.nn as nn
import torch
import pandas as pd
import numpy as np
from pathlib import Path
from base_stem import SignalStem, PositionalEncoding
import sys

"""
================================================================================
MODEL NAME (模型名称): Transformer1D (Encoder-Only Architecture)
AUTHOR (作者): Yuanwei Song (宋元伟)
AFFILIATION (单位): Institute of Mountain Hazards and Environment, CAS (中国科学院成都山地灾害与环境研究所)
CONTACT (联系方式): songyuanwei@imde.ac.cn
================================================================================
STRUCTURE (模型结构):
    - Input (输入):  [Batch, 1, 30,000]
    - Output (输出): [Batch, 1] (Binary Logits / 二分类逻辑输出)
    - Strategy: SignalStem + Linear Embedding + Sinusoidal Positional Encoding 
                + 3-Layer Transformer Encoder + Global Average Pooling.
                (采用卷积前缀 + 线性嵌入 + 正余弦位置编码 + 3层Transformer编码器 + 全局平均池化)

DIMENSION EVOLUTION (维度演变):
--------------------------------------------------------------------------------
========================================================================================================================
================================================================================
Layer (type)                   Output Shape              Param #        
================================================================================
========================================================================================================================
Layer (type:depth-idx)                        Input Shape          Output Shape         Param #            Kernel Shape
========================================================================================================================
Transformer1D                                 [1, 1, 30000]        [1, 1]               --                   --
├─SignalStem: 1-1                             [1, 1, 30000]        [1, 32, 1000]        --                   --
│    └─Sequential: 2-1                        [1, 1, 30000]        [1, 32, 1000]        --                   --
│    │    └─Conv1d: 3-1                       [1, 1, 30000]        [1, 32, 1000]        992                  [30]
│    │    └─BatchNorm1d: 3-2                  [1, 32, 1000]        [1, 32, 1000]        64                   --
│    │    └─ReLU: 3-3                         [1, 32, 1000]        [1, 32, 1000]        --                   --
├─Linear: 1-2                                 [1, 1000, 32]        [1, 1000, 128]       4,224                --
├─PositionalEncoding: 1-3                     [1, 1000, 128]       [1, 1000, 128]       --                   --
├─TransformerEncoder: 1-4                     [1, 1000, 128]       [1, 1000, 128]       --                   --
│    └─ModuleList: 2-2                        --                   --                   --                   --
│    │    └─TransformerEncoderLayer: 3-4      [1, 1000, 128]       [1, 1000, 128]       99,584               --
│    │    └─TransformerEncoderLayer: 3-5      [1, 1000, 128]       [1, 1000, 128]       99,584               --
│    │    └─TransformerEncoderLayer: 3-6      [1, 1000, 128]       [1, 1000, 128]       99,584               --
├─LayerNorm: 1-5                              [1, 128]             [1, 128]             256                  --
├─Dropout: 1-6                                [1, 128]             [1, 128]             --                   --
├─Linear: 1-7                                 [1, 128]             [1, 1]               129                  --
========================================================================================================================

PARAMETERS (参数总量):
--------------------------------------------------------------------------------
- Total Trainable Params:     ~304,417
================================================================================
"""

class Transformer1D(nn.Module):
    def __init__(self, in_channels=1, d_model=128, nhead=4, num_layers=3, dim_feedforward=128):
        super(Transformer1D, self).__init__()

        self.stem = SignalStem(in_channels=in_channels)
        self.embedding = nn.Linear(32, d_model)
        self.pos_encoder = PositionalEncoding(d_model, max_len=1000)

        encoder_layer = nn.TransformerEncoderLayer(
            d_model=d_model,
            nhead=nhead,
            dim_feedforward=dim_feedforward,
            batch_first=True,
            dropout=0.5
        )
        self.transformer_encoder = nn.TransformerEncoder(encoder_layer, num_layers=num_layers)

        self.layer_norm = nn.LayerNorm(d_model)
        self.dropout = nn.Dropout(0.5)
        self.fc = nn.Linear(d_model, 1)

    def forward(self, x):
        # 降采样并调整维度: (B, 1, 30000) -> (B, 16, 1000) -> (B, 1000, 32)
        x = self.stem(x).permute(0, 2, 1)
        #  Embedding + Position
        x = self.embedding(x)
        x = self.pos_encoder(x)
        # Transformer 计算
        out = self.transformer_encoder(x)
        # 4. 全局聚合与分类
        out = out.mean(dim=1)
        out = self.dropout(self.layer_norm(out))
        return self.fc(out)


def load_csv_to_model_input(csv_path, seq_len=30000):
    """
    功能：把 CSV 电压数据 → 模型输入格式 (1, 1, 30000)
    你的 CSV 必须只有一列：Voltage_mV
    """
    # 1. 读取 CSV
    df = pd.read_csv(csv_path)

    # 2. 取出电压列（改成你自己的列名也行）
    signal = df['Voltage_mV'].values.astype(np.float32)
    signal_min = signal.min()
    signal_max = signal.max()
    if signal_max > signal_min:
        signal = (signal - signal_min) / (signal_max - signal_min)
    else:
        signal = np.zeros_like(signal, dtype=np.float32)

    # 3. 截断 or 补零 → 强制变成 30000 长度
    if len(signal) > seq_len:
        signal = signal[:seq_len]
    else:
        signal = np.pad(signal, (0, seq_len - len(signal)), mode='constant')

    # 4. 转换成张量 + 变成模型需要的形状 (1, 1, 30000)
    tensor_input = torch.tensor(signal.tolist(), dtype=torch.float32).unsqueeze(0).unsqueeze(0)

    return tensor_input


def load_model_weights(model, weight_path=None):
    if weight_path is None:
        weight_path = Path(__file__).resolve().parent / "Transformer1D_best.pth"
    else:
        weight_path = Path(weight_path)

    state_dict = torch.load(weight_path, map_location="cpu", weights_only=True)
    model.load_state_dict(state_dict)
    return weight_path


"""
================================================================================
测试：直接运行！
================================================================================
"""
if __name__ == "__main__":
    # 1. 加载你的 CSV 数据
    filepath=sys.argv[1]
    # input_tensor = load_csv_to_model_input(r"D:\practice\cesium\backend\suanfa\dzd\data\wave_Z.csv")  # 改这里的文件名
    input_tensor = load_csv_to_model_input(filepath)  # 改这里的文件名


# 2. 打印格式，确认是 (1,1,30000)
    print("shape:", input_tensor.shape)  # 一定输出：torch.Sze([1, 1, 30000])

    # 3. 初始化模型
    model = Transformer1D()
    weight_path = load_model_weights(model)
    print("weigh:", weight_path)

    # 4. 直接推理！
    model.eval()
    with torch.no_grad():
        output = model(input_tensor)
        prob = torch.sigmoid(output)
        pred_class = int(prob.item() >= 0.5)
        print("Sigmoid possibility:", prob.item())
        print("pred_class:", pred_class)

    # 5. 输出结果
    print("output:", output.item())

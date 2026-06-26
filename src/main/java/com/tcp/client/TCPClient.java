package com.tcp.client;

import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TCPClient {
	private Socket mClientSocket = null;
	private OutputStream mSendStream = null;
	private InputStream mReceiveStream = null;
	List<Float> parsedVoltages = new ArrayList<>();

	// 异步文件写出线程池
	private final ExecutorService fileWriterExecutor = Executors.newFixedThreadPool(3);

	// ====================== 核心修改：分方向缓存 + 文件状态管理 ======================
	private static final int MAX_POINTS_PER_FILE = 30000;
	private final Map<String, List<Float>> waveBuffer = new HashMap<>();
	// 记录每个方向：当前写入的文件路径
	private final Map<String, String> currentFilePath = new HashMap<>();
	// 记录每个方向：当前文件已写入行数
	private final Map<String, Integer> currentLineCount = new HashMap<>();

	// 初始化
	{
		waveBuffer.put("X", new ArrayList<>());
		waveBuffer.put("Y", new ArrayList<>());
		waveBuffer.put("Z", new ArrayList<>());
		currentFilePath.put("X", null);
		currentFilePath.put("Y", null);
		currentFilePath.put("Z", null);
		currentLineCount.put("X", 0);
		currentLineCount.put("Y", 0);
		currentLineCount.put("Z", 0);
	}

	public TCPClient(String host, int port) {
		if (null == mClientSocket) {
			try {
				mClientSocket = new Socket(host, port);
				mSendStream = mClientSocket.getOutputStream();
				mReceiveStream = mClientSocket.getInputStream();
				System.out.println("Connect TCP Client");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void requestOutSideServerInfo201() {
		int iLen = 292;
		int iRegType = 1;
		int iHeadLen = 32;
		int iHaevestID = 955555;
		int iType = 201;
		byte[] bHead = new byte[32];

		bHead[0] = (byte) (iType & 0x000000ff);
		bHead[1] = (byte) ((iType >> 8) & 0x000000ff);
		bHead[2] = (byte) ((iType >> 16) & 0x000000ff);
		bHead[3] = (byte) ((iType >> 24) & 0x000000ff);

		bHead[12] = (byte) (iHeadLen & 0x000000ff);
		bHead[13] = (byte) ((iHeadLen >> 8) & 0x000000ff);
		bHead[14] = (byte) ((iHeadLen >> 16) & 0x000000ff);
		bHead[15] = (byte) ((iHeadLen >> 24) & 0x000000ff);

		bHead[16] = (byte) (iLen & 0x000000ff);
		bHead[17] = (byte) ((iLen >> 8) & 0x000000ff);
		bHead[18] = (byte) ((iLen >> 16) & 0x000000ff);
		bHead[19] = (byte) ((iLen >> 24) & 0x000000ff);

		byte[] RequestInfo = new byte[292];
		RequestInfo[0] = (byte) (iRegType & 0x000000ff);
		RequestInfo[1] = (byte) ((iRegType >> 8) & 0x000000ff);
		RequestInfo[2] = (byte) ((iRegType >> 16) & 0x000000ff);
		RequestInfo[3] = (byte) ((iRegType >> 24) & 0x000000ff);

		String currentKey = "shanxishifan";
		String currentProject = "shanxishifan:shanxishifan";
		System.arraycopy(currentKey.getBytes(), 0, RequestInfo, 4, currentKey.length());
		System.arraycopy(currentProject.getBytes(), 0, RequestInfo, 68, currentProject.length());
		RequestInfo[196] = (byte) (iHaevestID & 0x000000ff);
		RequestInfo[197] = (byte) ((iHaevestID >> 8) & 0x000000ff);
		RequestInfo[198] = (byte) ((iHaevestID >> 16) & 0x000000ff);
		RequestInfo[199] = (byte) ((iHaevestID >> 24) & 0x000000ff);

		byte[] bSend = new byte[iLen + 32];
		System.arraycopy(bHead, 0, bSend, 0, 32);
		System.arraycopy(RequestInfo, 0, bSend, 32, 292);

		if (null != mSendStream) {
			try {
				mSendStream.write(bSend);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void requestOutSideServerInfo203(int iDeviceID) {
		int iLen = 148;
		int iHeadLen = 32;
		int iHaevestID = 955555;
		int iType = 203;
		byte[] RequestInfo = new byte[180];

		RequestInfo[0] = (byte) (iType & 0x000000ff);
		RequestInfo[1] = (byte) ((iType >> 8) & 0x000000ff);
		RequestInfo[2] = (byte) ((iType >> 16) & 0x000000ff);
		RequestInfo[3] = (byte) ((iType >> 24) & 0x000000ff);

		RequestInfo[12] = (byte) (iHeadLen & 0x000000ff);
		RequestInfo[13] = (byte) ((iHeadLen >> 8) & 0x000000ff);
		RequestInfo[14] = (byte) ((iHeadLen >> 16) & 0x000000ff);
		RequestInfo[15] = (byte) ((iHeadLen >> 24) & 0x000000ff);

		RequestInfo[16] = (byte) (iLen & 0x000000ff);
		RequestInfo[17] = (byte) ((iLen >> 8) & 0x000000ff);
		RequestInfo[18] = (byte) ((iLen >> 16) & 0x000000ff);
		RequestInfo[19] = (byte) ((iLen >> 24) & 0x000000ff);

		RequestInfo[20] = (byte) (iDeviceID & 0x000000ff);
		RequestInfo[21] = (byte) ((iDeviceID >> 8) & 0x000000ff);
		RequestInfo[22] = (byte) ((iDeviceID >> 16) & 0x000000ff);
		RequestInfo[23] = (byte) ((iDeviceID >> 24) & 0x000000ff);

		RequestInfo[32 + 0] = (byte) (iHaevestID & 0x000000ff);
		RequestInfo[32 + 1] = (byte) ((iHaevestID >> 8) & 0x000000ff);
		RequestInfo[32 + 2] = (byte) ((iHaevestID >> 16) & 0x000000ff);
		RequestInfo[32 + 3] = (byte) ((iHaevestID >> 24) & 0x000000ff);

		RequestInfo[32 + 4] = (byte) (iDeviceID & 0x000000ff);
		RequestInfo[32 + 5] = (byte) ((iDeviceID >> 8) & 0x000000ff);
		RequestInfo[32 + 6] = (byte) ((iDeviceID >> 16) & 0x000000ff);
		RequestInfo[32 + 7] = (byte) ((iDeviceID >> 24) & 0x000000ff);

		if (null != mSendStream) {
			try {
				mSendStream.write(RequestInfo);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void receiveDeviceInfo() {
		if (null != mReceiveStream) {
			try {
				int currentReceiveByteCount;
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
				TimeZone tzu = TimeZone.getTimeZone("UTC");
				sdf.setTimeZone(tzu);
				byte[] currentBuffer = new byte[10240];

				while ((currentReceiveByteCount = mReceiveStream.read(currentBuffer)) != -1) {
					if (currentReceiveByteCount > 0) {
						int iChannelType = readIntLE(currentBuffer, 0);

						if (iChannelType < 1000 && iChannelType > 0) {
							System.out.println("iChannelType:" + iChannelType + " currentReceiveByteCount:" + currentReceiveByteCount);

							if (204 == iChannelType) {
								int iDeviceID = readIntLE(currentBuffer, 4);
								int iType = readIntLE(currentBuffer, 8);
								long iWeeks = readIntLE(currentBuffer, 24) & 0xffffffffL;
								long iWeeksForMS = readIntLE(currentBuffer, 28) & 0xffffffffL;
								long timestamp = (iWeeks * 604800000L + iWeeksForMS) + (315964800L - 18L) * 1000L;
								String formattedDate = sdf.format(new java.sql.Date(timestamp));
								System.out.println(iDeviceID + " = " + ((0 == iType) ? "Z" : ((1 == iType) ? "X" : "Y")) +
										"(UTC)time = " + formattedDate + "(" + timestamp + ")");
							} else if (280 == iChannelType) {
								parseType280(currentBuffer, currentReceiveByteCount, sdf);
							}
						}
					}
				}
			} catch (IOException e) {
				System.out.println("Close Socket for stop Receive String");
			}
		}
	}

	private int readIntLE(byte[] data, int offset) {
		return ((data[offset + 3] & 0xff) << 24) |
				((data[offset + 2] & 0xff) << 16) |
				((data[offset + 1] & 0xff) << 8) |
				(data[offset] & 0xff);
	}

	private void parseType280(byte[] currentBuffer, int currentReceiveByteCount, SimpleDateFormat sdf) {
		// 基本帧头参数解析
		int iDeviceID = readIntLE(currentBuffer, 4);
		int iDeviceType = readIntLE(currentBuffer, 8);
		int iHeadLen = readIntLE(currentBuffer, 12);
		int iContentLen = readIntLE(currentBuffer, 16);

		// 固定采样率：250 Hz，采样间隔 4 ms
		int iSampleRate = 250;
		float sampleIntervalMs = 4.0f;

		// 时间戳信息
		long iWeeks = readIntLE(currentBuffer, 24) & 0xffffffffL;
		long iWeeksForMS = readIntLE(currentBuffer, 28) & 0xffffffffL;
		long timestamp = (iWeeks * 604800000L + iWeeksForMS) + (315964800L - 18L) * 1000L;
		String formattedDate = sdf.format(new java.sql.Date(timestamp));

		// 方向字符串
		String deviceTypeStr = (iDeviceType == 0) ? "Z" :
				(iDeviceType == 1) ? "X" :
						(iDeviceType == 2) ? "Y" : "UNKNOWN";

		System.out.println("====== Receive Type 280 ======");
		System.out.println("Device ID      : " + iDeviceID);
		System.out.println("Frame Type     : " + deviceTypeStr + "(" + iDeviceType + ")");
		System.out.println("Sample Rate    : " + iSampleRate + " Hz (采样间隔 " + sampleIntervalMs + " ms)");
		System.out.println("UTC Time       : " + formattedDate);
		System.out.println("Packet Length  : " + currentReceiveByteCount + " bytes");
		System.out.println("Header Length  : " + iHeadLen + " bytes");
		System.out.println("Content Length : " + iContentLen + " bytes");

		// 波形数据解析
		final float fScale = 5000f / 0xFFFFFF;
		int dataStartIndex = 32;
		int dataEndIndex = currentReceiveByteCount;
		int pointCount = 0;
		parsedVoltages.clear();

		System.out.println("\n====== 波形电压值转换结果 (单位：mV) ======");

		for (int i = dataStartIndex; i + 2 < dataEndIndex; i += 3) {
			int rawValue = ((currentBuffer[i] & 0xff) << 16)
					| ((currentBuffer[i + 1] & 0xff) << 8)
					| (currentBuffer[i + 2] & 0xff);

			int sign = ((rawValue >> 23) == 0) ? 1 : -1;
			float voltage;

			if (sign > 0) {
				voltage = fScale * rawValue;
			} else {
				rawValue = (rawValue - 1) ^ 0xFFFFFF;
				voltage = -fScale * rawValue;
			}
			parsedVoltages.add(voltage);

			float currentTimeMs = pointCount * sampleIntervalMs;
			if (pointCount < 10) {
				System.out.printf("点%-4d (%.3f ms) → 电压: %.3f mV\n", pointCount, currentTimeMs, voltage);
			}
			pointCount++;
		}

		System.out.println("\n本帧解析到有效采样点数: " + pointCount);
		System.out.println("========================================\n");

		// 缓存并写入文件
		cacheWaveData(deviceTypeStr, parsedVoltages);
	}

	public static class listenControlFrames implements Runnable {
		TCPClient currentClient;

		public listenControlFrames(TCPClient currentTCPClient) {
			currentClient = currentTCPClient;
		}

		@Override
		public void run() {
			currentClient.receiveDeviceInfo();
		}
	}

	/**
	 * 缓存波形数据，达到30000点写入新文件，否则追加原文件
	 */
	private void cacheWaveData(String direction, List<Float> points) {
		List<Float> buf = waveBuffer.get(direction);
		buf.addAll(points);

		// 只要有数据，就写入（追加/新建）
		if (!buf.isEmpty()) {
			processWaveData(direction, buf);
			buf.clear();
		}
	}

	/**
	 * 处理写入：不足30000追加，满30000新建文件
	 */
	private void processWaveData(String direction, List<Float> waveData) {
		List<Float> dataCopy = new ArrayList<>(waveData);
		fileWriterExecutor.submit(() -> {
			writeWaveToCSV(direction, dataCopy);
			analyzeWave(direction, dataCopy);
		});
	}

	/**
	 * 智能写入CSV：追加 or 新建
	 */
	private void writeWaveToCSV(String direction, List<Float> data) {
		try {
			int linesInCurrentFile = currentLineCount.get(direction);
			boolean needNewFile = false;

			// 判断：当前文件为空 或 即将超过30000行 → 新建文件
			if (currentFilePath.get(direction) == null || linesInCurrentFile >= MAX_POINTS_PER_FILE) {
				needNewFile = true;
			}

			File targetFile;
			if (needNewFile) {
				// 创建新文件
				String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
				String fileName = String.format("wave_%s_%s.csv", direction, timestamp);
				targetFile = new File(fileName);
				currentFilePath.put(direction, targetFile.getAbsolutePath());
				currentLineCount.put(direction, 0);

				// 写入表头
				try (PrintWriter writer = new PrintWriter(new FileWriter(targetFile))) {
					writer.println("Index,Voltage_mV");
				}
				System.out.println("🆕 新建文件：" + targetFile.getName());
			} else {
				// 使用现有文件
				targetFile = new File(currentFilePath.get(direction));
			}

			// 追加写入数据
			int startIndex = currentLineCount.get(direction);
			try (FileWriter fw = new FileWriter(targetFile, true);
				 PrintWriter writer = new PrintWriter(fw)) {

				for (int i = 0; i < data.size(); i++) {
					int globalIndex = startIndex + i;
					// 只写到30000行为止
					if (globalIndex >= MAX_POINTS_PER_FILE) break;
					writer.printf("%d,%.6f%n", globalIndex, data.get(i));
				}
			}

			// 更新行数
			int newLines = Math.min(data.size(), MAX_POINTS_PER_FILE - startIndex);
			currentLineCount.put(direction, startIndex + newLines);

			System.out.printf("✅ %s方向 追加成功：%d行 | 当前文件总计：%d/%d行\n",
					direction, newLines, currentLineCount.get(direction), MAX_POINTS_PER_FILE);

		} catch (IOException e) {
			System.err.println("❌ 写入失败：" + e.getMessage());
		}
	}

	/**
	 * 数据分析
	 */
	private void analyzeWave(String direction, List<Float> data) {
		if (data.isEmpty()) return;
		float sum = 0, max = Float.MIN_VALUE, min = Float.MAX_VALUE;
		for (float v : data) {
			sum += v;
			max = Math.max(max, v);
			min = Math.min(min, v);
		}
		float avg = sum / data.size();
		System.out.printf("【分析】%s方向 - 平均:%.3f mV | 最大:%.3f | 最小:%.3f%n", direction, avg, max, min);
	}

	public void closeConnection() {
		try {
			if (mClientSocket != null) mClientSocket.close();
			fileWriterExecutor.shutdown();
			System.out.println("连接已关闭");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		TCPClient currentTCPClient = new TCPClient("61.160.105.26", 16151);
		Thread currentFramesThread = new Thread(new listenControlFrames(currentTCPClient));
		currentFramesThread.start();
		currentTCPClient.requestOutSideServerInfo201();
		currentTCPClient.requestOutSideServerInfo203(5200369);
	}
}
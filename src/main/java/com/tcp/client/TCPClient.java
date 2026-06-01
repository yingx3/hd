package com.tcp.client;

import org.springframework.http.ResponseEntity;

import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class TCPClient {

	private static final int RAW_SAMPLE_RATE = 250;
	private static final int TARGET_SAMPLE_RATE = 100;
	private static final int WINDOW_SECONDS = 300; // 5分钟
	private static final int RAW_POINTS_PER_WINDOW = RAW_SAMPLE_RATE * WINDOW_SECONDS;       // 75000
	private static final int TARGET_POINTS_PER_WINDOW = TARGET_SAMPLE_RATE * WINDOW_SECONDS; // 30000

	private Socket socket;
	private OutputStream sendStream;
	private InputStream receiveStream;

	// Z方向5分钟缓存
	private final List<Float> zWindowBuffer = Collections.synchronizedList(new ArrayList<>());

	// 原始Z方向持续写入文件
	private static final String RAW_Z_FILE = "raw_Z.csv";

	private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	public TCPClient(String host, int port) {
		try {
			socket = new Socket(host, port);
			sendStream = socket.getOutputStream();
			receiveStream = socket.getInputStream();
			System.out.println("Connect TCP Client");
		} catch (IOException e) {
			throw new RuntimeException("连接失败", e);
		}

		initRawCsv();

		// 每5分钟处理一次Z方向数据
		scheduler.scheduleAtFixedRate(this::processFiveMinuteWindow, 5, 5, TimeUnit.MINUTES);
	}

	private void initRawCsv() {
		File file = new File(RAW_Z_FILE);
		if (!file.exists()) {
			try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
				writer.println("Timestamp,Direction,Voltage_mV");
			} catch (IOException e) {
				System.err.println("初始化 raw_Z.csv 失败");
			}
		}
	}

	public void requestOutSideServerInfo201() {
		int len = 292;
		int regType = 1;
		int headLen = 32;
		int haevestID = 955555;
		int type = 201;

		byte[] head = new byte[32];
		writeIntLE(head, 0, type);
		writeIntLE(head, 12, headLen);
		writeIntLE(head, 16, len);

		byte[] requestInfo = new byte[292];
		writeIntLE(requestInfo, 0, regType);

		String currentKey = "shanxishifan";
		String currentProject = "shanxishifan:shanxishifan";
		System.arraycopy(currentKey.getBytes(), 0, requestInfo, 4, currentKey.length());
		System.arraycopy(currentProject.getBytes(), 0, requestInfo, 68, currentProject.length());
		writeIntLE(requestInfo, 196, haevestID);

		byte[] send = new byte[len + 32];
		System.arraycopy(head, 0, send, 0, 32);
		System.arraycopy(requestInfo, 0, send, 32, len);

		sendBytes(send);
	}

	public void requestOutSideServerInfo203(int deviceID) {
		int len = 148;
		int headLen = 32;
		int haevestID = 955555;
		int type = 203;

		byte[] requestInfo = new byte[180];
		writeIntLE(requestInfo, 0, type);
		writeIntLE(requestInfo, 12, headLen);
		writeIntLE(requestInfo, 16, len);
		writeIntLE(requestInfo, 20, deviceID);
		writeIntLE(requestInfo, 32, haevestID);
		writeIntLE(requestInfo, 36, deviceID);

		sendBytes(requestInfo);
	}

	private void sendBytes(byte[] data) {
		try {
			if (sendStream != null) {
				sendStream.write(data);
				sendStream.flush();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void writeIntLE(byte[] data, int offset, int value) {
		data[offset] = (byte) (value & 0xff);
		data[offset + 1] = (byte) ((value >> 8) & 0xff);
		data[offset + 2] = (byte) ((value >> 16) & 0xff);
		data[offset + 3] = (byte) ((value >> 24) & 0xff);
	}

	private int readIntLE(byte[] data, int offset) {
		return ((data[offset + 3] & 0xff) << 24)
				| ((data[offset + 2] & 0xff) << 16)
				| ((data[offset + 1] & 0xff) << 8)
				| (data[offset] & 0xff);
	}

	public void receiveDeviceInfo() {
		try {
			byte[] buffer = new byte[10240];
			while (true) {
				int count = receiveStream.read(buffer);
				if (count == -1) break;
				if (count <= 0) continue;

				int channelType = readIntLE(buffer, 0);
				if (channelType == 280) {
					parseType280(buffer, count);
				}
			}
		} catch (IOException e) {
			System.out.println("Close Socket for stop Receive");
		}
	}

	private void parseType280(byte[] buffer, int byteCount) {
		int deviceType = readIntLE(buffer, 8);

		// 只处理Z方向：deviceType == 0
		if (deviceType != 0) return;

		long weeks = readIntLE(buffer, 24) & 0xffffffffL;
		long ms = readIntLE(buffer, 28) & 0xffffffffL;
		long timestamp = (weeks * 604800000L + ms) + (315964800L - 18L) * 1000L;

		List<Float> voltages = parseVoltages(buffer, byteCount);
		if (voltages.isEmpty()) return;

		// 持续写入原始Z数据
		ioExecutor.submit(() -> appendRawZCsv(timestamp, voltages));

		// 放入5分钟缓存
		zWindowBuffer.addAll(voltages);

//		System.out.println("Z方向接收点数: " + voltages.size());
	}

	private List<Float> parseVoltages(byte[] buffer, int byteCount) {
		List<Float> voltages = new ArrayList<>();
		final float scale = 5000f / 0xFFFFFF;

		for (int i = 32; i + 2 < byteCount; i += 3) {
			int rawValue = ((buffer[i] & 0xff) << 16)
					| ((buffer[i + 1] & 0xff) << 8)
					| (buffer[i + 2] & 0xff);

			int sign = ((rawValue >> 23) == 0) ? 1 : -1;
			float voltage;
			if (sign > 0) {
				voltage = scale * rawValue;
			} else {
				rawValue = (rawValue - 1) ^ 0xFFFFFF;
				voltage = -scale * rawValue;
			}
			voltages.add(voltage);
		}
		return voltages;
	}

	private void appendRawZCsv(long timestamp, List<Float> voltages) {
		synchronized (RAW_Z_FILE) {
			try (PrintWriter writer = new PrintWriter(new FileWriter(RAW_Z_FILE, true))) {
				for (Float v : voltages) {
					writer.printf("%d,Z,%.6f%n", timestamp, v);
				}
			} catch (IOException e) {
				System.err.println("写入 raw_Z.csv 失败: " + e.getMessage());
			}
		}
	}

	private void processFiveMinuteWindow() {
		List<Float> snapshot;

		synchronized (zWindowBuffer) {
			if (zWindowBuffer.isEmpty()) {
				System.out.println("5分钟到期，但Z方向无数据");
				return;
			}
			snapshot = new ArrayList<>(zWindowBuffer);
			zWindowBuffer.clear();
		}

		ioExecutor.submit(() -> {
			try {
				List<Float> fixedRaw = normalizeRawWindow(snapshot);   // 修正到75000点
				List<Float> resampled = resample250To100(fixedRaw);    // 重采样到30000点
				File file = writeResampledCsv(resampled);

				System.out.println("5分钟Z方向处理完成: 原始点数=" + snapshot.size()
						+ ", 修正后=" + fixedRaw.size()
						+ ", 重采样后=" + resampled.size()
						+ ", 文件=" + file.getAbsolutePath());

				runPython(file);

			} catch (Exception e) {
				System.err.println("5分钟窗口处理失败: " + e.getMessage());
				e.printStackTrace();
			}
		});
	}
	private void runPython(File file) {
		String pythonExe = "./scripts/python/python.exe";
		String pythonScript = "./suanfa/dzd/scripts/transformer1d.py";

		try {
			ProcessBuilder pb = new ProcessBuilder(
					pythonExe,
					pythonScript,
					file.getAbsolutePath()
			);

			pb.redirectErrorStream(true);
			Process process = pb.start();

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					System.out.println("Python输出: " + line);
				}
			}

			int exitCode = process.waitFor();
			System.out.println("Python进程结束，退出码: " + exitCode);

			if (exitCode != 0) {
				System.err.println("Python脚本执行失败: " + file.getAbsolutePath());
			}

		} catch (Exception e) {
			System.err.println("调用Python失败: " + e.getMessage());
			e.printStackTrace();
		}
	}


	// 将5分钟窗口数据修正到75000点
	private List<Float> normalizeRawWindow(List<Float> data) {
		List<Float> result = new ArrayList<>(RAW_POINTS_PER_WINDOW);

		if (data.isEmpty()) {
			for (int i = 0; i < RAW_POINTS_PER_WINDOW; i++) result.add(0f);
			return result;
		}

		if (data.size() == RAW_POINTS_PER_WINDOW) return data;

		if (data.size() > RAW_POINTS_PER_WINDOW) {
			return new ArrayList<>(data.subList(0, RAW_POINTS_PER_WINDOW));
		}

		result.addAll(data);
		float last = data.get(data.size() - 1);
		while (result.size() < RAW_POINTS_PER_WINDOW) {
			result.add(last);
		}
		return result;
	}

	// 250Hz -> 100Hz，线性重采样
	private List<Float> resample250To100(List<Float> input) {
		List<Float> output = new ArrayList<>(TARGET_POINTS_PER_WINDOW);

		double scale = (double) (input.size() - 1) / (TARGET_POINTS_PER_WINDOW - 1);

		for (int i = 0; i < TARGET_POINTS_PER_WINDOW; i++) {
			double srcIndex = i * scale;
			int left = (int) Math.floor(srcIndex);
			int right = Math.min(left + 1, input.size() - 1);
			double frac = srcIndex - left;

			float value = (float) (input.get(left) * (1.0 - frac) + input.get(right) * frac);
			output.add(value);
		}
		return output;
	}

	private File writeResampledCsv(List<Float> data) {
		String time = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		File file = new File("resampled_Z_" + time + ".csv");

		try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
			writer.println("Index,Voltage_mV");
			for (int i = 0; i < data.size(); i++) {
				writer.printf("%d,%.6f%n", i, data.get(i));
			}
		} catch (IOException e) {
			throw new RuntimeException("写入重采样文件失败", e);
		}

		return file;
	}

	public void closeConnection() {
		try {
			scheduler.shutdown();
			ioExecutor.shutdown();
			if (socket != null) socket.close();
			System.out.println("连接已关闭");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static class ListenControlFrames implements Runnable {
		private final TCPClient client;

		public ListenControlFrames(TCPClient client) {
			this.client = client;
		}

		@Override
		public void run() {
			client.receiveDeviceInfo();
		}
	}

	public static void main(String[] args) {
		TCPClient client = new TCPClient("61.160.105.26", 16151);
		new Thread(new ListenControlFrames(client)).start();

		client.requestOutSideServerInfo201();
		client.requestOutSideServerInfo203(5200369);
	}
}

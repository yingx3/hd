package com.tcp.client;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TCPClient implements Runnable, Closeable {

    public static final String DEFAULT_HOST = "61.160.105.26";
    public static final int DEFAULT_PORT = 16151;
    public static final int DEFAULT_DEVICE_ID = 5200369;
    public static final int DEFAULT_HARVEST_ID = 955555;
    public static final String DEFAULT_PROJECT_KEY = "shanxishifan";
    public static final String DEFAULT_PROJECT_NAME = "shanxishifan:shanxishifan";

    private static final int HEADER_LENGTH = 32;
    private static final int MIN_HEADER_LENGTH = 20;
    private static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;
    private static final int DEFAULT_MAX_POINTS_PER_FILE = 30000;
    private static final int SAMPLE_RATE = 250;
    private static final float SAMPLE_INTERVAL_MS = 4.0f;
    private static final long GPS_UTC_OFFSET_MILLIS = (315964800L - 18L) * 1000L;

    private static final DateTimeFormatter LOG_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final String host;
    private final int port;
    private final int defaultDeviceId;
    private final int harvestId;
    private final String projectKey;
    private final String projectName;
    private final File outputDirectory;
    private final int maxPointsPerFile;
    private final boolean reconnectEnabled;
    private final long reconnectDelayMs;
    private final long pollIntervalMs;
    private final int connectTimeoutMs;

    private final Object connectionLock = new Object();
    private final Object sendLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean writerShutdown = new AtomicBoolean(false);
    private final ExecutorService fileWriterExecutor;
    private final Map<String, DirectionFileState> fileStates = new HashMap<>();

    private volatile Socket clientSocket;
    private volatile OutputStream sendStream;
    private volatile InputStream receiveStream;
    private volatile ScheduledExecutorService pollExecutor;

    public TCPClient(String host, int port) {
        this(host, port, DEFAULT_DEVICE_ID, DEFAULT_HARVEST_ID, DEFAULT_PROJECT_KEY, DEFAULT_PROJECT_NAME,
                new File(System.getProperty("tcp.wave.outputDir", ".")),
                Integer.getInteger("tcp.wave.maxPointsPerFile", DEFAULT_MAX_POINTS_PER_FILE),
                Boolean.parseBoolean(System.getProperty("tcp.sensor.reconnect", "true")),
                Long.getLong("tcp.sensor.reconnectDelayMs", 5000L),
                Long.getLong("tcp.sensor.pollIntervalMs", 0L),
                Integer.getInteger("tcp.sensor.connectTimeoutMs", 5000),
                true);
    }

    TCPClient(String host, int port, File outputDirectory, int maxPointsPerFile, boolean autoConnect) {
        this(host, port, DEFAULT_DEVICE_ID, DEFAULT_HARVEST_ID, DEFAULT_PROJECT_KEY, DEFAULT_PROJECT_NAME,
                outputDirectory, maxPointsPerFile, false, 0L, 0L, 1000, autoConnect);
    }

    TCPClient(String host, int port, int defaultDeviceId, int harvestId, String projectKey, String projectName,
              File outputDirectory, int maxPointsPerFile, boolean reconnectEnabled, long reconnectDelayMs,
              long pollIntervalMs, int connectTimeoutMs, boolean autoConnect) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory must not be null");
        }

        this.host = host.trim();
        this.port = port;
        this.defaultDeviceId = defaultDeviceId;
        this.harvestId = harvestId;
        this.projectKey = projectKey == null ? DEFAULT_PROJECT_KEY : projectKey;
        this.projectName = projectName == null ? DEFAULT_PROJECT_NAME : projectName;
        this.outputDirectory = outputDirectory;
        this.maxPointsPerFile = Math.max(1, maxPointsPerFile);
        this.reconnectEnabled = reconnectEnabled;
        this.reconnectDelayMs = Math.max(0L, reconnectDelayMs);
        this.pollIntervalMs = Math.max(0L, pollIntervalMs);
        this.connectTimeoutMs = Math.max(100, connectTimeoutMs);

        this.fileWriterExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "sensor-wave-writer");
            thread.setDaemon(true);
            return thread;
        });
        for (String direction : new String[] {"X", "Y", "Z"}) {
            fileStates.put(direction, new DirectionFileState());
        }

        if (autoConnect) {
            connectQuietly();
        }
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            while (running.get()) {
                try {
                    connectIfNeeded();
                    sendRequest201();
                    sendRequest203(defaultDeviceId);
                    startPolling();
                    receiveLoop();
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("TCP sensor connection error: " + e.getMessage());
                    }
                } catch (RuntimeException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                } finally {
                    stopPolling();
                    closeSocketQuietly();
                }

                if (!running.get() || !reconnectEnabled) {
                    break;
                }

                System.out.println("Reconnect after " + reconnectDelayMs + " ms");
                sleepQuietly(reconnectDelayMs);
            }
        } finally {
            running.set(false);
            stopPolling();
            closeSocketQuietly();
            shutdownWriter();
        }
    }

    private synchronized void connectIfNeeded() throws IOException {
        Socket current = clientSocket;
        if (current != null && current.isConnected() && !current.isClosed()) {
            return;
        }

        closeSocketQuietly();

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            synchronized (connectionLock) {
                clientSocket = socket;
                sendStream = socket.getOutputStream();
                receiveStream = socket.getInputStream();
            }

            System.out.println("Connect TCP Client " + host + ":" + port);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private void connectQuietly() {
        try {
            connectIfNeeded();
        } catch (IOException e) {
            System.err.println("TCP connect failed: " + e.getMessage());
        }
    }

    public void requestOutSideServerInfo201() {
        try {
            sendRequest201();
        } catch (IOException e) {
            System.err.println("Send 201 failed: " + e.getMessage());
        }
    }

    private void sendRequest201() throws IOException {
        sendBytes(buildRequest201(projectKey, projectName, harvestId));
    }

    static byte[] buildRequest201(String projectKey, String projectName, int harvestId) {
        final int contentLength = 292;
        byte[] request = new byte[HEADER_LENGTH + contentLength];

        putIntLE(request, 0, 201);
        putIntLE(request, 12, HEADER_LENGTH);
        putIntLE(request, 16, contentLength);

        int payloadOffset = HEADER_LENGTH;
        putIntLE(request, payloadOffset, 1);
        putString(request, payloadOffset + 4, 64, projectKey);
        putString(request, payloadOffset + 68, 128, projectName);
        putIntLE(request, payloadOffset + 196, harvestId);

        return request;
    }

    public void requestOutSideServerInfo203(int iDeviceID) {
        try {
            sendRequest203(iDeviceID);
        } catch (IOException e) {
            System.err.println("Send 203 failed: " + e.getMessage());
        }
    }

    private void sendRequest203(int iDeviceID) throws IOException {
        sendBytes(buildRequest203(iDeviceID, harvestId));
    }

    static byte[] buildRequest203(int deviceId, int harvestId) {
        final int contentLength = 148;
        byte[] request = new byte[HEADER_LENGTH + contentLength];

        putIntLE(request, 0, 203);
        putIntLE(request, 12, HEADER_LENGTH);
        putIntLE(request, 16, contentLength);
        putIntLE(request, 20, deviceId);
        putIntLE(request, 32, harvestId);
        putIntLE(request, 36, deviceId);

        return request;
    }

    private void sendBytes(byte[] data) throws IOException {
        synchronized (sendLock) {
            OutputStream output = sendStream;
            if (output == null) {
                throw new IOException("TCP connection is not established");
            }
            output.write(data);
            output.flush();
        }
    }

    public void receiveDeviceInfo() {
        boolean startedHere = running.compareAndSet(false, true);
        try {
            if (startedHere) {
                connectIfNeeded();
            }
            receiveLoop();
        } catch (IOException e) {
            if (running.get()) {
                System.err.println("Receive failed: " + e.getMessage());
            }
        } finally {
            if (startedHere) {
                running.set(false);
                closeSocketQuietly();
                shutdownWriter();
            }
        }
    }

    private void receiveLoop() throws IOException {
        InputStream input = receiveStream;
        if (input == null) {
            throw new IOException("TCP receive stream is not available");
        }

        FrameAccumulator accumulator = new FrameAccumulator();
        byte[] buffer = new byte[8192];

        while (running.get()) {
            int count;
            try {
                count = input.read(buffer);
            } catch (SocketException e) {
                if (!running.get() || clientSocket == null || clientSocket.isClosed()) {
                    break;
                }
                throw e;
            }

            if (count < 0) {
                throw new EOFException("Server closed connection");
            }
            if (count == 0) {
                continue;
            }

            for (byte[] frame : accumulator.append(buffer, count)) {
                handleFrame(frame);
            }
        }
    }

    private void handleFrame(byte[] frame) {
        try {
            int type = readIntLE(frame, 0);
            if (type == 204) {
                parseType204(frame);
            } else if (type == 280) {
                ParsedWaveFrame parsed = parseType280Frame(frame);
                logType280Frame(parsed);
                submitWaveData(parsed.direction, parsed.samples);
            } else {
                System.out.println("Unknown frame type: " + type);
            }
        } catch (RuntimeException e) {
            System.err.println("Failed to parse frame, bytes=" + frame.length + ": " + e.getMessage());
        }
    }

    private void parseType204(byte[] frame) {
        if (frame.length < HEADER_LENGTH) {
            return;
        }

        int deviceId = readIntLE(frame, 4);
        int deviceType = readIntLE(frame, 8);
        long timestamp = parseTimestamp(frame);
        System.out.println(deviceId + " = " + directionFor(deviceType)
                + "(UTC)time = " + formatUtc(timestamp) + "(" + timestamp + ")");
    }

    static ParsedWaveFrame parseType280Frame(byte[] frame) {
        if (frame == null || frame.length < HEADER_LENGTH) {
            throw new IllegalArgumentException("280 frame is shorter than " + HEADER_LENGTH + " bytes");
        }

        int deviceId = readIntLE(frame, 4);
        int deviceType = readIntLE(frame, 8);
        int headerLength = readIntLE(frame, 12);
        int contentLength = readIntLE(frame, 16);
        long frameTimestamp = parseTimestamp(frame);

        int dataStart = headerLength >= HEADER_LENGTH && headerLength <= frame.length
                ? headerLength
                : HEADER_LENGTH;
        if (dataStart > frame.length) {
            dataStart = HEADER_LENGTH;
        }

        long declaredEndLong = contentLength > 0 ? (long) dataStart + contentLength : frame.length;
        int dataEnd = declaredEndLong >= frame.length ? frame.length : (int) declaredEndLong;
        if (dataEnd < dataStart) {
            dataEnd = frame.length;
        }

        int pointCount = (dataEnd - dataStart) / 3;
        List<WaveSample> samples = new ArrayList<>(pointCount);
        final float scale = 5000f / 0xFFFFFF;

        for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
            int offset = dataStart + pointIndex * 3;
            int rawValue = ((frame[offset] & 0xff) << 16)
                    | ((frame[offset + 1] & 0xff) << 8)
                    | (frame[offset + 2] & 0xff);

            int magnitude;
            float voltage;
            if ((rawValue & 0x800000) == 0) {
                magnitude = rawValue;
                voltage = scale * magnitude;
            } else {
                magnitude = ((~rawValue) & 0xFFFFFF) + 1;
                voltage = -scale * magnitude;
            }

            long sampleTimestamp = frameTimestamp + pointIndex * (long) SAMPLE_INTERVAL_MS;
            samples.add(new WaveSample(sampleTimestamp, voltage));
        }

        return new ParsedWaveFrame(deviceId, deviceType, directionFor(deviceType),
                frameTimestamp, headerLength, contentLength, samples);
    }

    private static void logType280Frame(ParsedWaveFrame parsed) {
        System.out.println("====== Receive Type 280 ======");
        System.out.println("Device ID      : " + parsed.deviceId);
        System.out.println("Frame Type     : " + parsed.direction + "(" + parsed.deviceType + ")");
        System.out.println("Sample Rate    : " + SAMPLE_RATE + " Hz (采样间隔 " + SAMPLE_INTERVAL_MS + " ms)");
        System.out.println("UTC Time       : " + formatUtc(parsed.frameTimestamp));
        System.out.println("Header Length  : " + parsed.headerLength + " bytes");
        System.out.println("Content Length : " + parsed.contentLength + " bytes");
        System.out.println("Valid samples  : " + parsed.samples.size());

        for (int index = 0; index < Math.min(10, parsed.samples.size()); index++) {
            WaveSample sample = parsed.samples.get(index);
            System.out.printf("点%-4d (%s) -> 电压: %.3f mV%n",
                    index, formatUtc(sample.timestampMs), sample.voltageMv);
        }
        System.out.println("========================================\n");
    }

    private void submitWaveData(String direction, List<WaveSample> samples) {
        if (samples == null || samples.isEmpty() || writerShutdown.get()) {
            return;
        }

        List<WaveSample> dataCopy = List.copyOf(samples);
        try {
            fileWriterExecutor.submit(() -> {
                try {
                    writeWaveToCSV(direction, dataCopy);
                    analyzeWave(direction, dataCopy);
                } catch (IOException e) {
                    System.err.println("Write wave CSV failed for " + direction + ": " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            System.err.println("Wave writer is already stopped, drop " + dataCopy.size() + " points");
        }
    }

    void writeWaveToCSV(String direction, List<WaveSample> data) throws IOException {
        if (data == null || data.isEmpty()) {
            return;
        }

        DirectionFileState state = fileStates.computeIfAbsent(direction, key -> new DirectionFileState());
        int offset = 0;

        while (offset < data.size()) {
            if (state.currentFile == null || state.lineCount >= maxPointsPerFile) {
                openNewFile(direction, state);
            }

            int pointsToWrite = Math.min(maxPointsPerFile - state.lineCount, data.size() - offset);
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(state.currentFile, true), StandardCharsets.UTF_8))) {
                for (int index = 0; index < pointsToWrite; index++) {
                    WaveSample sample = data.get(offset + index);
                    writer.printf("%d,%s,%.6f%n", sample.timestampMs, direction, sample.voltageMv);
                }
            }

            state.lineCount += pointsToWrite;
            offset += pointsToWrite;

            System.out.printf("%s direction wrote %d points -> %s (%d/%d)%n",
                    direction, pointsToWrite, state.currentFile.getName(), state.lineCount, maxPointsPerFile);
        }
    }

    private void openNewFile(String direction, DirectionFileState state) throws IOException {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Cannot create output directory: " + outputDirectory.getAbsolutePath());
        }

        String timestamp = FILE_TIME_FORMATTER.format(Instant.now());
        String baseName = String.format("wave_%s_%s", direction, timestamp);
        File target = new File(outputDirectory, baseName + ".csv");
        int suffix = 1;
        while (target.exists()) {
            target = new File(outputDirectory, baseName + "_" + suffix + ".csv");
            suffix++;
        }

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(target), StandardCharsets.UTF_8))) {
            writer.println("Timestamp,Direction,Voltage_mV");
        }

        state.currentFile = target;
        state.lineCount = 0;
        System.out.println("New wave file: " + target.getAbsolutePath());
    }

    private static void analyzeWave(String direction, List<WaveSample> data) {
        if (data.isEmpty()) {
            return;
        }

        float sum = 0;
        float max = Float.MIN_VALUE;
        float min = Float.MAX_VALUE;
        for (WaveSample sample : data) {
            sum += sample.voltageMv;
            max = Math.max(max, sample.voltageMv);
            min = Math.min(min, sample.voltageMv);
        }

        float average = sum / data.size();
        System.out.printf("【分析】%s方向 - 平均:%.3f mV | 最大:%.3f | 最小:%.3f%n",
                direction, average, max, min);
    }

    @Override
    public void close() {
        closeConnection();
    }

    public void closeConnection() {
        boolean wasRunning = running.getAndSet(false);
        stopPolling();
        closeSocketQuietly();
        shutdownWriter();
        if (wasRunning) {
            System.out.println("Connection closed");
        }
    }

    private void startPolling() {
        if (pollIntervalMs <= 0L || pollExecutor != null) {
            return;
        }

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sensor-poll");
            thread.setDaemon(true);
            return thread;
        });
        pollExecutor = executor;
        executor.scheduleAtFixedRate(() -> {
            if (!running.get()) {
                return;
            }
            try {
                sendRequest203(defaultDeviceId);
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("Poll request failed: " + e.getMessage());
                }
                closeSocketQuietly();
            }
        }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        ScheduledExecutorService executor = pollExecutor;
        pollExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void shutdownWriter() {
        if (!writerShutdown.compareAndSet(false, true)) {
            return;
        }

        fileWriterExecutor.shutdown();
        try {
            if (!fileWriterExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fileWriterExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fileWriterExecutor.shutdownNow();
        }
    }

    private void closeSocketQuietly() {
        synchronized (connectionLock) {
            Socket socket = clientSocket;
            clientSocket = null;
            sendStream = null;
            receiveStream = null;
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore close errors
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long parseTimestamp(byte[] frame) {
        long weeks = readIntLE(frame, 24) & 0xffffffffL;
        long millisecondsOfWeek = readIntLE(frame, 28) & 0xffffffffL;
        return weeks * 604800000L + millisecondsOfWeek + GPS_UTC_OFFSET_MILLIS;
    }

    private static String formatUtc(long timestamp) {
        return LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    private static String directionFor(int deviceType) {
        if (deviceType == 0) {
            return "Z";
        }
        if (deviceType == 1) {
            return "X";
        }
        if (deviceType == 2) {
            return "Y";
        }
        return "UNKNOWN";
    }

    private static boolean isPlausibleFrameHeader(int type, int headerLength, int contentLength) {
        return type > 0
                && type < 1000
                && headerLength >= MIN_HEADER_LENGTH
                && headerLength <= MAX_FRAME_BYTES
                && contentLength >= 0
                && contentLength <= MAX_FRAME_BYTES - headerLength;
    }

    private static int readIntLE(byte[] data, int offset) {
        return ((data[offset + 3] & 0xff) << 24)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 1] & 0xff) << 8)
                | (data[offset] & 0xff);
    }

    private static void putIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) ((value >> 16) & 0xff);
        data[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void putString(byte[] data, int offset, int maxLength, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLength) {
            throw new IllegalArgumentException("String field exceeds " + maxLength + " bytes: " + value);
        }
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }

    static List<byte[]> extractFrames(byte[] stream) {
        FrameAccumulator accumulator = new FrameAccumulator();
        return accumulator.append(stream, stream.length);
    }

    static final class FrameAccumulator {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        List<byte[]> append(byte[] data, int length) {
            if (length <= 0) {
                return new ArrayList<>();
            }

            pending.write(data, 0, length);
            byte[] buffered = pending.toByteArray();
            int offset = 0;
            List<byte[]> frames = new ArrayList<>();

            while (buffered.length - offset >= MIN_HEADER_LENGTH) {
                int type = readIntLE(buffered, offset);
                int headerLength = readIntLE(buffered, offset + 12);
                int contentLength = readIntLE(buffered, offset + 16);

                if (!isPlausibleFrameHeader(type, headerLength, contentLength)) {
                    offset++;
                    continue;
                }

                int frameLength = headerLength + contentLength;
                if (buffered.length - offset < frameLength) {
                    break;
                }

                frames.add(Arrays.copyOfRange(buffered, offset, offset + frameLength));
                offset += frameLength;
            }

            if (offset > 0) {
                pending.reset();
                pending.write(buffered, offset, buffered.length - offset);
            }
            return frames;
        }

        int bufferedBytes() {
            return pending.size();
        }

        void reset() {
            pending.reset();
        }
    }

    static final class ParsedWaveFrame {
        final int deviceId;
        final int deviceType;
        final String direction;
        final long frameTimestamp;
        final int headerLength;
        final int contentLength;
        final List<WaveSample> samples;

        ParsedWaveFrame(int deviceId, int deviceType, String direction, long frameTimestamp,
                        int headerLength, int contentLength, List<WaveSample> samples) {
            this.deviceId = deviceId;
            this.deviceType = deviceType;
            this.direction = direction;
            this.frameTimestamp = frameTimestamp;
            this.headerLength = headerLength;
            this.contentLength = contentLength;
            this.samples = samples;
        }
    }

    static final class WaveSample {
        final long timestampMs;
        final float voltageMv;

        WaveSample(long timestampMs, float voltageMv) {
            this.timestampMs = timestampMs;
            this.voltageMv = voltageMv;
        }
    }

    private static final class DirectionFileState {
        private File currentFile;
        private int lineCount;
    }

    public static class listenControlFrames implements Runnable {
        private final TCPClient currentClient;

        public listenControlFrames(TCPClient currentTCPClient) {
            this.currentClient = currentTCPClient;
        }

        @Override
        public void run() {
            currentClient.run();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        String host = configString("tcp.sensor.host", "TCP_SENSOR_HOST", DEFAULT_HOST);
        int port = configInt("tcp.sensor.port", "TCP_SENSOR_PORT", DEFAULT_PORT);
        int deviceId = configInt("tcp.sensor.deviceId", "TCP_SENSOR_DEVICE_ID", DEFAULT_DEVICE_ID);
        int harvestId = configInt("tcp.sensor.harvestId", "TCP_SENSOR_HARVEST_ID", DEFAULT_HARVEST_ID);
        String projectKey = configString("tcp.sensor.projectKey", "TCP_SENSOR_PROJECT_KEY", DEFAULT_PROJECT_KEY);
        String projectName = configString("tcp.sensor.projectName", "TCP_SENSOR_PROJECT_NAME", DEFAULT_PROJECT_NAME);
        File outputDirectory = new File(configString("tcp.wave.outputDir", "TCP_WAVE_OUTPUT_DIR", "."));
        int maxPointsPerFile = configInt("tcp.wave.maxPointsPerFile", "TCP_WAVE_MAX_POINTS_PER_FILE",
                DEFAULT_MAX_POINTS_PER_FILE);
        boolean reconnect = configBoolean("tcp.sensor.reconnect", "TCP_SENSOR_RECONNECT", true);
        long reconnectDelayMs = configLong("tcp.sensor.reconnectDelayMs", "TCP_SENSOR_RECONNECT_DELAY_MS", 5000L);
        long pollIntervalMs = configLong("tcp.sensor.pollIntervalMs", "TCP_SENSOR_POLL_INTERVAL_MS", 0L);
        int connectTimeoutMs = configInt("tcp.sensor.connectTimeoutMs", "TCP_SENSOR_CONNECT_TIMEOUT_MS", 5000);

        TCPClient client = new TCPClient(host, port, deviceId, harvestId, projectKey, projectName,
                outputDirectory, maxPointsPerFile, reconnect, reconnectDelayMs, pollIntervalMs,
                connectTimeoutMs, false);

        Runtime.getRuntime().addShutdownHook(new Thread(client::closeConnection, "tcp-sensor-shutdown"));
        Thread currentFramesThread = new Thread(new listenControlFrames(client), "tcp-sensor-client");
        currentFramesThread.start();
        currentFramesThread.join();
    }

    private static String configString(String propertyName, String environmentName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environmentName);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int configInt(String propertyName, String environmentName, int defaultValue) {
        String value = configString(propertyName, environmentName, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer config: " + propertyName + "=" + value, e);
        }
    }

    private static long configLong(String propertyName, String environmentName, long defaultValue) {
        String value = configString(propertyName, environmentName, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid long config: " + propertyName + "=" + value, e);
        }
    }

    private static boolean configBoolean(String propertyName, String environmentName, boolean defaultValue) {
        String value = configString(propertyName, environmentName, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }
}
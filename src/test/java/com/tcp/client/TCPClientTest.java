package com.tcp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TCPClientTest {

    @Test
    void frameAccumulatorHandlesSplitAndMergedFrames() {
        byte[] type204 = frame(204, 32, 0);
        putIntLE(type204, 4, 42);
        byte[] type280 = frame(280, 32, 9);
        putIntLE(type280, 4, 52);
        byte[] merged = concat(type204, type280);

        TCPClient.FrameAccumulator accumulator = new TCPClient.FrameAccumulator();
        List<byte[]> frames = new ArrayList<>(accumulator.append(Arrays.copyOfRange(merged, 0, 7), 7));
        assertTrue(frames.isEmpty(), "partial frame must not be parsed");
        assertEquals(7, accumulator.bufferedBytes());

        frames.addAll(accumulator.append(
                Arrays.copyOfRange(merged, 7, merged.length), merged.length - 7));

        assertEquals(2, frames.size());
        assertArrayEquals(type204, frames.get(0));
        assertArrayEquals(type280, frames.get(1));
        assertEquals(0, accumulator.bufferedBytes());
    }

    @Test
    void frameAccumulatorResynchronizesAfterGarbageBytes() {
        byte[] garbage = new byte[] {0x11, 0x22, 0x33};
        byte[] type204 = frame(204, 32, 0);
        byte[] stream = concat(garbage, type204);

        List<byte[]> frames = TCPClient.extractFrames(stream);

        assertEquals(1, frames.size());
        assertArrayEquals(type204, frames.get(0));
    }

    @Test
    void parseType280FrameHonorsDeclaredContentLength() {
        byte[] frame = frame(280, 32, 3);
        putInt24BE(frame, 32, 1000);
        frame = Arrays.copyOf(frame, 41);
        putInt24BE(frame, 35, 0xFFFFFF);

        TCPClient.ParsedWaveFrame parsed = TCPClient.parseType280Frame(frame);

        assertEquals(1, parsed.samples.size(), "bytes after declared content length must be ignored");
        assertEquals(1000f * (5000f / 0xFFFFFF), parsed.samples.get(0).voltageMv, 0.0001f);
    }

    @Test
    void parseType280FrameComputesVoltageAndPerSampleTimestamp() {
        byte[] frame = frame(280, 32, 9);
        putIntLE(frame, 4, 5200369);
        putIntLE(frame, 8, 1);
        putIntLE(frame, 24, 2000);
        putIntLE(frame, 28, 1000);
        putInt24BE(frame, 32, 1000);
        putInt24BE(frame, 35, 0xFFFFFF);
        putInt24BE(frame, 38, 0x800000);

        TCPClient.ParsedWaveFrame parsed = TCPClient.parseType280Frame(frame);
        long expectedStart = 2000L * 604800000L + 1000L + (315964800L - 18L) * 1000L;
        float scale = 5000f / 0xFFFFFF;

        assertEquals("X", parsed.direction);
        assertEquals(expectedStart, parsed.frameTimestamp);
        assertEquals(3, parsed.samples.size());
        assertEquals(expectedStart, parsed.samples.get(0).timestampMs);
        assertEquals(expectedStart + 4L, parsed.samples.get(1).timestampMs);
        assertEquals(expectedStart + 8L, parsed.samples.get(2).timestampMs);
        assertEquals(scale * 1000f, parsed.samples.get(0).voltageMv, 0.0001f);
        assertEquals(-scale, parsed.samples.get(1).voltageMv, 0.0001f);
        assertEquals(-scale * 0x800000, parsed.samples.get(2).voltageMv, 0.0001f);
    }

    @Test
    void writerRotatesFilesWithoutDroppingSamples(@TempDir Path tempDir) throws Exception {
        TCPClient client = new TCPClient("127.0.0.1", 1, tempDir.toFile(), 5, false);
        try {
            List<TCPClient.WaveSample> samples = new ArrayList<>();
            for (int index = 0; index < 7; index++) {
                samples.add(new TCPClient.WaveSample(1000L + index, index));
            }

            client.writeWaveToCSV("Z", samples);

            List<Path> files;
            try (Stream<Path> stream = Files.list(tempDir)) {
                files = stream
                        .filter(path -> path.getFileName().toString().endsWith(".csv"))
                        .sorted()
                        .collect(Collectors.toList());
            }

            assertEquals(2, files.size());
            int dataLineCount = 0;
            Set<Long> timestamps = new HashSet<>();
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file);
                assertEquals("Timestamp,Direction,Voltage_mV", lines.get(0));
                for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
                    String[] columns = lines.get(lineIndex).split(",");
                    timestamps.add(Long.parseLong(columns[0]));
                    dataLineCount++;
                }
            }

            assertEquals(7, dataLineCount, "all points must be written across rotated files");
            assertEquals(7, timestamps.size());
        } finally {
            client.closeConnection();
        }
    }

    @Test
    void closeConnectionIsIdempotent(@TempDir Path tempDir) {
        TCPClient client = new TCPClient("127.0.0.1", 1, tempDir.toFile(), 5, false);

        assertDoesNotThrow(client::closeConnection);
        assertDoesNotThrow(client::closeConnection);
    }

    @Test
    void requestPacketsKeepOriginalProtocolLayout() {
        byte[] request201 = TCPClient.buildRequest201(
                "shanxishifan", "shanxishifan:shanxishifan", 955555);

        assertEquals(324, request201.length);
        assertEquals(201, readIntLE(request201, 0));
        assertEquals(32, readIntLE(request201, 12));
        assertEquals(292, readIntLE(request201, 16));
        assertEquals(1, readIntLE(request201, 32));
        assertEquals("shanxishifan", readString(request201, 36, 12));
        assertEquals("shanxishifan:shanxishifan", readString(request201, 100, 25));
        assertEquals(955555, readIntLE(request201, 228));

        byte[] request203 = TCPClient.buildRequest203(5200369, 955555);
        assertEquals(180, request203.length);
        assertEquals(203, readIntLE(request203, 0));
        assertEquals(32, readIntLE(request203, 12));
        assertEquals(148, readIntLE(request203, 16));
        assertEquals(5200369, readIntLE(request203, 20));
        assertEquals(955555, readIntLE(request203, 32));
        assertEquals(5200369, readIntLE(request203, 36));
    }

    private static byte[] frame(int type, int headerLength, int contentLength) {
        byte[] frame = new byte[headerLength + contentLength];
        putIntLE(frame, 0, type);
        putIntLE(frame, 12, headerLength);
        putIntLE(frame, 16, contentLength);
        return frame;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static int readIntLE(byte[] data, int offset) {
        return ((data[offset + 3] & 0xff) << 24)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 1] & 0xff) << 8)
                | (data[offset] & 0xff);
    }

    private static String readString(byte[] data, int offset, int length) {
        return new String(data, offset, length, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void putIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) ((value >> 16) & 0xff);
        data[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void putInt24BE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 16) & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) (value & 0xff);
    }
}
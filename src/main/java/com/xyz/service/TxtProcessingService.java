package com.xyz.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TxtProcessingService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;



    public void startProcessing(String dirPath) {
        if (!Files.exists(Paths.get(dirPath))) {
            throw new IllegalArgumentException("目录不存在: " + dirPath);
        }
        new Thread(() -> {
            try {
                List<Path> sortedFiles = Files.list(Paths.get(dirPath))
                        .filter(p -> p.toString().endsWith(".txt"))
                        .sorted(Comparator.comparing(Path::getFileName))
                        .collect(Collectors.toList());

                for (Path file : sortedFiles) {
                    double[][] data = parseTxtFile(file);
                    double[][] normalizedData = normalizeData(data);
                    sendViaWebSocket(file.getFileName().toString(), normalizedData);

                    // 根据前端参数控制间隔
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 解析 TXT 文件为二维数组
    private double[][] parseTxtFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        int rows = lines.size();
        int cols = lines.get(0).split(" ").length;
        double[][] matrix = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            String[] values = lines.get(i).split(" ");
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = Double.parseDouble(values[j]);
            }
        }
        return matrix;
    }

    // 数据归一化（缩放到 [0,1]）
    private double[][] normalizeData(double[][] data) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        // 查找全局最大最小值
        for (double[] row : data) {
            for (double val : row) {
                if (val < min) min = val;
                if (val > max) max = val;
            }
        }
        // 归一化计算
        double range = max - min;
        double[][] normalized = new double[data.length][data[0].length];
        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < data[0].length; j++) {
                normalized[i][j] = (data[i][j] - min) / range;
            }
        }
        return normalized;
    }

    // 通过 WebSocket 发送数据
    private void sendViaWebSocket(String filename, double[][] data) {
        String json = convertToJson(data);
        messagingTemplate.convertAndSend("/topic/txt-frames", json);
    }

    // 将数据转换为 JSON 字符串
    private String convertToJson(double[][] data) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
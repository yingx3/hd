package com.xyz.controller;

import org.locationtech.proj4j.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class AscToGeoJSONConverter {
private static final CRSFactory crsFactory = new CRSFactory();
    private static final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    public static void main(String[] args) throws Exception {
        // 处理从 0000 到 0040 共41个文件
        for (int i = 0; i <= 20; i++) {
            // 1. 构建输入输出文件名
            String inputNum = String.format("%04d", i); // 补零到4位
            String outputNum = String.valueOf(i + 1);   // 输出文件从1开始

            File ascFile = new File(
                    "//wsl$/Ubuntu-20.04/home/syl/exp333_results/exp333_ascii/exp333_hflow_max" + inputNum + ".asc"
            );
            File geoJsonFile = new File(
                    "D:\\practice\\nginx-1.24.0\\html\\avaflow_bomi\\avaflow_output" + outputNum + ".geojson"
            );

            // 2. 检查输入文件是否存在
            if (!ascFile.exists()) {
                System.err.println("文件不存在: " + ascFile.getPath());
                continue;
            }

            // 3. 处理单个文件
            try {
                System.out.println("正在处理: " + ascFile.getName());
                processSingleFile(ascFile, geoJsonFile);
            } catch (Exception e) {
                System.err.println("处理失败: " + ascFile.getName());
                e.printStackTrace();
            }
        }
    }

    // 封装单个文件处理逻辑
    private static void processSingleFile(File ascFile, File geoJsonFile) throws Exception {
        AscMetadata metadata = parseAscHeader(ascFile);
        List<List<Double>> gridData = parseAscData(ascFile, metadata);

        CoordinateTransform utmToWgs84 = createCoordinateTransform("EPSG:32647", "EPSG:4326");
        JSONObject geoJson = convertToGeoJSON(gridData, metadata, utmToWgs84);

        try (FileWriter writer = new FileWriter(geoJsonFile)) {
            writer.write(geoJson.toString(4));
        }
    }
    // 解析ASC文件头元数据
    private static AscMetadata parseAscHeader(File file) throws IOException {
        AscMetadata meta = new AscMetadata();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null && !line.trim().isEmpty()) {
                String[] parts = line.trim().split("\\s+");
                switch (parts[0].toLowerCase()) {
                    case "ncols": meta.ncols = Integer.parseInt(parts[1]); break;
                    case "nrows": meta.nrows = Integer.parseInt(parts[1]); break;
                    case "xllcenter": meta.xllcenter = Double.parseDouble(parts[1]); break;
                    case "yllcenter": meta.yllcenter = Double.parseDouble(parts[1]); break;
                    case "cellsize": meta.cellsize = Double.parseDouble(parts[1]); break;
                    case "nodata_value": meta.nodata = Double.parseDouble(parts[1]); break;
                }
            }
        }
        return meta;
    }

    // 解析ASC数据部分
    private static List<List<Double>> parseAscData(File file, AscMetadata meta) throws IOException {
        List<List<Double>> grid = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            // 跳过头部
            for (int i = 0; i < 6; i++) reader.readLine();

            // 读取数据行（ASC数据从北到南排列）
            String line;
            for (int row = 0; row < meta.nrows && (line = reader.readLine()) != null; row++) {
                List<Double> rowData = new ArrayList<>();
                String[] values = line.trim().split("\\s+");
                for (String val : values) {
                    rowData.add(Double.parseDouble(val));
                }
                grid.add(rowData);
            }
        }
        return grid;
    }

    // 创建坐标转换器
    private static CoordinateTransform createCoordinateTransform(String srcCRS, String tgtCRS) {
        CoordinateReferenceSystem source = crsFactory.createFromName(srcCRS);
        CoordinateReferenceSystem target = crsFactory.createFromName(tgtCRS);
        return ctFactory.createTransform(source, target);
    }

//     转换为GeoJSON
    private static JSONObject convertToGeoJSON(List<List<Double>> grid, AscMetadata meta,
                                               CoordinateTransform transform) {
        JSONObject featureCollection = new JSONObject();
        featureCollection.put("type", "FeatureCollection");

        JSONArray features = new JSONArray();
        ProjCoordinate srcCoord = new ProjCoordinate();
        ProjCoordinate tgtCoord = new ProjCoordinate();

        // 遍历所有网格单元（注意ASC数据行顺序）
        for (int row = 0; row < meta.nrows; row++) {
            List<Double> rowData = grid.get(row);
            for (int col = 0; col < meta.ncols; col++) {
                double value = rowData.get(col);

                // 跳过无效数据
                if (value == meta.nodata||value==0.0) continue;

                // 计算UTM坐标（单元格中心）
                double x = meta.xllcenter + (col + 0.5) * meta.cellsize;
                double y = meta.yllcenter + (meta.nrows - row - 0.5) * meta.cellsize;

                // 坐标转换
                srcCoord.setValue(x, y);
                transform.transform(srcCoord, tgtCoord);

                // 创建GeoJSON特征
                JSONObject feature = new JSONObject();
                feature.put("type", "Feature");

                JSONObject geometry = new JSONObject();
                geometry.put("type", "Point");
                geometry.put("coordinates", new JSONArray()
                        .put(tgtCoord.x)
                        .put(tgtCoord.y));

                JSONObject properties = new JSONObject();
                properties.put("value", value);

                feature.put("geometry", geometry);
                feature.put("properties", properties);
                features.put(feature);
            }
        }

        featureCollection.put("features", features);
        return featureCollection;
    }


    static class AscMetadata {
        int ncols;
        int nrows;
        double xllcenter;
        double yllcenter;
        double cellsize;
        double nodata;
    }
}
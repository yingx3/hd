package com.xyz.controller;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把前端手绘的 WGS84 多边形转换成 DepthAveraged2D 所需的地形调控文件。
 *
 * barrier: 写入 barrier.asc，抬高挡墙。
 * notch:   直接下切 z.asc，形成开槽/缺口；h.asc 保持不变。
 */
final class AvaflowAlphaBarrierWriter {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();
    private static final double MAX_CUT_DEPTH = 200.0;

    private AvaflowAlphaBarrierWriter() {
    }

    static final class Result {
        final int barrierCells;
        final double maxBarrierHeight;
        final int notchCells;
        final double maxNotchDepth;

        Result(int barrierCells, double maxBarrierHeight, int notchCells, double maxNotchDepth) {
            this.barrierCells = barrierCells;
            this.maxBarrierHeight = maxBarrierHeight;
            this.notchCells = notchCells;
            this.maxNotchDepth = maxNotchDepth;
        }

        boolean hasBarrier() {
            return barrierCells > 0;
        }

        boolean hasNotch() {
            return notchCells > 0;
        }
    }

    /** 写 barrier.asc：只处理 type=barrier（或旧数据没有 type 时按拦挡处理）。 */
    static Result write(File zFile, File barrierFile, List<Map<String, Object>> edits,
                        String sourceCrs) throws IOException {
        AscGridMetadataReader.GridInfo grid = AscGridMetadataReader.read(zFile);
        List<PolygonEdit> barriers = parseEdits(edits, sourceCrs, true);
        if (barriers.isEmpty()) {
            if (barrierFile.exists() && !barrierFile.delete()) {
                System.err.println("[avaflow-alpha] 旧 barrier.asc 删除失败: " + barrierFile.getAbsolutePath());
            }
            return new Result(0, 0.0, 0, 0.0);
        }

        int cells = 0;
        double maxHeight = 0.0;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(barrierFile), StandardCharsets.UTF_8))) {
            writer.write("ncols " + grid.ncols);
            writer.newLine();
            writer.write("nrows " + grid.nrows);
            writer.newLine();
            writer.write(number("xllcorner", grid.xllCorner));
            writer.newLine();
            writer.write(number("yllcorner", grid.yllCorner));
            writer.newLine();
            writer.write(number("cellsize", grid.cellSize));
            writer.newLine();
            writer.write("NODATA_value -9999");
            writer.newLine();

            for (int row = 0; row < grid.nrows; row++) {
                StringBuilder line = new StringBuilder(grid.ncols * 8);
                double y = grid.yllCorner + (grid.nrows - row - 0.5) * grid.cellSize;
                for (int col = 0; col < grid.ncols; col++) {
                    double x = grid.xllCorner + (col + 0.5) * grid.cellSize;
                    double height = 0.0;
                    for (PolygonEdit polygon : barriers) {
                        if (contains(polygon.points, x, y)) {
                            height = Math.max(height, polygon.height);
                        }
                    }
                    if (height > 0) {
                        cells++;
                        maxHeight = Math.max(maxHeight, height);
                    }
                    if (col > 0) {
                        line.append(' ');
                    }
                    line.append(format(height));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }
        return new Result(cells, maxHeight, 0, 0.0);
    }

    /** 直接把开槽下切写入 z.asc；与 barrier.asc 解耦，可单独使用。 */
    static Result applyNotches(File zFile, List<Map<String, Object>> edits,
                               String sourceCrs) throws IOException {
        AscGridMetadataReader.GridInfo grid = AscGridMetadataReader.read(zFile);
        List<PolygonEdit> notches = parseEdits(edits, sourceCrs, false);
        if (notches.isEmpty()) {
            return new Result(0, 0.0, 0, 0.0);
        }

        double[] values = readAsciiValues(zFile, grid);
        int cells = 0;
        double maxDepth = 0.0;
        for (int row = 0; row < grid.nrows; row++) {
            double y = grid.yllCorner + (grid.nrows - row - 0.5) * grid.cellSize;
            for (int col = 0; col < grid.ncols; col++) {
                double x = grid.xllCorner + (col + 0.5) * grid.cellSize;
                double depth = 0.0;
                for (PolygonEdit notch : notches) {
                    if (contains(notch.points, x, y)) {
                        depth = Math.max(depth, notch.depth);
                    }
                }
                if (depth > 0) {
                    double effective = Math.min(depth, MAX_CUT_DEPTH);
                    values[row * grid.ncols + col] -= effective;
                    cells++;
                    maxDepth = Math.max(maxDepth, effective);
                }
            }
        }
        writeAsciiValues(zFile, grid, values);
        return new Result(0, 0.0, cells, maxDepth);
    }

    private static List<PolygonEdit> parseEdits(List<Map<String, Object>> edits,
                                                String sourceCrs,
                                                boolean barrierOnly) {
        List<PolygonEdit> result = new ArrayList<>();
        if (edits == null) {
            return result;
        }

        CoordinateReferenceSystem source = CRS_FACTORY.createFromName("EPSG:4326");
        CoordinateReferenceSystem target = CRS_FACTORY.createFromName(
                sourceCrs == null || sourceCrs.trim().isEmpty() ? "EPSG:32646" : sourceCrs.trim());
        CoordinateTransform transform = TRANSFORM_FACTORY.createTransform(source, target);

        for (Map<String, Object> edit : edits) {
            if (edit == null) {
                continue;
            }
            String type = String.valueOf(edit.getOrDefault("type", "barrier"))
                    .trim().toLowerCase(Locale.ROOT);
            boolean notch = "notch".equals(type) || "cut".equals(type)
                    || "slot".equals(type) || "开槽".equals(type);
            if (barrierOnly == notch) {
                continue;
            }

            double height = 0.0;
            double depth = 0.0;
            if (notch) {
                Object value = edit.get("depth");
                if (value == null) {
                    value = edit.get("raise");
                }
                depth = numberValue(value, "开槽深度");
                if (!(depth > 0)) {
                    throw new IllegalArgumentException("开槽深度必须大于 0");
                }
            } else {
                Object value = edit.get("height");
                if (value == null) {
                    value = edit.get("raise");
                }
                height = numberValue(value, "坝体高度");
                if (!(height > 0)) {
                    throw new IllegalArgumentException("坝体高度必须大于 0");
                }
            }

            Object polygonValue = edit.get("polygon");
            if (!(polygonValue instanceof Collection)) {
                throw new IllegalArgumentException("调控范围缺少 polygon");
            }
            Collection<?> points = (Collection<?>) polygonValue;
            if (points.size() < 3) {
                throw new IllegalArgumentException("调控范围至少需要 3 个顶点");
            }

            double[][] transformed = new double[points.size()][2];
            int index = 0;
            for (Object pointValue : points) {
                if (!(pointValue instanceof Collection) || ((Collection<?>) pointValue).size() < 2) {
                    throw new IllegalArgumentException("调控范围顶点格式错误");
                }
                Collection<?> point = (Collection<?>) pointValue;
                Object[] pair = point.toArray();
                double lon = numberValue(pair[0], "经度");
                double lat = numberValue(pair[1], "纬度");
                ProjCoordinate in = new ProjCoordinate(lon, lat);
                ProjCoordinate out = new ProjCoordinate();
                transform.transform(in, out);
                transformed[index][0] = out.x;
                transformed[index][1] = out.y;
                index++;
            }
            result.add(new PolygonEdit(transformed, height, depth));
        }
        return result;
    }

    private static double[] readAsciiValues(File file, AscGridMetadataReader.GridInfo grid)
            throws IOException {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        int dataStart = 0;
        while (dataStart < lines.size()) {
            String key = lines.get(dataStart).trim().toLowerCase(Locale.ROOT);
            if (!(key.startsWith("ncols") || key.startsWith("nrows")
                    || key.startsWith("xllcorner") || key.startsWith("xllcenter")
                    || key.startsWith("yllcorner") || key.startsWith("yllcenter")
                    || key.startsWith("cellsize") || key.startsWith("nodata_value"))) {
                break;
            }
            dataStart++;
        }

        double[] values = new double[grid.ncols * grid.nrows];
        int index = 0;
        for (int i = dataStart; i < lines.size() && index < values.length; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            for (String token : line.split("\\s+")) {
                if (index >= values.length) {
                    break;
                }
                values[index++] = Double.parseDouble(token);
            }
        }
        if (index < values.length) {
            throw new IOException("z.asc 数据行不足，无法应用开槽: " + index + "/" + values.length);
        }
        return values;
    }

    private static void writeAsciiValues(File file, AscGridMetadataReader.GridInfo grid,
                                         double[] values) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write("ncols " + grid.ncols);
            writer.newLine();
            writer.write("nrows " + grid.nrows);
            writer.newLine();
            writer.write(number("xllcorner", grid.xllCorner));
            writer.newLine();
            writer.write(number("yllcorner", grid.yllCorner));
            writer.newLine();
            writer.write(number("cellsize", grid.cellSize));
            writer.newLine();
            writer.write("NODATA_value -9999");
            writer.newLine();

            int index = 0;
            for (int row = 0; row < grid.nrows; row++) {
                StringBuilder line = new StringBuilder(grid.ncols * 12);
                for (int col = 0; col < grid.ncols; col++) {
                    if (col > 0) {
                        line.append(' ');
                    }
                    line.append(format(values[index++]));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }
    }

    private static boolean contains(double[][] polygon, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            double xi = polygon[i][0];
            double yi = polygon[i][1];
            double xj = polygon[j][0];
            double yj = polygon[j][1];
            boolean intersects = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double numberValue(Object value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是有效数字");
        }
    }

    private static String number(String key, double value) {
        return key + " " + format(value);
    }

    private static String format(double value) {
        if (Math.abs(value) < 1e-12) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.10f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static final class PolygonEdit {
        final double[][] points;
        final double height;
        final double depth;

        PolygonEdit(double[][] points, double height, double depth) {
            this.points = points;
            this.height = height;
            this.depth = depth;
        }
    }
}
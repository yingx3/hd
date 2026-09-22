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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把前端手绘的 WGS84 多边形转换成 DepthAveraged2D 所需的挡墙高度 ASC。
 */
final class AvaflowAlphaBarrierWriter {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();

    private AvaflowAlphaBarrierWriter() {
    }

    static final class Result {
        final int cells;
        final double maxHeight;

        Result(int cells, double maxHeight) {
            this.cells = cells;
            this.maxHeight = maxHeight;
        }
    }

    static Result write(File zFile, File barrierFile, List<Map<String, Object>> edits,
                        String sourceCrs) throws IOException {
        AscGridMetadataReader.GridInfo grid = AscGridMetadataReader.read(zFile);
        List<PolygonEdit> polygons = parsePolygons(edits, sourceCrs);
        if (polygons.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个有效的手绘拦挡范围");
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
                    for (PolygonEdit polygon : polygons) {
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
        return new Result(cells, maxHeight);
    }

    private static List<PolygonEdit> parsePolygons(List<Map<String, Object>> edits, String sourceCrs) {
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
            double height = numberValue(edit.get("raise"), "坝体高度");
            if (!(height > 0)) {
                throw new IllegalArgumentException("坝体高度必须大于 0");
            }

            Object polygonValue = edit.get("polygon");
            if (!(polygonValue instanceof Collection)) {
                throw new IllegalArgumentException("拦挡范围缺少 polygon");
            }
            Collection<?> points = (Collection<?>) polygonValue;
            if (points.size() < 3) {
                throw new IllegalArgumentException("拦挡范围至少需要 3 个顶点");
            }

            double[][] transformed = new double[points.size()][2];
            int index = 0;
            for (Object pointValue : points) {
                if (!(pointValue instanceof Collection) || ((Collection<?>) pointValue).size() < 2) {
                    throw new IllegalArgumentException("拦挡范围顶点格式错误");
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
            result.add(new PolygonEdit(transformed, height));
        }
        return result;
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

        PolygonEdit(double[][] points, double height) {
            this.points = points;
            this.height = height;
        }
    }
}
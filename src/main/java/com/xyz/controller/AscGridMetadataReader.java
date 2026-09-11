package com.xyz.controller;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Lightweight ESRI ASCII grid reader used by the avaflow beta renderer.
 * It reads the grid header, computes value statistics and exposes the
 * geographic center/bbox after transforming the source CRS to EPSG:4326.
 */
final class AscGridMetadataReader {
    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();

    private AscGridMetadataReader() {
    }

    static final class GridInfo {
        int ncols;
        int nrows;
        double xllCorner;
        double yllCorner;
        double cellSize;
        double noData = Double.NaN;
        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = Double.NEGATIVE_INFINITY;
        boolean hasValue;

        double centerX() {
            return xllCorner + ncols * cellSize / 2.0;
        }

        double centerY() {
            return yllCorner + nrows * cellSize / 2.0;
        }

        double maxX() {
            return xllCorner + ncols * cellSize;
        }

        double maxY() {
            return yllCorner + nrows * cellSize;
        }
    }

    static GridInfo read(File file) throws IOException {
        GridInfo info = new GridInfo();
        boolean hasXllCenter = false;
        boolean hasYllCenter = false;
        double xllCenter = 0.0;
        double yllCenter = 0.0;
        boolean dataStarted = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }

                if (!dataStarted) {
                    String key = parts[0].toLowerCase(Locale.ROOT);
                    switch (key) {
                        case "ncols":
                            info.ncols = parseInt(parts[1], key);
                            break;
                        case "nrows":
                            info.nrows = parseInt(parts[1], key);
                            break;
                        case "xllcorner":
                            info.xllCorner = parseDouble(parts[1], key);
                            hasXllCenter = false;
                            break;
                        case "yllcorner":
                            info.yllCorner = parseDouble(parts[1], key);
                            hasYllCenter = false;
                            break;
                        case "xllcenter":
                            xllCenter = parseDouble(parts[1], key);
                            hasXllCenter = true;
                            break;
                        case "yllcenter":
                            yllCenter = parseDouble(parts[1], key);
                            hasYllCenter = true;
                            break;
                        case "cellsize":
                            info.cellSize = parseDouble(parts[1], key);
                            break;
                        case "nodata_value":
                            info.noData = parseDouble(parts[1], key);
                            break;
                        default:
                            dataStarted = true;
                            break;
                    }
                    if (!dataStarted) {
                        continue;
                    }
                }

                for (String token : parts) {
                    double value;
                    try {
                        value = Double.parseDouble(token);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    if (!Double.isNaN(info.noData) && value == info.noData) {
                        continue;
                    }
                    info.minValue = Math.min(info.minValue, value);
                    info.maxValue = Math.max(info.maxValue, value);
                    info.hasValue = true;
                }
            }
        }

        if (hasXllCenter) {
            info.xllCorner = xllCenter - info.cellSize / 2.0;
        }
        if (hasYllCenter) {
            info.yllCorner = yllCenter - info.cellSize / 2.0;
        }
        if (info.ncols <= 0 || info.nrows <= 0 || info.cellSize <= 0) {
            throw new IOException("Invalid ASC header in " + file.getName());
        }
        return info;
    }

    static double[] transformCenter(GridInfo info, String sourceCrs) {
        return transformPoint(info.centerX(), info.centerY(), sourceCrs);
    }

    static double[] transformBbox(GridInfo info, String sourceCrs) {
        double[] lowerLeft = transformPoint(info.xllCorner, info.yllCorner, sourceCrs);
        double[] lowerRight = transformPoint(info.maxX(), info.yllCorner, sourceCrs);
        double[] upperRight = transformPoint(info.maxX(), info.maxY(), sourceCrs);
        double[] upperLeft = transformPoint(info.xllCorner, info.maxY(), sourceCrs);

        double minLng = Math.min(Math.min(lowerLeft[0], lowerRight[0]), Math.min(upperRight[0], upperLeft[0]));
        double minLat = Math.min(Math.min(lowerLeft[1], lowerRight[1]), Math.min(upperRight[1], upperLeft[1]));
        double maxLng = Math.max(Math.max(lowerLeft[0], lowerRight[0]), Math.max(upperRight[0], upperLeft[0]));
        double maxLat = Math.max(Math.max(lowerLeft[1], lowerRight[1]), Math.max(upperRight[1], upperLeft[1]));
        return new double[]{minLng, minLat, maxLng, maxLat};
    }

    private static double[] transformPoint(double x, double y, String sourceCrs) {
        String srcName = sourceCrs == null || sourceCrs.trim().isEmpty()
                ? "EPSG:32647"
                : sourceCrs.trim();
        CoordinateReferenceSystem source = CRS_FACTORY.createFromName(srcName);
        CoordinateReferenceSystem target = CRS_FACTORY.createFromName("EPSG:4326");
        CoordinateTransform transform = TRANSFORM_FACTORY.createTransform(source, target);
        ProjCoordinate src = new ProjCoordinate(x, y);
        ProjCoordinate dst = new ProjCoordinate();
        transform.transform(src, dst);
        return new double[]{dst.x, dst.y};
    }

    private static int parseInt(String value, String key) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid ASC " + key + " value: " + value, ex);
        }
    }

    private static double parseDouble(String value, String key) throws IOException {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid ASC " + key + " value: " + value, ex);
        }
    }
}
package com.xyz.controller;

import org.locationtech.proj4j.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts an ESRI ASCII grid cell value into point GeoJSON in EPSG:4326.
 * The converter returns the geographic bbox so callers can position the
 * camera without knowing anything about the source area.
 */
public class AscToGeoJSONConverter {
    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: AscToGeoJSONConverter <input.asc> <output.geojson> [sourceCrs]");
            return;
        }
        convert(new File(args[0]), new File(args[1]), args.length > 2 ? args[2] : "EPSG:32647");
    }

    /** Converts one ASC file and returns [minLng, minLat, maxLng, maxLat]. */
    public static double[] convert(File ascFile, File geoJsonFile) throws Exception {
        return convert(ascFile, geoJsonFile, "EPSG:32647");
    }

    /** Converts one ASC file using the supplied source CRS. */
    public static double[] convert(File ascFile, File geoJsonFile, String sourceCrs) throws Exception {
        if (ascFile == null || !ascFile.isFile()) {
            throw new IllegalArgumentException("ASC file does not exist: " + ascFile);
        }
        String src = sourceCrs == null || sourceCrs.trim().isEmpty()
                ? "EPSG:32647"
                : sourceCrs.trim();

        AscMetadata metadata = parseAscHeader(ascFile);
        List<List<Double>> gridData = parseAscData(ascFile, metadata);
        CoordinateTransform transform = createCoordinateTransform(src, "EPSG:4326");
        ConversionResult result = convertToGeoJSON(gridData, metadata, transform);

        File parent = geoJsonFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory: " + parent);
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(geoJsonFile), StandardCharsets.UTF_8)) {
            writer.write(result.geoJson.toString());
        }
        return result.bbox;
    }

    private static AscMetadata parseAscHeader(File file) throws IOException {
        AscMetadata meta = new AscMetadata();
        boolean xllCorner = false;
        boolean yllCorner = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && !line.trim().isEmpty()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                switch (parts[0].toLowerCase()) {
                    case "ncols":
                        meta.ncols = Integer.parseInt(parts[1]);
                        break;
                    case "nrows":
                        meta.nrows = Integer.parseInt(parts[1]);
                        break;
                    case "xllcenter":
                        meta.xllcenter = Double.parseDouble(parts[1]);
                        break;
                    case "yllcenter":
                        meta.yllcenter = Double.parseDouble(parts[1]);
                        break;
                    case "xllcorner":
                        meta.xllcenter = Double.parseDouble(parts[1]);
                        xllCorner = true;
                        break;
                    case "yllcorner":
                        meta.yllcenter = Double.parseDouble(parts[1]);
                        yllCorner = true;
                        break;
                    case "cellsize":
                        meta.cellsize = Double.parseDouble(parts[1]);
                        break;
                    case "nodata_value":
                        meta.nodata = Double.parseDouble(parts[1]);
                        break;
                    default:
                        break;
                }
            }
        }
        if (meta.ncols <= 0 || meta.nrows <= 0 || meta.cellsize <= 0) {
            throw new IOException("Invalid ASC header in " + file.getName());
        }
        if (xllCorner) meta.xllcenter += meta.cellsize / 2.0;
        if (yllCorner) meta.yllcenter += meta.cellsize / 2.0;
        return meta;
    }

    private static List<List<Double>> parseAscData(File file, AscMetadata meta) throws IOException {
        List<List<Double>> grid = new ArrayList<>(meta.nrows);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            // ASC headers used by r.avaflow contain six lines.
            for (int i = 0; i < 6; i++) {
                if (reader.readLine() == null) {
                    throw new IOException("Truncated ASC header in " + file.getName());
                }
            }
            String line;
            for (int row = 0; row < meta.nrows && (line = reader.readLine()) != null; row++) {
                String[] values = line.trim().split("\\s+");
                List<Double> rowData = new ArrayList<>(meta.ncols);
                for (int col = 0; col < meta.ncols && col < values.length; col++) {
                    rowData.add(Double.parseDouble(values[col]));
                }
                if (rowData.size() != meta.ncols) {
                    throw new IOException("Invalid ASC row " + row + " in " + file.getName());
                }
                grid.add(rowData);
            }
        }
        if (grid.size() != meta.nrows) {
            throw new IOException("Invalid ASC row count in " + file.getName());
        }
        return grid;
    }

    private static CoordinateTransform createCoordinateTransform(String srcCrs, String tgtCrs) {
        CoordinateReferenceSystem source = CRS_FACTORY.createFromName(srcCrs);
        CoordinateReferenceSystem target = CRS_FACTORY.createFromName(tgtCrs);
        return TRANSFORM_FACTORY.createTransform(source, target);
    }

    private static ConversionResult convertToGeoJSON(
            List<List<Double>> grid,
            AscMetadata meta,
            CoordinateTransform transform) {
        JSONObject featureCollection = new JSONObject();
        featureCollection.put("type", "FeatureCollection");

        JSONArray features = new JSONArray();
        ProjCoordinate srcCoord = new ProjCoordinate();
        ProjCoordinate tgtCoord = new ProjCoordinate();
        double minLng = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLng = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        boolean hasPoint = false;

        for (int row = 0; row < meta.nrows; row++) {
            List<Double> rowData = grid.get(row);
            for (int col = 0; col < meta.ncols; col++) {
                double value = rowData.get(col);
                if (!Double.isFinite(value) || value == meta.nodata || value == 0.0) {
                    continue;
                }

                double x = meta.xllcenter + col * meta.cellsize;
                double y = meta.yllcenter + (meta.nrows - row - 1) * meta.cellsize;
                srcCoord.setValue(x, y);
                transform.transform(srcCoord, tgtCoord);
                if (!Double.isFinite(tgtCoord.x) || !Double.isFinite(tgtCoord.y)) {
                    continue;
                }

                minLng = Math.min(minLng, tgtCoord.x);
                minLat = Math.min(minLat, tgtCoord.y);
                maxLng = Math.max(maxLng, tgtCoord.x);
                maxLat = Math.max(maxLat, tgtCoord.y);
                hasPoint = true;

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
        double[] bbox = hasPoint
                ? new double[]{minLng, minLat, maxLng, maxLat}
                : null;
        return new ConversionResult(featureCollection, bbox);
    }

    private static class ConversionResult {
        final JSONObject geoJson;
        final double[] bbox;

        ConversionResult(JSONObject geoJson, double[] bbox) {
            this.geoJson = geoJson;
            this.bbox = bbox;
        }
    }

    private static class AscMetadata {
        int ncols;
        int nrows;
        double xllcenter;
        double yllcenter;
        double cellsize;
        double nodata = Double.NaN;
    }
}

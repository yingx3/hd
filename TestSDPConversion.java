import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 模拟 /SDP_Start 端点的坐标转换逻辑，把结果存到本地 JSON 文件
 * 用法: mvn exec:java -Dexec.mainClass="TestSDPConversion"
 */
public class TestSDPConversion {

    public static void main(String[] args) throws Exception {
        // 读取 Python 生成的原始 JSON
        String rawJson = new String(Files.readAllBytes(
                Paths.get("data/SDP_Results/raw_python.json")));
        // 去掉 "RESULT_JSON=" 前缀
        rawJson = rawJson.substring(rawJson.indexOf("=") + 1).trim();
        System.out.println("Raw: " + rawJson.substring(0, Math.min(200, rawJson.length())) + "...");

        Map<String, Object> rawResult = new ObjectMapper().readValue(rawJson,
                new TypeReference<Map<String, Object>>() {});

        boolean isProjected = Boolean.TRUE.equals(rawResult.get("isProjected"));
        double west  = Double.parseDouble(rawResult.get("west").toString());
        double south = Double.parseDouble(rawResult.get("south").toString());
        double east  = Double.parseDouble(rawResult.get("east").toString());
        double north = Double.parseDouble(rawResult.get("north").toString());

        System.out.println("原CRS边界: west=" + west + " south=" + south + " east=" + east + " north=" + north);
        System.out.println("isProjected=" + isProjected);

        double minLng, minLat, maxLng, maxLat;
        if (isProjected && rawResult.get("crsWkt") != null) {
            String crsWkt = rawResult.get("crsWkt").toString();
            System.out.println("CRS: " + crsWkt.substring(0, Math.min(80, crsWkt.length())) + "...");

            CoordinateReferenceSystem sourceCRS = CRS.parseWKT(crsWkt);
            CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326", true);
            MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS);

            double[] sw = new double[]{west, south};
            double[] ne = new double[]{east, north};
            transform.transform(sw, 0, sw, 0, 1);
            transform.transform(ne, 0, ne, 0, 1);

            minLng = sw[0];
            minLat = sw[1];
            maxLng = ne[0];
            maxLat = ne[1];
            System.out.println("转换后 (WGS84): minLng=" + minLng + " minLat=" + minLat + " maxLng=" + maxLng + " maxLat=" + maxLat);
        } else {
            minLng = west;
            minLat = south;
            maxLng = east;
            maxLat = north;
        }

        // 构造最终返回给前端的 JSON
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minLng", Math.round(minLng * 1000000.0) / 1000000.0);
        result.put("minLat", Math.round(minLat * 1000000.0) / 1000000.0);
        result.put("maxLng", Math.round(maxLng * 1000000.0) / 1000000.0);
        result.put("maxLat", Math.round(maxLat * 1000000.0) / 1000000.0);
        result.put("width", rawResult.get("width"));
        result.put("height", rawResult.get("height"));
        result.put("valueRange", rawResult.get("valueRange"));
        // imageBase64 单独存文件（太大了）
        result.put("imageBase64", "[see data/SDP_Results/imageBase64.txt]");

        // 保存坐标 + 元数据
        ObjectMapper mapper = new ObjectMapper();
        String finalJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Files.write(Paths.get("data/SDP_Results/final_result.json"), finalJson.getBytes());
        System.out.println("final_result.json 已保存");

        // imageBase64 单独存
        String imageBase64 = rawResult.get("imageBase64").toString();
        Files.write(Paths.get("data/SDP_Results/imageBase64.txt"), imageBase64.getBytes());
        System.out.println("imageBase64.txt 已保存 (" + (imageBase64.length() / 1024) + " KB)");

        // PNG 也存一份可直接看的图片
        byte[] pngBytes = Base64.getDecoder().decode(imageBase64);
        Files.write(Paths.get("data/SDP_Results/ZMAX_preview.png"), pngBytes);
        System.out.println("ZMAX_preview.png 已保存");
    }
}

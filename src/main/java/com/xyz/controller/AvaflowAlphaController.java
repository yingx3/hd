package com.xyz.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 冰川泥石流动力学模型_alpha。
 *
 * 该链路输入 z.asc / h.asc，直接调用
 * DepthAveraged2D.exe，并把输出 h_######.asc 发布为前端可播放的静态帧。
 */
@RestController
@RequestMapping("/admin/user")
public class AvaflowAlphaController {

    private static final Map<String, Map<String, Object>> ALPHA_JOBS = new ConcurrentHashMap<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${app.project-root:E:/Projects/ZHLXT/backend/hd-mao_0322}")
    private String projectRoot;

    @Value("${app.avaflow-alpha.exe:E:/Projects/ZHLXT/\u7b97\u6cd5/DepthAveraged2D_Windows_Sedongpu_Basic/DepthAveraged2D.exe}")
    private String alphaExe;

    @Value("${app.avaflow-alpha.jobs-root:}")
    private String alphaJobsRoot;

    @Value("${app.avaflow-alpha.static-dir:E:/softwares/nginx-1.26.2/nginx-1.26.2/html}")
    private String alphaStaticDir;

    @Value("${app.avaflow-alpha.static-subdir:avaflow_alpha}")
    private String alphaStaticSubdir;

    @Value("${app.avaflow-alpha.source-crs:EPSG:32646}")
    private String alphaSourceCrs;

    @Value("${app.avaflow-alpha.timeout:3600}")
    private long alphaTimeoutSeconds;

    @Value("${app.avaflow-alpha.model:single-phase}")
    private String alphaModel;

    @Value("${app.avaflow-alpha.terrain-mode:3}")
    private String alphaTerrainMode;

    @Value("${app.avaflow-alpha.source-mode:2}")
    private String alphaSourceMode;

    @Value("${app.avaflow-alpha.source-depth:2.8}")
    private String alphaSourceDepth;

    @Value("${app.avaflow-alpha.source-update-mode:1}")
    private String alphaSourceUpdateMode;

    @Value("${app.avaflow-alpha.mu-coulomb:0.27}")
    private String alphaMuCoulomb;

    @Value("${app.avaflow-alpha.n-manning:0.03}")
    private String alphaNManning;

    @PostMapping("/upload_avaflow_alpha")
    public ResponseEntity<?> uploadAlphaInputs(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "jobId", required = false) String requestedJobId) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body("缺少文件");
        }

        try {
            String jobId = requestedJobId == null || requestedJobId.trim().isEmpty()
                    ? newAlphaJobId()
                    : requireJobId(requestedJobId);
            File inputDir = new File(alphaJobDir(jobId), "input");
            Files.createDirectories(inputDir.toPath());

            for (MultipartFile file : files) {
                String original = file.getOriginalFilename();
                if (original == null || original.trim().isEmpty()) {
                    continue;
                }
                String name = Paths.get(original).getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!"z.asc".equals(lower) && !"h.asc".equals(lower)) {
                    return ResponseEntity.badRequest().body("仅支持 z.asc 和 h.asc，收到: " + name);
                }
                File target = new File(inputDir, lower);
                file.transferTo(target);
                if ("h.asc".equals(lower)) {
                    // 重新上传物源厚度时清掉上一次的削薄基准备份，避免基准过期
                    File backup = new File(inputDir, "h.original.asc");
                    if (backup.isFile()) {
                        Files.deleteIfExists(backup.toPath());
                    }
                }
            }

            for (String name : Arrays.asList("z.asc", "h.asc")) {
                File target = new File(inputDir, name);
                if (!target.isFile() || target.length() == 0) {
                    return ResponseEntity.badRequest().body("缺少输入文件: " + name);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("jobId", jobId);
            response.put("message", "上传成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/yj_alpha")
    public ResponseEntity<?> startAlphaModel(@RequestBody Map<String, Object> body) {
        final String jobId;
        try {
            jobId = requireJobId(str(body, "jobId"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        Double timeStepValue = optNum(body, "timeStep");
        Double durationValue = optNum(body, "duration");
        double timeStep = timeStepValue == null ? 10.0 : timeStepValue;
        double duration = durationValue == null ? 200.0 : durationValue;
        String requestedName = str(body, "name");
        final String simulationName = requestedName.length() > 60
                ? requestedName.substring(0, 60) : requestedName;

        String timeError = validateTime(timeStep, duration);
        if (timeError != null) {
            return ResponseEntity.badRequest().body(timeError);
        }

        // 物源厚度比例（%）：按比例削薄初始物源 h.asc，100 = 不削薄
        Double ratioValue = optNum(body, "sourceThicknessRatio");
        double sourceThicknessRatio = ratioValue == null ? 100.0 : ratioValue;
        if (Double.isNaN(sourceThicknessRatio) || sourceThicknessRatio <= 0 || sourceThicknessRatio > 100.0) {
            return ResponseEntity.badRequest().body("物源厚度比例需在 (0, 100] 之间");
        }

        File jobDir = alphaJobDir(jobId);
        File inputDir = new File(jobDir, "input");
        for (String name : Arrays.asList("z.asc", "h.asc")) {
            File target = new File(inputDir, name);
            if (!target.isFile() || target.length() == 0) {
                return ResponseEntity.badRequest().body("任务输入不完整: " + name);
            }
        }

        if (Math.abs(sourceThicknessRatio - 100.0) > 1e-9) {
            try {
                applySourceThicknessRatio(inputDir, sourceThicknessRatio);
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body("物源厚度比例处理失败: " + e.getMessage());
            }
        }

        File exe = new File(alphaExe);
        if (!exe.isFile()) {
            return ResponseEntity.internalServerError().body("未找到 DepthAveraged2D.exe: " + alphaExe);
        }

        Object barrierEditsRaw = body.get("barrierEdits");
        final List<Map<String, Object>> terrainEdits = toMapList(barrierEditsRaw);
        final boolean hasTerrainEdits = !terrainEdits.isEmpty();
        final File barrierFile = new File(inputDir, "barrier.asc");
        AvaflowAlphaBarrierWriter.Result barrierStats = null;
        AvaflowAlphaBarrierWriter.Result notchStats = null;
        boolean hasBarrier = false;
        boolean hasNotch = false;
        if (hasTerrainEdits) {
            try {
                barrierStats = AvaflowAlphaBarrierWriter.write(
                        new File(inputDir, "z.asc"),
                        barrierFile,
                        terrainEdits,
                        alphaSourceCrs);
                notchStats = AvaflowAlphaBarrierWriter.applyNotches(
                        new File(inputDir, "z.asc"),
                        terrainEdits,
                        alphaSourceCrs);
                hasBarrier = barrierStats.hasBarrier();
                hasNotch = notchStats.hasNotch();
                if (!hasBarrier && !hasNotch) {
                    return ResponseEntity.badRequest().body("手绘范围没有覆盖任何有效栅格");
                }
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body("地形调控文件生成失败: " + e.getMessage());
            }
        }

        File resultDir = new File(jobDir, "results");
        File paramsFile = new File(jobDir, "params.txt");
        try {
            Files.createDirectories(new File(resultDir, "asc").toPath());
            writeAlphaParams(paramsFile, timeStep, duration);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("参数文件写入失败: " + e.getMessage());
        }

        Map<String, Object> job = new ConcurrentHashMap<>();
        job.put("jobId", jobId);
        job.put("name", simulationName);
        job.put("status", "running");
        job.put("phase", "simulation");
        job.put("progress", 0);
        job.put("frames", 0);
        job.put("expectedFrames", expectedFrames(timeStep, duration));
        job.put("message", "DepthAveraged2D 计算中...");
        job.put("startedAt", System.currentTimeMillis());
        job.put("timeStep", timeStep);
        job.put("duration", duration);
        job.put("sourceThicknessRatio", sourceThicknessRatio);
        if (hasBarrier) {
            job.put("barrierEdited", true);
            job.put("barrierCells", barrierStats.barrierCells);
            job.put("barrierHeight", barrierStats.maxBarrierHeight);
        }
        if (hasNotch) {
            job.put("notchEdited", true);
            job.put("notchCells", notchStats.notchCells);
            job.put("notchDepth", notchStats.maxNotchDepth);
        }
        if (hasTerrainEdits) {
            job.put("terrainEdited", true);
            job.put("terrainEdits", barrierEditsRaw);
        }
        ALPHA_JOBS.put(jobId, job);

        final boolean barrierEnabled = hasBarrier;
        new Thread(() -> runAlphaJob(job, exe, paramsFile, inputDir, resultDir, barrierFile, barrierEnabled),
                "avaflow-alpha-" + jobId).start();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "accepted");
        response.put("jobId", jobId);
        response.put("message", "已启动，请轮询状态获取结果");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/yj_alpha_status")
    public ResponseEntity<?> alphaStatus(@RequestParam("jobId") String jobId) {
        Map<String, Object> job = ALPHA_JOBS.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body("未知任务: " + jobId);
        }

        if ("running".equals(job.get("status")) && "simulation".equals(job.get("phase"))) {
            File asciiDir = new File(new File(alphaJobDir(jobId), "results"), "asc");
            File[] frames = asciiDir.listFiles((dir, name) -> name.matches("h_\\d{6}\\.asc"));
            int count = frames == null ? 0 : frames.length;
            int expected = ((Number) job.getOrDefault("expectedFrames", 1)).intValue();
            job.put("frames", count);
            job.put("progress", Math.min(85, (int) Math.round(count * 85.0 / Math.max(1, expected))));
        }
        if ("running".equals(job.get("status"))) {
            Object startedAt = job.get("startedAt");
            if (startedAt instanceof Number) {
                job.put("elapsedSeconds", (System.currentTimeMillis() - ((Number) startedAt).longValue()) / 1000);
            }
        }
        return ResponseEntity.ok(new LinkedHashMap<>(job));
    }

    @GetMapping("/avaflow_alpha_history")
    public ResponseEntity<?> alphaHistory(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "kind", required = false) String kind) {
        int max = (limit == null || limit <= 0) ? 30 : Math.min(200, limit);
        String filter = kind == null ? "alpha" : kind.trim().toLowerCase(Locale.ROOT);
        File root = new File(alphaStaticDir, alphaStaticSubdir);
        File[] dirs = root.listFiles(File::isDirectory);
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        if (dirs != null) {
            Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
            for (File dir : dirs) {
                String jobId = dir.getName();
                if (!jobId.matches("[A-Za-z0-9_-]{1,96}")) {
                    continue;
                }
                try {
                    Map<String, Object> item = buildAlphaHistoryItem(dir, jobId);
                    if (item == null) {
                        continue;
                    }
                    boolean barrier = Boolean.TRUE.equals(item.get("barrierEdited"));
                    if ("barrier".equals(filter) && !barrier) {
                        continue;
                    }
                    if ("alpha".equals(filter) && barrier) {
                        continue;
                    }
                    total++;
                    if (items.size() < max) {
                        items.add(item);
                    }
                } catch (Exception e) {
                    System.err.println("avaflow_alpha 历史记录读取失败 " + jobId + ": " + e.getMessage());
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("total", total);
        response.put("items", items);
        return ResponseEntity.ok(response);
    }

    private void runAlphaJob(Map<String, Object> job, File exe, File paramsFile,
                             File inputDir, File resultDir, File barrierFile, boolean hasBarrier) {
        String jobId = String.valueOf(job.get("jobId"));
        try {
            List<String> command = new ArrayList<>(Arrays.asList(
                    exe.getAbsolutePath(),
                    "--model", alphaModel,
                    "--params", paramsFile.getAbsolutePath(),
                    "--input", inputDir.getAbsolutePath(),
                    "--output", resultDir.getAbsolutePath()));
            if (hasBarrier && barrierFile != null && barrierFile.isFile()) {
                command.add("--barrier");
                command.add(barrierFile.getAbsolutePath());
            }
            ProcessResult processResult = runProcess(command, exe.getParentFile(), alphaTimeoutSeconds,
                    "[avaflow-alpha] ");
            if (processResult.exitCode != 0) {
                job.put("status", "error");
                job.put("phase", "error");
                job.put("message", "DepthAveraged2D 失败: " + tail(processResult.output, 1200));
                return;
            }

            job.put("phase", "converting");
            job.put("progress", 90);
            job.put("message", "结果帧整理中...");

            File asciiDir = new File(resultDir, "asc");
            Map<String, Object> result = prepareAlphaFrames(asciiDir, jobId);
            int frameCount = ((Number) result.getOrDefault("frameCount", 0)).intValue();
            if (frameCount <= 0) {
                job.put("status", "error");
                job.put("phase", "error");
                job.put("message", "计算完成但未找到 h_######.asc 结果帧");
                return;
            }

            if (result.get("meta") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ratioMeta = (Map<String, Object>) result.get("meta");
                ratioMeta.put("sourceThicknessRatio", job.get("sourceThicknessRatio"));
            }

            boolean hasTerrainEdits = Boolean.TRUE.equals(job.get("terrainEdited"));
            boolean hasNotch = Boolean.TRUE.equals(job.get("notchEdited"));
            if (hasTerrainEdits && result.get("meta") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) result.get("meta");
                meta.put("terrainEdited", true);
                meta.put("terrainEdits", job.get("terrainEdits"));
                if (hasBarrier) {
                    meta.put("barrierEdited", true);
                    meta.put("barrierCells", job.get("barrierCells"));
                    meta.put("barrierHeight", job.get("barrierHeight"));
                }
                if (hasNotch) {
                    meta.put("notchEdited", true);
                    meta.put("notchCells", job.get("notchCells"));
                    meta.put("notchDepth", job.get("notchDepth"));
                }
            }
            job.putAll(result);
            job.put("frames", frameCount);
            writeAlphaHistoryMeta(jobId, result, job);
            job.put("status", "done");
            job.put("phase", "done");
            job.put("progress", 100);
            job.put("message", "计算完成");
            job.put("finishedAt", System.currentTimeMillis());
        } catch (Exception e) {
            job.put("status", "error");
            job.put("phase", "error");
            job.put("message", "运行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void writeAlphaHistoryMeta(String jobId, Map<String, Object> result, Map<String, Object> job) {
        try {
            Map<String, Object> history = new LinkedHashMap<>();
            history.put("jobId", jobId);
            history.put("name", job.get("name"));
            history.put("model", "avaflow_alpha");
            history.put("createdAtEpoch", job.getOrDefault("startedAt", System.currentTimeMillis()));
            history.put("frameCount", result.get("frameCount"));
            history.put("timeStep", job.get("timeStep"));
            history.put("duration", job.get("duration"));
            history.put("sourceThicknessRatio", job.get("sourceThicknessRatio"));
            history.put("barrierEdited", Boolean.TRUE.equals(job.get("barrierEdited")));
            if (Boolean.TRUE.equals(job.get("barrierEdited"))) {
                history.put("barrierCells", job.get("barrierCells"));
                history.put("barrierHeight", job.get("barrierHeight"));
                history.put("terrainEdits", job.get("terrainEdits"));
            }
            history.put("notchEdited", Boolean.TRUE.equals(job.get("notchEdited")));
            if (Boolean.TRUE.equals(job.get("notchEdited"))) {
                history.put("notchCells", job.get("notchCells"));
                history.put("notchDepth", job.get("notchDepth"));
                history.put("terrainEdits", job.get("terrainEdits"));
            }
            history.put("meta", result.get("meta"));
            File outDir = new File(new File(alphaStaticDir, alphaStaticSubdir), jobId);
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                return;
            }
            OBJECT_MAPPER.writeValue(new File(outDir, "history_meta.json"), history);
        } catch (Exception e) {
            System.err.println("avaflow_alpha 历史元数据写入失败 " + jobId + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAlphaHistoryItem(File jobDir, String jobId) throws Exception {
        File framesDir = new File(jobDir, "frames");
        if (!framesDir.isDirectory()) {
            return null;
        }
        File[] files = framesDir.listFiles((dir, name) -> name.matches("h_\\d{6}\\.asc"));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        int frameCount = files.length;

        Map<String, Object> history = new LinkedHashMap<>();
        File historyFile = new File(jobDir, "history_meta.json");
        if (historyFile.isFile()) {
            try {
                history = OBJECT_MAPPER.readValue(historyFile,
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                // 缓存损坏时按帧文件重建
            }
        }

        Map<String, Object> meta = history.get("meta") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) history.get("meta"))
                : new LinkedHashMap<>();
        if (meta.isEmpty()) {
            AscGridMetadataReader.GridInfo first = null;
            double globalMin = Double.POSITIVE_INFINITY;
            double globalMax = Double.NEGATIVE_INFINITY;
            boolean hasValue = false;
            for (File file : files) {
                AscGridMetadataReader.GridInfo info = AscGridMetadataReader.read(file);
                if (first == null) {
                    first = info;
                }
                if (info.hasValue) {
                    globalMin = Math.min(globalMin, info.minValue);
                    globalMax = Math.max(globalMax, info.maxValue);
                    hasValue = true;
                }
            }
            meta.put("sourceCrs", alphaSourceCrs);
            meta.put("field", "solid");
            meta.put("model", alphaModel);
            if (first != null) {
                meta.put("ncols", first.ncols);
                meta.put("nrows", first.nrows);
                meta.put("cellsize", first.cellSize);
                try {
                    double[] center = AscGridMetadataReader.transformCenter(first, alphaSourceCrs);
                    double[] bbox = AscGridMetadataReader.transformBbox(first, alphaSourceCrs);
                    meta.put("centerLon", center[0]);
                    meta.put("centerLat", center[1]);
                    meta.put("bbox", Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
                } catch (Exception ignored) {
                    meta.put("centerLon", null);
                    meta.put("centerLat", null);
                    meta.put("bbox", null);
                }
            }
            meta.put("globalMin", hasValue ? globalMin : 0.0);
            meta.put("globalMax", hasValue ? globalMax : 0.0);

            history.put("jobId", jobId);
            history.put("model", "avaflow_alpha");
            history.put("createdAtEpoch", createdEpochOf(jobId, jobDir));
            history.put("frameCount", frameCount);
            history.put("barrierEdited", false);
            history.put("notchEdited", false);
            history.put("meta", meta);
            try {
                OBJECT_MAPPER.writeValue(historyFile, history);
            } catch (Exception ignored) {
                // 缓存写失败不影响本次回放
            }
        }


        boolean barrierEdited = Boolean.TRUE.equals(history.get("barrierEdited"))
                || Boolean.TRUE.equals(meta.get("barrierEdited"));
        boolean notchEdited = Boolean.TRUE.equals(history.get("notchEdited"))
                || Boolean.TRUE.equals(meta.get("notchEdited"));
        if (barrierEdited) {
            meta.put("barrierEdited", true);
            meta.put("barrierCells", history.get("barrierCells"));
            meta.put("barrierHeight", history.get("barrierHeight"));
            meta.put("terrainEdits", history.get("terrainEdits"));
        }
        if (notchEdited) {
            meta.put("notchEdited", true);
            meta.put("notchCells", history.get("notchCells"));
            meta.put("notchDepth", history.get("notchDepth"));
            meta.put("terrainEdits", history.get("terrainEdits") != null
                    ? history.get("terrainEdits") : meta.get("terrainEdits"));
        }

        List<String> frameFiles = new ArrayList<>();
        for (File file : files) {
            frameFiles.add(file.getName());
        }
        String rootPath = "/ng/" + alphaStaticSubdir + "/" + jobId;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("outputBase", rootPath);
        result.put("ascBase", rootPath + "/frames");
        result.put("frameFiles", frameFiles);
        result.put("frameCount", frameCount);
        result.put("bbox", meta.get("bbox"));
        result.put("meta", meta);

        long createdEpoch = history.get("createdAtEpoch") instanceof Number
                ? ((Number) history.get("createdAtEpoch")).longValue()
                : createdEpochOf(jobId, jobDir);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("jobId", jobId);
        item.put("name", history.get("name") != null ? history.get("name") : meta.get("name"));
        item.put("createdAtEpoch", createdEpoch);
        item.put("createdAtText", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(createdEpoch)));
        item.put("frameCount", frameCount);
        item.put("globalMax", meta.getOrDefault("globalMax", 0.0));
        item.put("barrierEdited", barrierEdited);
        item.put("barrierCells", history.get("barrierCells"));
        item.put("barrierHeight", history.get("barrierHeight"));
        item.put("notchEdited", notchEdited);
        item.put("notchCells", history.get("notchCells"));
        item.put("notchDepth", history.get("notchDepth"));
        item.put("timeStep", history.get("timeStep"));
        item.put("duration", history.get("duration"));
        item.put("terrainEdited", barrierEdited || notchEdited);
        item.put("bbox", meta.get("bbox"));
        item.put("meta", meta);
        item.put("result", result);
        return item;
    }

    private long createdEpochOf(String jobId, File jobDir) {
        String[] parts = jobId.split("_");
        if (parts.length >= 2) {
            try {
                long epoch = Long.parseLong(parts[1]);
                if (epoch > 0L) {
                    return epoch;
                }
            } catch (NumberFormatException ignored) {
                // 兼容非时间戳命名的目录
            }
        }
        return jobDir.lastModified();
    }

    private Map<String, Object> prepareAlphaFrames(File asciiDir, String jobId) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        String outputBase = "/ng/" + alphaStaticSubdir + "/" + jobId;
        String ascBase = outputBase + "/frames";
        File outDir = new File(new File(alphaStaticDir, alphaStaticSubdir), jobId);
        File framesDir = new File(outDir, "frames");
        if (!framesDir.exists() && !framesDir.mkdirs()) {
            throw new IOException("无法创建结果目录: " + framesDir);
        }

        File[] files = asciiDir.listFiles((dir, name) -> name.matches("h_\\d{6}\\.asc"));
        List<String> frameFiles = new ArrayList<>();
        AscGridMetadataReader.GridInfo first = null;
        double globalMin = Double.POSITIVE_INFINITY;
        double globalMax = Double.NEGATIVE_INFINITY;
        boolean hasValue = false;

        if (files != null && files.length > 0) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File file : files) {
                AscGridMetadataReader.GridInfo info = AscGridMetadataReader.read(file);
                if (first == null) {
                    first = info;
                }
                if (info.hasValue) {
                    globalMin = Math.min(globalMin, info.minValue);
                    globalMax = Math.max(globalMax, info.maxValue);
                    hasValue = true;
                }
                Files.copy(file.toPath(), new File(framesDir, file.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                frameFiles.add(file.getName());
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceCrs", alphaSourceCrs);
        meta.put("field", "solid");
        meta.put("model", alphaModel);

        if (first != null) {
            meta.put("ncols", first.ncols);
            meta.put("nrows", first.nrows);
            meta.put("cellsize", first.cellSize);
            try {
                double[] center = AscGridMetadataReader.transformCenter(first, alphaSourceCrs);
                double[] bbox = AscGridMetadataReader.transformBbox(first, alphaSourceCrs);
                meta.put("centerLon", center[0]);
                meta.put("centerLat", center[1]);
                meta.put("bbox", Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
            } catch (Exception e) {
                meta.put("centerLon", null);
                meta.put("centerLat", null);
                meta.put("bbox", null);
            }
        }
        meta.put("globalMin", hasValue ? globalMin : 0.0);
        meta.put("globalMax", hasValue ? globalMax : 0.0);

        out.put("frameCount", frameFiles.size());
        out.put("outputBase", outputBase);
        out.put("ascBase", ascBase);
        out.put("frameFiles", frameFiles);
        out.put("meta", meta);
        out.put("bbox", meta.get("bbox"));
        out.put("jobId", jobId);
        return out;
    }

    private void writeAlphaParams(File file, double timeStep, double duration) throws IOException {
        List<String> lines = Arrays.asList(
                "# DepthAveraged2D single-phase run generated by hd-mao_0322",
                "terrain_Mode=" + alphaTerrainMode,
                "source_Mode=" + alphaSourceMode,
                "source_depth=" + alphaSourceDepth,
                "totalTime=" + formatNumber(duration),
                "interval=" + formatNumber(timeStep),
                "source_update_mode=" + alphaSourceUpdateMode,
                "mu_coulomb=" + alphaMuCoulomb,
                "n_manning=" + alphaNManning,
                "output_dat=0",
                "output_asc=1",
                "output_dataframe=0",
                "output_vtu=0");
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }

    /** ASC 头关键字，用于识别栅格数据起始行。 */
    private static final Set<String> ASC_HEADER_KEYS = new HashSet<>(Arrays.asList(
            "ncols", "nrows", "xllcorner", "yllcorner", "xllcenter", "yllcenter",
            "cellsize", "nodata_value"));

    /**
     * 按物源厚度比例（%）削薄物源厚度 h.asc：
     * 第一次调用时把原始 h.asc 备份为 h.original.asc，之后每次都基于备份重新缩放，
     * 保证多次运行（或改比例重跑）结果一致。
     */
    private static void applySourceThicknessRatio(File inputDir, double percent) throws IOException {
        File hAsc = new File(inputDir, "h.asc");
        File backup = new File(inputDir, "h.original.asc");
        if (!backup.isFile()) {
            Files.copy(hAsc.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        double ratio = percent / 100.0;
        List<String> lines = Files.readAllLines(backup.toPath(), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(lines.size());
        boolean headerDone = false;
        Double nodata = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!headerDone) {
                String key = trimmed.isEmpty() ? "" : trimmed.split("\\s+")[0].toLowerCase(Locale.ROOT);
                if (ASC_HEADER_KEYS.contains(key)) {
                    if (trimmed.split("\\s+").length > 1 && "nodata_value".equals(key)) {
                        nodata = parseAscDouble(trimmed.split("\\s+")[1]);
                    }
                    out.add(line);
                    continue;
                }
                headerDone = true;
            }
            if (trimmed.isEmpty()) {
                out.add(line);
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            StringBuilder sb = new StringBuilder(trimmed.length());
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                Double v = parseAscDouble(parts[i]);
                if (v == null || v <= 0 || (nodata != null && Math.abs(v - nodata) < 1e-6)) {
                    sb.append(parts[i]);
                } else {
                    sb.append(formatNumber(v * ratio));
                }
            }
            out.add(sb.toString());
        }
        Files.write(hAsc.toPath(), out, StandardCharsets.UTF_8);
    }

    private static Double parseAscDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (Exception e) {
            return null;
        }
    }

    private File alphaJobDir(String jobId) {
        File root = alphaJobsRoot == null || alphaJobsRoot.trim().isEmpty()
                ? new File(projectRoot, "suanfa/avaflow_alpha/jobs")
                : new File(alphaJobsRoot.trim());
        return new File(root, jobId);
    }

    private static String validateTime(double timeStep, double duration) {
        if (!(timeStep > 0) || !(duration > 0) || Double.isNaN(timeStep) || Double.isNaN(duration)) {
            return "时间步长和时间范围必须大于 0";
        }
        if (timeStep > 3600) {
            return "时间步长过大（建议 1~60 秒）";
        }
        if (duration < timeStep) {
            return "时间范围不能小于时间步长";
        }
        if (duration > 30 * 24 * 3600) {
            return "时间范围过大（上限 30 天）";
        }
        return null;
    }

    private static int expectedFrames(double timeStep, double duration) {
        return Math.max(1, (int) Math.floor(duration / timeStep) + 1);
    }

    private static String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String newAlphaJobId() {
        return "alpha_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String requireJobId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少任务 ID");
        }
        String jobId = value.trim();
        if (!jobId.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("非法任务 ID");
        }
        return jobId;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<Map<String, Object>> toMapList(Object raw) {
        if (!(raw instanceof Collection)) {
            return java.util.Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (Collection<?>) raw) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("拦挡范围格式错误");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> edit = (Map<String, Object>) item;
            result.add(edit);
        }
        return result;
    }

    private static Double optNum(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String tail(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return "..." + text.substring(text.length() - maxChars);
    }

    private static ProcessResult runProcess(List<String> command, File workDir, long timeoutSeconds,
                                            String logPrefix) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workDir != null) {
            builder.directory(workDir);
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    System.out.println(logPrefix + line);
                }
            } catch (IOException e) {
                System.err.println("读取 DepthAveraged2D 输出失败: " + e.getMessage());
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        reader.join(2000);
        String text = output.toString();
        if (!finished) {
            text = "[timeout] 进程运行超过 " + timeoutSeconds + " 秒，已被强制终止"
                    + System.lineSeparator() + text;
        }
        return new ProcessResult(process.exitValue(), text);
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
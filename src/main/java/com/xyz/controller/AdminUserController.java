package com.xyz.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;


import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.geotools.referencing.CRS;
import org.geotools.data.FileDataStore;
import org.geotools.data.FileDataStoreFinder;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.store.ReprojectingFeatureCollection;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geojson.feature.FeatureJSON;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

//import static jdk.jfr.internal.SecuritySupport.getAbsolutePath;

@RestController
@RequestMapping("/admin/user")
public class AdminUserController {

    private static String staticDir = "E:\\softwares\\nginx-1.26.2\\nginx-1.26.2\\html";

    @Value("${app.static-dir:E:\\softwares\\nginx-1.26.2\\nginx-1.26.2\\html}")
    public void setStaticDir(String dir) {
        staticDir = dir;
    }

    // ---- Python 脚本路径配置（开发环境用绝对路径，部署时在 application.yml 中覆盖）----
    @Value("${app.project-root:E:/Projects/ZHLXT/backend/hd-mao_0322}")
    private String projectRoot;

    @Value("${app.python-exe:E:/Projects/ZHLXT/backend/hd-mao_0322/scripts/python/python.exe}")
    private String pythonExe;

    @Value("${app.conda-python-exe:D:/application/miniconda3/envs/geocompy/python.exe}")
    private String condaPythonExe;

    @Value("${app.inference-script:E:/Projects/ZHLXT/demo/src/assets/src/inference.py}")
    private String inferenceScript;

    @Value("${app.transformer-script:E:/Projects/ZHLXT/算法/dzd/scripts/transformer1d.py}")
    private String transformerScript;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 冰川泥石流动力学模型（DepthAveraged2D）网格坐标系，用于把 SDP 结果重投影对齐。 */
    @Value("${app.avaflow-alpha.source-crs:EPSG:32646}")
    private String alphaSourceCrs = "EPSG:32646";

    /** 冰川泥石流动力学模型任务根目录（默认 <projectRoot>/suanfa/avaflow_alpha/jobs）。 */
    @Value("${app.avaflow-alpha.jobs-root:}")
    private String alphaJobsRoot;

    /** SDP 启动物源计算结果根目录（默认 <projectRoot>/data/SDP_Results）。 */
    private File sdpResultsRootDir() {
        return new File(projectRoot, "data/SDP_Results");
    }

    @Value("${app.process-timeout:600}")
    private long processTimeoutSeconds;

    // ---- 冰岩崩动力学模型（Pro / python_port 数值内核）----
    @Value("${app.pro.jobs-root:}")
    private String proJobsRoot;

    @Value("${app.pro.script:}")
    private String proScript;

    @Value("${app.pro.static-subdir:pro}")
    private String proStaticSubdir;

    @Value("${app.pro.timeout:3600}")
    private long proTimeoutSeconds;

    @Value("${app.pro.default-max-frames:40}")
    private int proDefaultMaxFrames;

    @Value("${app.pro.default-input-dir:}")
    private String proDefaultInputDir;

    @Value("${app.pro.source-crs:EPSG:32647}")
    private String proSourceCrs;

    @PostMapping("/fx")
    public String fxmodelparam(@RequestBody FormData formData) throws  Exception {
        // 获取表单数据
//        System.out.println(formData);
        String name = formData.getName();
        List<String> time = formData.getTime();
        String rsl = formData.getRsl();
        String color = formData.getColor();
        String depth = formData.getDepth();
        String zmax = formData.getZmax();
        String diffus = formData.getDiffus();
        String ksat = formData.getKsat();
//        System.out.println(time.get(0));
        System.out.println("接收数据成功！");
        ArrayList<String> nums_n = new ArrayList<>();

        // 批量执行多时间段（3、6、12、24h等）
        // pid -> 该进程对应的预测时段：结果图名称必须与本进程的时段一致，避免多时段并行时全部标成同一时段
        Map<String, String> pidTimeMap = new LinkedHashMap<>();
        for(int i=0;i<time.size();i++){
            int nums=time.size();
            String x = TRIGRS(projectRoot, time.get(i),rsl,depth,diffus,ksat,zmax,color,nums);
            nums_n.add(x);
            pidTimeMap.put(x, time.get(i));
        }
        Set<String> processedPids = new HashSet<>();
        Set<String> p_name = new HashSet<>();
        //检测exe执行完毕后生成相应的小时图
        // 等待各时段的 TRIGRS 进程结束，并为已结束的进程生成对应结果图
        long waitDeadline = System.currentTimeMillis() + Math.max(60000L, processTimeoutSeconds * 1000L);
        for (int i = 0; i < time.size(); i++) {
            boolean allPidsFinished = false;
            while (!allPidsFinished) {
                allPidsFinished = true;
                if (checkAndExecute(projectRoot, nums_n, color, pidTimeMap, processedPids, p_name)) {
                    break;
                }
                for (String pid : nums_n) {
                    if (isPidRunning(pid)) {
                        allPidsFinished = false;
                        break;
                    }
                }
                // 保护：进程长时间未结束（或 PID 判定异常）时不再无限等待，避免前端一直拿不到结果
                if (System.currentTimeMillis() > waitDeadline) {
                    System.out.println("[风险源模型] 等待 TRIGRS 结束超时（" + processTimeoutSeconds + "s），停止等待");
                    break;
                }
                if (!allPidsFinished) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            System.out.println("completed for time: " + time.get(i));
        }

        System.out.println("模型执行完毕");
        // 将 Set 转换为 List
        List<String> list = new ArrayList<>(p_name);
        if (list.isEmpty()) {
            System.out.println("[风险源模型] 未生成任何结果图，请查看上方异常堆栈");
            return "ERROR:结果图生成失败，请查看后端日志";
        }
        // 按用户勾选的时段顺序输出结果图，前端依次取图时不会错位
        if (time != null && !time.isEmpty()) {
            List<String> ordered = new ArrayList<>();
            for (String t : time) {
                String suffix = "_" + safeName(t) + ".png";
                for (Iterator<String> it = list.iterator(); it.hasNext(); ) {
                    String imgName = it.next();
                    if (imgName.endsWith(suffix)) {
                        ordered.add(imgName);
                        it.remove();
                        break;
                    }
                }
            }
            ordered.addAll(list); // 名称中不含时段（灰度/红绿蓝等色带）时保持原有顺序
            list = ordered;
        }
//        System.out.println(list.get(0));
        // 构建返回结果（保持前端正则解析兼容：左下经度/纬度 + 图片名称N）
        StringBuilder resp = new StringBuilder();
        resp.append("左下经度:97.50895326289057,左下纬度:31.04328676214011,右上经度:97.6075307037595,右上纬度:31.17971326461056");
        for (int idx = 0; idx < list.size(); idx++) {
            resp.append(",图片名称").append(idx + 1).append(":").append(list.get(idx));
        }
        return resp.toString();
//        return "左下经度:" + z[1] + ", 左下纬度:" + z[0] + ", 右上经度:" + z[3] + ", 右上纬度:" + z[2]+",图片名称："+z[4];
//        return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称:"+"dangerLevel_20250121_161228_914.png";
    }
    // }
//自定义formdata类型
    @Data
    public static class  FormData {
    private String name;
    private String rsl;
    private String color;
    private List<String> time; // 使用 List 接收 JSON 数组
    private String depth;
    private String zmax;
    private String diffus;
    private String Ksat;
}
    @Data
    public static class FormData1{
        private String phases;
        private String cf;
        private String bf;
        private String ff;
    }

    // 检查 PID 并在少一个时执行代码
    public static boolean checkAndExecute(String projectRoot, List<String> pids, String color, Map<String, String> pidTimeMap, Set<String> processedPids, Set<String> p_name) throws Exception {
        boolean anyPidStopped = false;
        for (String pid : pids) {
            if (!isPidRunning(pid) && !processedPids.contains(pid)) {
                // 如果有 PID 不在运行，则执行代码
                processedPids.add(pid); // 标记该 PID 已处理
                // 用该进程自己的时段命名结果图，多时段并行时不会互相串名
                String slotTime = pidTimeMap.get(pid);
                String z = null;
                try {
                    z = GrayscaleImageGenerator(projectRoot, color, slotTime);
                } catch (Exception e) {
                    System.out.println("[风险源模型] 结果图生成异常（时段 " + slotTime + "）: " + e);
                    e.printStackTrace();
                }
                if (z == null || z.trim().isEmpty()) {
                    System.out.println("[风险源模型] 结果图生成失败（时段 " + slotTime + "），详见上方异常堆栈");
                } else {
                    p_name.add(z);
                }
                anyPidStopped = true; // 标记至少有一个 PID 已停止
            }
        }
        return anyPidStopped; // 返回是否有 PID 停止运行
    }

    // 检查单个 PID 是否正在运行
    public static boolean isPidRunning(String pid) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("tasklist /FI \"PID eq " + pid + "\"");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(pid)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("检查 PID 失败: " + e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    //生成风险txt文件
    public static String TRIGRS(String projectRoot,String time,String rsl,String depth,String diffus,String ksat,String zmax,String color,int nums) throws IOException, InterruptedException {

        final String[] result1 = new String[1];
//        int[] pid = new int[nums];
        // 备份原始文件
        backupFile(new java.io.File(projectRoot, "tr_in.txt").getAbsolutePath(), new java.io.File(projectRoot, "tr_in_b.txt").getAbsolutePath());

        // 设置tr_in.txt 文件路径
//        String filePath = "D:/code/c/demo1/tr_in.txt";
        String filePath = new java.io.File(projectRoot, "tr_in.txt").getAbsolutePath();


        //读取栅格图的行列号
        String filePath3 = new java.io.File(projectRoot, "data/tutorial/dem.asc").getAbsolutePath();
        BufferedReader reader3 = new BufferedReader(new FileReader(filePath3));
        String line_1 = reader3.readLine(); // 读取第一行数据
        String line_2 = reader3.readLine(); // 读取第二行数据
        reader3.close();
        String[] line1Parts = line_1.split("\\s+");
        String[] line2Parts = line_2.split("\\s+");
        String ncols = line1Parts[1];
        String nrows = line2Parts[1];

        /*        System.out.println("第一行数据中的数字部分: " + ncols);
        System.out.println("第二行数据中的数字部分: " + nrows);*/

        //读取像元个数
        String filePath4 = new java.io.File(projectRoot, "data/tutorial/TIcelindxList_tutorial.txt").getAbsolutePath();
        BufferedReader reader4 = new BufferedReader(new FileReader(filePath4));
        int lineCount = 0;
        while (reader4.readLine() != null) {
            lineCount++;
        }
        reader4.close();
        String lineCountStr = String.valueOf(lineCount); // 将 lineCount 转换为字符串
        //System.out.println("像元个数: " + lineCountStr);

        //读取nwf（影响像元个数）
        String filePath5 = new java.io.File(projectRoot, "data/tutorial/TIwfactorList_tutorial.txt").getAbsolutePath();
        BufferedReader reader5 = new BufferedReader(new FileReader(filePath5));
        int lineCount1 = 0;
        while (reader5.readLine() != null) {
            lineCount1++;
        }
        reader5.close();
        int result = lineCount1 - lineCount * 2;
        String resultStr = String.valueOf(result); // 将 result 转换为字符串
        //System.out.println("nwf: " + resultStr);

        // 默认修改内容
        change(filePath, "xygs", lineCountStr);
        change(filePath, "h", nrows);
        change(filePath, "l", ncols);
        change(filePath, "yxxygs", resultStr);


        //用户交互修改内容
//        Scanner scanner = new Scanner(System.in);
//
//        System.out.print("Enter the new value for 'sj': ");
//        String newSj = scanner.nextLine();
//
//        System.out.print("Enter the new value for 'rsl': ");
//        String newRsl = scanner.nextLine();

        change(filePath, "time", time);
        change(filePath, "rsl", rsl);
        change(filePath, "di", diffus);
        change(filePath, "ks", ksat);
        change(filePath, "zm", zmax);
        change(filePath, "de", depth);
        /*        // 读取修改文件并打印内容
        BufferedReader reader2 = new BufferedReader(new FileReader(filePath));
        String line2;
        System.out.println("修改文件");
        while ((line2 = reader2.readLine()) != null) {
            System.out.println(line2);
        }
        reader2.close();*/
//        ExecutorService executorService = Executors.newFixedThreadPool(2);  // 创建线程池，有两个任务

//        ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "start", "D:/code/c/demo1/TRIGRS.exe");
//        Process process = builder.start();
//        // 恢复原始文件
//        restoreFile(new java.io.File(projectRoot, "tr_in.txt").getAbsolutePath(), new java.io.File(projectRoot, "tr_in_b.txt").getAbsolutePath());

//        new Thread(() -> {
//            try {
//                ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "start", "D:/code/c/demo1/TRIGRS.exe");
//                Process process = builder.start();
//                process.waitFor(); // 等待外部进程执行完毕
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }).start();
//
//        // 恢复原始文件
//        restoreFile(new java.io.File(projectRoot, "tr_in.txt").getAbsolutePath(), new java.io.File(projectRoot, "tr_in_b.txt").getAbsolutePath());

        CompletableFuture<Void> processFuture = CompletableFuture.runAsync(() -> {
            try {
                // 直接启动 TRIGRS.exe（旧写法 "cmd /c start ..." 会额外弹出一个独立的控制台窗口）
                ProcessBuilder builder = new ProcessBuilder(new java.io.File(projectRoot, "TRIGRS.exe").getAbsolutePath());
                builder.directory(new java.io.File(projectRoot));
                // 合并 stdout/stderr，子进程输出交给下面的线程转发到后端控制台
                builder.redirectErrorStream(true);
                Process trigrsProcess = builder.start();
                // 子进程不需要标准输入，立即关闭，避免个别情况下等待输入卡死
                try {
                    trigrsProcess.getOutputStream().close();
                } catch (IOException ignored) {
                }
                // 直接使用真实 PID：TRIGRS.exe 由本进程启动，无需再解析 tasklist（避免误匹配上次残留的同名进程）
                result1[0] = String.valueOf(trigrsProcess.pid());
                System.out.println("[风险源模型] TRIGRS.exe 已启动，PID=" + result1[0] + "，运行日志将打印在本终端");
                Thread logPump = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(trigrsProcess.getInputStream(), Charset.forName("GBK")))) {
                        String outLine;
                        while ((outLine = br.readLine()) != null) {
                            System.out.println("[风险源模型] " + outLine);
                        }
                    } catch (Exception ex) {
                        System.out.println("[风险源模型] 输出读取结束: " + ex.getMessage());
                    }
                }, "trigrs-console");
                logPump.setDaemon(true);
                logPump.start();
//                new Thread(() -> {
//                    try {
//                        // 模拟等待 TRIGRS.exe 完成执行
//                        boolean isRunning = true;
//                        while (isRunning) {
//                            ProcessBuilder tasklist = new ProcessBuilder("tasklist","/FI", "IMAGENAME eq TRIGRS.exe");
//                            Process tasklistProcess = tasklist.start();
//                            tasklistProcess.waitFor();
//
//                            BufferedReader reader = new BufferedReader(new InputStreamReader(tasklistProcess.getInputStream(), Charset.forName("GBK")));
//                            String line=reader.readLine();
//                            boolean found = false;
//                            String pid1;
//
//                            while (line != null) {
//                                System.out.println(line);
//                                if (line.contains(String.valueOf(pid[0]))){
//                                    found = true;
//                                    break;
//                                }
//                            }
//                            if (!found) {
//                                isRunning = false;  // 如果 TRIGRS.exe 不再运行，退出循环
//                                GrayscaleImageGenerator(color,time);
//                                System.out.println("TRIGRS.exe 执行完毕！");
//                            } else {
//                                System.out.println("睡眠1秒");
//                                Thread.sleep(1000);  // 每 1 秒检查一次
//                            }
//                        }
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    }
//                }).start();

//
            } catch (Exception e) {
                e.printStackTrace();
            }

        });
        // 主线程先等1秒再执行恢复文件的操作
        try {
            Thread.sleep(1000);  // 主线程暂停1秒
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // 同时执行恢复文件的操作
        restoreFile(new java.io.File(projectRoot, "tr_in.txt").getAbsolutePath(), new java.io.File(projectRoot, "tr_in_b.txt").getAbsolutePath());

       return result1[0];

    }
    //替换tr_in.txt的内容
    public static void change(String filePath, String oldStr, String newStr) {
        try {
            RandomAccessFile raf = new RandomAccessFile(filePath, "rw");
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = raf.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }

            String content = sb.toString();
            content = content.replaceAll("(?i)\\b" + oldStr + "\\b", newStr); // 使用正则表达式匹配替换

            raf.setLength(0); // 清空文件内容
            raf.writeBytes(content); // 写入替换后的内容

            raf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    //备份文件
    public static void backupFile(String origFilePath, String backupFilePath) throws IOException {
        Path origPath = Paths.get(origFilePath);
        Path backupPath = Paths.get(backupFilePath);
        Files.copy(origPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
    }
    //恢复文件
    public static void restoreFile(String origFilePath, String backupFilePath) throws IOException {
        Path origPath = Paths.get(origFilePath);
        Path backupPath = Paths.get(backupFilePath);

        // 如果目标文件被占用，尝试先删除再复制
        try {
            // 强制删除旧文件（如果存在）
            if (Files.exists(origPath)) {
                try {
                    Files.delete(origPath);
                } catch (IOException e) {
                    System.err.println("文件删除失败（可能被占用）: " + origPath);
                }
            }

            // 再复制新文件
            try (InputStream in = Files.newInputStream(backupPath)) {
                Files.copy(in, origPath, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (IOException e) {
            throw new IOException("文件恢复失败，可能被占用: " + origPath.toAbsolutePath(), e);
        }
    }

    // 文件名与图名匹配统一使用的安全时段标记
    private static String safeName(String time) {
        return (time == null) ? "" : time.trim().replaceAll("[^0-9A-Za-z._-]", "");
    }

    /**
     * 「灾害危险区划」静态图层列表：扫描静态目录里风险源模型输出的 dangerLevel_*.png，
     * 返回文件名、时间、降雨历时与地理范围（供前端按矩形贴图）。
     */
    @GetMapping("/danger_level_list")
    public ResponseEntity<?> dangerLevelList(@RequestParam(value = "limit", required = false) Integer limit) {
        int max = (limit == null || limit <= 0) ? 40 : Math.min(200, limit);
        File dir = new File(staticDir);
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        File[] files = dir.listFiles((d, name) ->
                name.matches("dangerLevel_\\d{8}_\\d{6}_\\d{3}_\\d+\\.png"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName).reversed());
            Pattern p = Pattern.compile("dangerLevel_(\\d{8})_(\\d{6})_(\\d{3})_(\\d+)\\.png");
            for (File f : files) {
                Matcher m = p.matcher(f.getName());
                if (!m.matches()) {
                    continue;
                }
                long duration = 0L;
                try {
                    duration = Long.parseLong(m.group(4));
                } catch (NumberFormatException ignored) {
                }
                double[] bbox = readDangerLevelBbox(f);
                if (bbox == null) {
                    continue;
                }
                String ymd = m.group(1);
                String hms = m.group(2);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", f.getName());
                item.put("url", "/ng/" + f.getName());
                item.put("timestamp", ymd + "_" + hms + "_" + m.group(3));
                item.put("timeText", ymd.substring(0, 4) + "-" + ymd.substring(4, 6) + "-" + ymd.substring(6, 8)
                        + " " + hms.substring(0, 2) + ":" + hms.substring(2, 4) + ":" + hms.substring(4, 6));
                item.put("durationSeconds", duration);
                item.put("durationText", duration > 0 && duration % 3600 == 0
                        ? (duration / 3600) + "h" : (duration > 0 ? duration + "s" : ""));
                item.put("bbox", Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
                total++;
                if (items.size() < max) {
                    items.add(item);
                }
            }
        }
        // 固定危险区划（灾害链风险源数据 → 灾害数据 → 灾害危险区划 的默认展示）：
        // 由「风险源定量识别与表征模型」的一次结果快照而来，见 <staticDir>/danger_zone/
        try {
            File fixedDir = new File(staticDir, "danger_zone");
            File fixedPng = new File(fixedDir, "hazard_zone.png");
            if (fixedPng.isFile()) {
                double[] bb = readDangerLevelBbox(fixedPng);
                if (bb != null) {
                    String srcName = "";
                    String timeText = "";
                    String durationText = "";
                    int fixedW = 0;
                    int fixedH = 0;
                    File fixedMeta = new File(fixedDir, "hazard_zone.png.json");
                    if (fixedMeta.isFile()) {
                        try {
                            Map<String, Object> fm = OBJECT_MAPPER.readValue(fixedMeta,
                                    new TypeReference<Map<String, Object>>() {
                                    });
                            srcName = fm.get("sourceFile") == null ? "" : String.valueOf(fm.get("sourceFile"));
                            timeText = fm.get("timeText") == null ? "" : String.valueOf(fm.get("timeText"));
                            durationText = fm.get("durationText") == null ? "" : String.valueOf(fm.get("durationText"));
                            if (fm.get("ncols") instanceof Number) {
                                fixedW = ((Number) fm.get("ncols")).intValue();
                            }
                            if (fm.get("nrows") instanceof Number) {
                                fixedH = ((Number) fm.get("nrows")).intValue();
                            }
                        } catch (Exception ignored) {
                        }
                        if (fixedW <= 0 || fixedH <= 0) {
                            // 边车里没有尺寸时直接读 PNG 头
                            try {
                                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(fixedPng);
                                if (img != null) {
                                    fixedW = img.getWidth();
                                    fixedH = img.getHeight();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    Map<String, Object> fixed = new LinkedHashMap<>();
                    fixed.put("file", "hazard_zone");
                    fixed.put("url", "/ng/danger_zone/hazard_zone.png");
                    fixed.put("fixed", true);
                    fixed.put("timestamp", "");
                    fixed.put("durationSeconds", 0);
                    fixed.put("durationText", durationText);
                    fixed.put("timeText", "固定危险区划"
                            + (timeText.isEmpty() ? "" : "（" + timeText
                            + (durationText.isEmpty() ? "" : " · " + durationText) + "）"));
                    fixed.put("sourceFile", srcName);
                    fixed.put("width", fixedW > 0 ? fixedW : 879);
                    fixed.put("height", fixedH > 0 ? fixedH : 1553);
                    fixed.put("bbox", Arrays.asList(bb[0], bb[1], bb[2], bb[3]));
                    items.add(0, fixed);
                    total++;
                }
            }
        } catch (Exception e) {
            System.err.println("固定危险区划读取失败: " + e.getMessage());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("total", total);
        resp.put("items", items);
        return ResponseEntity.ok(resp);
    }

    /**
     * 危险区划 PNG 的地理范围：优先读同名 .json 边车；
     * 旧图（风险源模型早期版本没有边车）回落到 TRIGRS 教程网格的范围：
     * data/tutorial/dem.asc，879 x 1553 @10m，EPSG:32646 (930466.85, 3443145.16)-(939256.85, 3458675.16)
     * 换算到 WGS84 即下方常量（与前端原有的 leftlong/rightlong 默认取景一致）。
     */
    private double[] readDangerLevelBbox(File png) {
        File sidecar = new File(png.getAbsolutePath() + ".json");
        if (sidecar.isFile()) {
            try {
                Map<String, Object> m = OBJECT_MAPPER.readValue(sidecar,
                        new TypeReference<Map<String, Object>>() {
                        });
                Object w = m.get("west");
                Object s = m.get("south");
                Object e = m.get("east");
                Object n = m.get("north");
                if (w instanceof Number && s instanceof Number && e instanceof Number && n instanceof Number) {
                    double ww = ((Number) w).doubleValue();
                    double ss = ((Number) s).doubleValue();
                    double ee = ((Number) e).doubleValue();
                    double nn = ((Number) n).doubleValue();
                    // 历史数据：早期版本经纬度写反（west 里是纬度、south 里是经度），这里自动纠正
                    if (Math.abs(ww) <= 90 && Math.abs(ss) > 90) {
                        double tmp = ww;
                        ww = ss;
                        ss = tmp;
                        tmp = ee;
                        ee = nn;
                        nn = tmp;
                    }
                    return new double[] { ww, ss, ee, nn };
                }
            } catch (Exception ignored) {
                // 边车损坏时回落默认范围
            }
        }
        return new double[] { 97.508953, 31.040039, 97.607531, 31.182979 };
    }

    //风险txt文件转为png
    public static String GrayscaleImageGenerator(String projectRoot,String color,String time) throws Exception {
        System.out.println("开始生成图-----");
        String File = new java.io.File(projectRoot, "data/result/TRfs_min_tutorial_1.txt").getAbsolutePath(); // 输入文件路径
        // 输出目录不存在时先创建，避免 ImageIO 写文件失败导致前端拿不到结果
        java.io.File outDir = new java.io.File(staticDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        // 文件名中的时段标记只保留安全字符，避免非法文件名
        String safeTime = safeName(time);
        // 自动创建唯一的临时文件，前缀为 "temp_"，后缀为 ".txt"
        Path inputFile = Files.createTempFile("temp_", ".txt");
        // 拷贝原文件到临时文件：TRIGRS 正在写结果时文件可能被短暂占用，重试几次再放弃
        IOException copyError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Files.copy(Paths.get(File), inputFile, StandardCopyOption.REPLACE_EXISTING);
                copyError = null;
                break;
            } catch (IOException e) {
                copyError = e;
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (copyError != null) {
            try {
                Files.deleteIfExists(inputFile);
            } catch (IOException ignored) {
            }
            System.out.println("[风险源模型] 结果文件读取失败: " + copyError);
            return null;
        }
        int width = 0; // 图像宽度
        int height = 0; // 图像高度
        int cellSize = 0; // 每个像元格的大小
        int noFillValue = 0; // 不填充的值
        double x1 = 0.0, x2 = 0.0, x3 = 0.0, x4 = 0.0; // 顶点坐标
        double y1 = 0.0, y2 = 0.0, y3 = 0.0, y4 = 0.0;
        double[] z=null;
        String q =null;
        String colorScheme = color; // 用户选择的色带类型: "gray", "rgb", "redGradient","dangerLevel"等

        try (BufferedReader br =  Files.newBufferedReader(inputFile)){
            // 读取文件数据
            String line = br.readLine();
            width = Integer.parseInt(line.split("         ")[1]);
            line = br.readLine();
            height = Integer.parseInt(line.split("         ")[1]);

            line = br.readLine();
            x1 = Double.parseDouble(line.split("     ")[1]);
            line = br.readLine();
            y1 = Double.parseDouble(line.split("     ")[1]);

            line = br.readLine();
            double cell = Double.parseDouble(line.split("      ")[1]);
            cellSize = (int) cell;
            line = br.readLine();
            noFillValue = Integer.parseInt(line.split("  ")[1]);
            // 设置顶点坐标和坐标转换
            x2 = x1;
            x3 = x1 + width * cellSize;
            x4 = x3;
            y2 = y1 + height * cellSize;
            y3 = y2;
            y4 = y1;

            // 必须指定 true（强制 XY：easting,northing -> lon,lat）；否则 GeoTools 按 EPSG 轴序
            // 解释成 (northing, easting)，写出的经纬度会左右/上下颠倒
            CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:32646", true);
            CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326", true);
            MathTransform transformToWGS84 = CRS.findMathTransform(sourceCRS, targetCRS);

            double[] srcPts = {x1, y1, x2, y2, x3, y3, x4, y4};
            double[] destPts = new double[8];
            transformToWGS84.transform(srcPts, 0, destPts, 0, 4);
            // 创建图像
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = image.createGraphics();
            int row = 0;
            while ((line = br.readLine()) != null && row < height) {
                String[] values = line.split(" ");
                for (int col = 0; col < values.length && col < width; col++) {
                    String valueStr = values[col].trim();
                    double value = Double.parseDouble(valueStr);

                    try {
                        if (value == noFillValue) {
                            g2d.setColor(new Color(0, 0, 0, 0));
                        } else {
                            // 根据用户选择的色带类型设置颜色
                            Color fillColor = getColorFromValue(value, colorScheme);
                            g2d.setColor(fillColor);
                        }
                        g2d.fillRect(col, row, 1, 1);
                    } catch (NumberFormatException e) {
                        System.err.println("无效的数字: " + valueStr);
                    }
                }
                row++;
            }
            g2d.dispose();
            // 生成唯一的文件名
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String uniqueFileName="";
            if(color.equals("gray")){
                uniqueFileName= "gray_" + timestamp + ".png";
            }else if(color.equals("rgb")){
                uniqueFileName= "rgb_" + timestamp + ".png";
            }
            else if(color.equals("dangerLevel")){
                uniqueFileName= "dangerLevel_" + timestamp + "_" + safeTime + ".png";
            }else{
                uniqueFileName= "redGradient_" + timestamp + ".png";
            }
            ImageIO.write(image, "png", new java.io.File(staticDir, uniqueFileName));
//            System.out.println("临时文件已销毁。");
            System.out.println("图像生成为:"+uniqueFileName);
            z=destPts;
            q=uniqueFileName;
            // 同步记录该图的地理范围（WGS84），供「数值计算模型集 → 区域灾害本底数据点位 → 灾害危险区划」
            // 作为静态图层直接贴图回放（旧图没有边车，接口会回落到 TRIGRS 教程网格范围）。
            try {
                Map<String, Object> sidecar = new LinkedHashMap<>();
                sidecar.put("file", uniqueFileName);
                sidecar.put("color", color);
                sidecar.put("time", safeTime);
                sidecar.put("west", z[0]);
                sidecar.put("south", z[1]);
                sidecar.put("east", z[4]);
                sidecar.put("north", z[5]);
                sidecar.put("ncols", width);
                sidecar.put("nrows", height);
                OBJECT_MAPPER.writeValue(new java.io.File(staticDir, uniqueFileName + ".json"), sidecar);
            } catch (Exception ignored) {
                // 边车写入失败不影响主流程，接口会回落到默认范围
            }
        } catch (IOException e) {
            System.out.println("[风险源模型] 结果图生成失败: " + e);
            e.printStackTrace();
        } finally {
            try {
                Files.deleteIfExists(inputFile);
            } catch (IOException ignored) {
            }
        }
//        return new String[] {
//                String.valueOf(z[0]),
//                String.valueOf(z[1]),
//                String.valueOf(z[4]),
//                String.valueOf(z[5]),
//                q
//        };
        return q;
    }
    //调整png色带显示
    private static Color getColorFromValue(double value, String colorScheme) {
        int grayValue;
        switch (colorScheme) {
            case "gray":
                grayValue = (int) Math.max(0, Math.min(10, value));
                grayValue = (int) (grayValue * 25.5);
                return new Color(grayValue, grayValue, grayValue, 255);

            case "rgb":
                if (value <= 3) {
                    int red = (int) (value / 3 * 255);
                    return new Color(red, 0, 0, 255);
                } else if (value <= 6) {
                    int green = (int) ((value - 3) / 3 * 255);
                    return new Color(255 - green, green, 0, 255);
                } else {
                    int blue = (int) ((value - 6) / 4 * 255);
                    return new Color(0, 255 - blue, blue, 255);
                }
            case "dangerLevel":
                if (value <= 1) {
                    // Red: Extreme risk
                    return new Color(212, 48, 48, 255);//212, 48, 48
                } else if (value <= 3.4) {
                    // Yellow: High risk
                    return new Color(230, 141, 26, 255);
                } else if (value <= 6.7) {
                    // Orange: Medium risk
                    return new Color(230, 195, 0, 220);//255, 195, 0
                } else {
                    // Blue: Low risk
                    return new Color(42, 130, 228, 255);//42, 130, 228
                }
            case "redGradient":
                int redIntensity = (int) Math.max(0, Math.min(10, value));
                redIntensity = (int) (redIntensity * 25.5);
                return new Color(redIntensity, 0, 0, 255);

            default:
                grayValue = (int) Math.max(0, Math.min(10, value));
                grayValue = (int) (grayValue * 25.5);
                return new Color(grayValue, grayValue, grayValue, 255);
        }
    }
    /**
     * 冰川泥石流易发性预测模型：调用 Python 推理脚本，返回 GeoJSON。
     * <p>
     * 说明：推理脚本的输出 shapefile 路径固定（waternet_results1.shp，先删后写），
     * 因此这里必须串行执行，避免多个请求同时读写同一份输出文件。
     */
    private static final Object GBM_INFERENCE_LOCK = new Object();

    @PostMapping("/GBM")
    public ResponseEntity<?> runInference(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
            @SuppressWarnings("unchecked")
            Map<String, Object> form = body.get("form") instanceof Map
                    ? (Map<String, Object>) body.get("form")
                    : new HashMap<>();
            String jsonStr = OBJECT_MAPPER.writeValueAsString(form);

            if (files == null || files.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing 'files'");
            }

            // shp / dbf / shx / prj 一起上传时，以 .shp 作为入口；同名附属文件由脚本按同名规则读取
            Map<String, Object> shpEntry = null;
            for (Map<String, Object> f : files) {
                Object p = f == null ? null : f.get("savedPath");
                if (p != null && p.toString().toLowerCase().endsWith(".shp")) {
                    shpEntry = f;
                    break;
                }
            }
            if (shpEntry == null) {
                shpEntry = files.get(0);
            }

            String firstPath = shpEntry == null ? null : (String) shpEntry.get("savedPath");
            if (firstPath == null || firstPath.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Invalid file path");
            }
            File firstFile = new File(firstPath);
            String folder = firstFile.getParent();
            String shpfile = firstFile.getName();

            synchronized (GBM_INFERENCE_LOCK) {
                ProcessResult pr = runProcess(
                        Arrays.asList(pythonExe, inferenceScript, shpfile, jsonStr),
                        null, processTimeoutSeconds, "[Python] ", StandardCharsets.UTF_8);

                String outputShpPath = extractSentinel(pr.output, "OUTPUT_PATH=");
                // 推理脚本内部异常时仍可能以 0 退出，因此以「是否拿到输出路径」为准
                if (pr.exitCode != 0 || outputShpPath.isEmpty()) {
                    return ResponseEntity.internalServerError()
                            .body("推理失败：" + tailOf(pr.output, 800));
                }

                File shpFile = new File(outputShpPath);
                if (!shpFile.exists()) {
                    return ResponseEntity.internalServerError()
                            .body("推理输出文件不存在：" + outputShpPath);
                }

                //  Shapefile to GeoJSON
                System.setProperty("org.geotools.shapefile.charset", "GBK");
                ShapefileDataStore store = new ShapefileDataStore(shpFile.toURI().toURL());
                store.setCharset(Charset.forName("GBK"));
                SimpleFeatureSource featureSource = store.getFeatureSource();
                SimpleFeatureCollection collection = featureSource.getFeatures();

                CoordinateReferenceSystem sourceCRS = featureSource.getSchema().getCoordinateReferenceSystem();
                if (sourceCRS == null) {
                    sourceCRS = CRS.decode("EPSG:32646", true);
                }
                CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326", true);
                if (!CRS.equalsIgnoreMetadata(sourceCRS, targetCRS)) {
                    collection = new ReprojectingFeatureCollection(collection, targetCRS);
                }

                FeatureJSON fjson = new FeatureJSON();
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                fjson.writeFeatureCollection(collection, os);
                String geojson = os.toString();

                Map<String, Object> resp = new HashMap<>();
                resp.put("status", "ok");
                resp.put("geojson", geojson);
                resp.put("folder", folder);
                return ResponseEntity.ok(resp);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }


    @PostMapping("/seismic")
    public ResponseEntity<?> processSeismic(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> fileInfo = (Map<String, String>) body.get("file");
            if (fileInfo == null || fileInfo.get("savedPath") == null) {
                return ResponseEntity.badRequest().body("Missing 'file.savedPath'");
            }
            String excelPath = fileInfo.get("savedPath");
            File excelFile = new File(excelPath);
            if (!excelFile.exists()) {
                return ResponseEntity.badRequest().body("Excel file not found: " + excelPath);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) body.get("params");
            if (params == null) {
                params = new HashMap<>();
            }

            double threshold = params.containsKey("threshold") ? Double.parseDouble(params.get("threshold").toString()) : 2.5;
            int short_window = params.containsKey("short_window") ? Integer.parseInt(params.get("short_window").toString()) : 30;
            int long_window = params.containsKey("long_window") ? Integer.parseInt(params.get("long_window").toString()) : 240;
            int segment_duration = params.containsKey("segment_duration") ? Integer.parseInt(params.get("segment_duration").toString()) : 10;
            int total_duration = params.containsKey("total_duration") ? Integer.parseInt(params.get("total_duration").toString()) : 60;
            int sampling_rate = params.containsKey("sampling_rate") ? Integer.parseInt(params.get("sampling_rate").toString()) : 100;

            String pythonSeismicScript = projectRoot + "/suanfa/seismic/seismic.py";
            ProcessResult pr = runProcess(
                    Arrays.asList(pythonExe, pythonSeismicScript, excelPath,
                            String.valueOf(threshold),
                            String.valueOf(short_window),
                            String.valueOf(long_window),
                            String.valueOf(segment_duration),
                            String.valueOf(total_duration),
                            String.valueOf(sampling_rate)),
                    null, processTimeoutSeconds, "[Python] ", StandardCharsets.UTF_8);
            if (pr.exitCode != 0) {
                // 带上 Python 侧日志尾部，便于定位（此前只有一句 Python script failed）
                return ResponseEntity.internalServerError()
                        .body("Python script failed: " + tail(pr.output, 800));
            }

            String outputJson = extractSentinel(pr.output, "RESULT_JSON=");
            // echarts 数据优先从文件读取（脚本会输出 echarts_file=...），
            // 兼容旧脚本直接把整段 JSON 打到 stdout 的 echarts= 写法
            String echarts_data = "";
            String echartsFile = extractSentinel(pr.output, "echarts_file=");
            if (!echartsFile.isEmpty()) {
                try {
                    File ef = new File(echartsFile.trim());
                    echarts_data = new String(Files.readAllBytes(ef.toPath()), StandardCharsets.UTF_8);
                    ef.delete(); // 读取后清理临时文件
                } catch (Exception e) {
                    System.err.println("[seismic] 读取 echarts 文件失败: " + e.getMessage());
                }
            }
            if (echarts_data.isEmpty()) {
                echarts_data = extractSentinel(pr.output, "echarts=");
            }
            if (outputJson.isEmpty()) {
                return ResponseEntity.internalServerError()
                        .body("No result from Python: " + tail(pr.output, 800));
            }

            boolean detected = outputJson.contains("\"detected\": true");
            Map<String, Object> resp = new HashMap<>();
            resp.put("detected", detected);
            resp.put("echarts_data", echarts_data);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }    @PostMapping("/SDP_Start")
    public ResponseEntity<?> processSDPStart(@RequestBody Map<String, Object> body) {
        String pythonScript = projectRoot + "/suanfa/SDP_Start/python/run.py";
        String convertScript = projectRoot + "/suanfa/SDP_Start/python/tif_to_json.py";

        String rainPath = body.containsKey("rain_path") ? body.get("rain_path").toString() : projectRoot + "/suanfa/SDP_Start/rainfall_tif";
        String tempPath = body.containsKey("temp_path") ? body.get("temp_path").toString() : projectRoot + "/suanfa/SDP_Start/tem_tif";
        double iceContent = body.containsKey("ice_content") ? Double.parseDouble(body.get("ice_content").toString()) : 0.2;
        double zmaxBoost = body.containsKey("zmax_boost") ? Double.parseDouble(body.get("zmax_boost").toString()) : 0.0;
        String tempPattern = body.containsKey("temp_pattern") ? body.get("temp_pattern").toString() : "temp_%d.tif";
        String sdpName = str(body, "name");
        if (sdpName.length() > 60) {
            sdpName = sdpName.substring(0, 60);
        }

        // 每次运行独立目录，完整保留历史结果
        String runId = newSdpRunId();
        File outputDir = new File(sdpResultsRootDir(), runId);
        try {
            Files.createDirectories(outputDir.toPath());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("创建 SDP 结果目录失败: " + e.getMessage());
        }

        try {
            ProcessResult pr = runProcess(
                    Arrays.asList(pythonExe, pythonScript,
                            "--rain_path", rainPath,
                            "--temp_path", tempPath,
                            "--output_dir", outputDir.getAbsolutePath(),
                            "--ice_content", String.valueOf(iceContent),
                            "--temp_pattern", tempPattern,
                            "--zmax_boost", String.valueOf(zmaxBoost)),
                    new File(projectRoot), processTimeoutSeconds, "SDP Python: ", Charset.forName("GBK"));
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError().body("run.py 执行失败，退出码：" + pr.exitCode);
            }

            String tifPath = pickSdpZmaxFile(outputDir);
            if (tifPath == null) {
                return ResponseEntity.internalServerError().body("未找到 ZMAX 结果栅格: " + outputDir.getAbsolutePath());
            }

            // 单次进程转换最终 tif（tif_to_json.py 内部统一转为 WGS84 边界 + PNG 预览）
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add(convertScript);
            cmd.add(tifPath);
            ProcessResult pr2 = runProcess(cmd, new File(projectRoot), processTimeoutSeconds, "Convert: ", StandardCharsets.UTF_8);
            if (pr2.exitCode != 0) {
                return ResponseEntity.internalServerError().body("tif_to_json.py 执行失败");
            }
            String jsonResult = extractSentinel(pr2.output, "RESULT_JSON=");
            if (jsonResult.isEmpty()) {
                return ResponseEntity.internalServerError().body("tif_to_json.py 未返回结果");
            }
            List<Map<String, Object>> rawFrames = parseFrameList(jsonResult);
            if (rawFrames.isEmpty()) {
                return ResponseEntity.internalServerError().body("tif_to_json.py 未返回有效帧");
            }

            Map<String, Object> rawFrame = rawFrames.get(rawFrames.size() - 1);
            Map<String, Object> frame = buildFrame(rawFrame);
            frame.put("runId", runId);
            frame.put("name", sdpName);

            // 落盘元数据：供 SDP 历史列表与动力学模型串联使用
            writeSdpResultMeta(runId, outputDir, sdpName, frame, rawFrame, tifPath, body);
            return ResponseEntity.ok(frame);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }


    @PostMapping("/avainit")
    public ResponseEntity<?> runAvainit(@RequestBody Map<String, Object> body) {
        return runAvainitScript("bedding.py", Arrays.asList(
                str(body, "melt_duration"), str(body, "slope_angle"), str(body, "slide_angle"),
                str(body, "ice_thickness"), str(body, "fissure_height"), str(body, "slide_length"),
                str(body, "cohesion"), str(body, "friction_angle"), str(body, "rock_density"),
                str(body, "permeability")));
    }

    @PostMapping("/bedding_inverse")
    public ResponseEntity<?> runBeddingInverse(@RequestBody Map<String, Object> body) {
        return runAvainitScript("bedding_inverse.py", Arrays.asList(
                str(body, "melt_duration"), str(body, "slope_angle"), str(body, "inverse_angle"),
                str(body, "ice_thickness"), str(body, "slope_height"), str(body, "bedding_space"),
                str(body, "cohesion"), str(body, "friction_angle"), str(body, "rock_density"),
                str(body, "permeability")));
    }

    @PostMapping("/bedding_wedge")
    public ResponseEntity<?> runBeddingWedge(@RequestBody Map<String, Object> body) {
        return runAvainitScript("bedding_wedget.py", Arrays.asList(
                str(body, "melt_duration"), str(body, "slope_angle"), str(body, "normal_vector"),
                str(body, "ice_thickness"), str(body, "square"), str(body, "slope_height"),
                str(body, "fracture"), str(body, "cohesion"), str(body, "friction_angle"),
                str(body, "rock_density"), str(body, "permeability")));
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : v.toString();
    }

    /** 统一调用冰岩崩启动模型 Python 脚本，解析 stdout 的 RESULT_JSON 并回传。 */
    private ResponseEntity<?> runAvainitScript(String scriptName, List<String> args) {
        try {
            String scriptPath = projectRoot + "/suanfa/avainit/" + scriptName;
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add(scriptPath);
            cmd.addAll(args);
            ProcessResult pr = runProcess(cmd, new File(projectRoot), processTimeoutSeconds, "[avainit] ", StandardCharsets.UTF_8);
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError().body("脚本执行失败，退出码：" + pr.exitCode);
            }
            String jsonResult = extractSentinel(pr.output, "RESULT_JSON=");
            if (jsonResult.isEmpty()) {
                return ResponseEntity.internalServerError().body("脚本未返回结果");
            }
            Map<String, Object> result = OBJECT_MAPPER.readValue(jsonResult, new TypeReference<Map<String, Object>>() {});
            if (result != null && result.containsKey("error")) {
                return ResponseEntity.badRequest().body(String.valueOf(result.get("error")));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    // ===================================================================== //
    // 冰岩崩动力学模型（Pro）：调用 suanfa/Pro/python_port 数值内核
    // ===================================================================== //

    /** jobId -> 任务状态，供 /pro_start_status 轮询 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> proJobs =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Pro 任务根目录（默认 <projectRoot>/suanfa/Pro/jobs）。 */
    private File proJobsRootDir() {
        String root = (proJobsRoot == null || proJobsRoot.trim().isEmpty())
                ? projectRoot + "/suanfa/Pro/jobs"
                : proJobsRoot.trim();
        return new File(root);
    }

    /** Pro 数值内核包装脚本路径（默认 <projectRoot>/suanfa/Pro/run_pro.py）。 */
    private String proScriptPath() {
        return (proScript == null || proScript.trim().isEmpty())
                ? projectRoot + "/suanfa/Pro/run_pro.py"
                : proScript.trim();
    }

    /**
     * Pro 输入数据目录：请求传入优先，其次取配置，都缺省时用
     * suanfa/Pro/user1/task；必须位于 projectRoot 之下，避免任意路径读取。
     */
    private File resolveProInputDir(String requested) {
        String raw = (requested == null || requested.trim().isEmpty()) ? proDefaultInputDir : requested;
        if (raw == null || raw.trim().isEmpty()) {
            raw = "suanfa/Pro/user1/task";
        }
        raw = raw.trim();
        File base = new File(projectRoot).getAbsoluteFile();
        File dir = new File(raw);
        if (!dir.isAbsolute()) {
            dir = new File(base, raw);
        }
        dir = dir.getAbsoluteFile();
        String basePath = base.getPath();
        String dirPath = dir.getPath();
        if (!dirPath.equals(basePath) && !dirPath.startsWith(basePath + File.separator)) {
            throw new IllegalArgumentException("\u8f93\u5165\u76ee\u5f55\u5fc5\u987b\u4f4d\u4e8e\u9879\u76ee\u6839\u76ee\u5f55\u5185: " + raw);
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("\u8f93\u5165\u76ee\u5f55\u4e0d\u5b58\u5728: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** Pro 模型输入允许的扩展名：tif/tiff 走 GDAL，asc/txt 走 ESRI ASCII 头部。 */
    private static final List<String> PRO_INPUT_EXTS = Arrays.asList("tif", "tiff", "asc", "txt");

    /** 取上传文件名的扩展名；不在白名单内时按 tif 处理。 */
    private static String proInputExt(String originalName) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (PRO_INPUT_EXTS.contains(ext)) {
                    return ext;
                }
            }
        }
        return "tif";
    }

    /** 上传落盘文件名：保留原扩展名，形如 zb.txt / hw.asc / zl.tif。 */
    private static String proInputFileName(String key, String originalName) {
        return key + "." + proInputExt(originalName);
    }

    /** 删除同名的其它扩展名残留，避免重复上传时新旧格式同时存在。 */
    private static void removeOtherProInputs(File inputDir, String key, String keepName) {
        for (String ext : PRO_INPUT_EXTS) {
            File stale = new File(inputDir, key + "." + ext);
            if (!stale.getName().equals(keepName) && stale.isFile() && !stale.delete()) {
                System.err.println("[pro_upload] 旧输入清理失败: " + stale.getAbsolutePath());
            }
        }
    }

    /** 是否已上传 zb/zl/hw 三幅输入（tif/tiff/asc/txt 任一格式）到 <jobDir>/inputs。 */
    private static boolean hasUploadedProInputs(File inputDir) {
        if (inputDir == null || !inputDir.isDirectory()) {
            return false;
        }
        for (String key : Arrays.asList("zb", "zl", "hw")) {
            boolean found = false;
            for (String ext : PRO_INPUT_EXTS) {
                File f = new File(inputDir, key + "." + ext);
                if (f.isFile() && f.length() > 0) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static String proFrameName(String prefix, int index) {
        return String.format(Locale.ROOT, "%s_hflow%04d.asc", prefix, index);
    }

    private static double numOf(Map<String, Object> map, String key, double fallback) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 先查 params，再查 body 顶层的可选数值；用于无头部 txt 的网格中心经纬度锚点。 */
    private static Double optNum(Map<String, Object> map, String key, Map<String, Object> fallbackMap) {
        Object v = map == null ? null : map.get(key);
        if (v == null && fallbackMap != null) {
            v = fallbackMap.get(key);
        }
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 依据文件名猜测输入栅格类型：zb(灾前地形) / zl(灾后地形) / hw(初始水深)。 */
    private static String classifyProInput(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("zl") || n.contains("post") || n.contains("\u540e")) {
            return "zl";
        }
        if (n.contains("hw") || n.contains("water") || n.contains("\u6c34\u6df1")) {
            return "hw";
        }
        if (n.contains("zb") || n.contains("elev") || n.contains("dem")) {
            return "zb";
        }
        return null;
    }

    /**
     * 上传 Pro 模型的三幅输入栅格（灾前地形 / 灾后地形 / 初始水深），并立即探测网格信息。
     * 支持命名部件 zb/zl/hw，也支持 files[] 按文件名自动识别。
     * 输入格式支持 GeoTIFF（tif/tiff）与带 ESRI ASCII 头部的文本（asc/txt），落盘时保留原扩展名。
     */
    @PostMapping("/pro_upload")
    public ResponseEntity<?> uploadProInputs(
            @RequestParam(value = "zb", required = false) MultipartFile zb,
            @RequestParam(value = "zl", required = false) MultipartFile zl,
            @RequestParam(value = "hw", required = false) MultipartFile hw,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "jobId", required = false) String requestedJobId,
            @RequestParam(value = "sourceCrs", required = false) String sourceCrs,
            @RequestParam(value = "anchorLon", required = false) String anchorLon,
            @RequestParam(value = "anchorLat", required = false) String anchorLat) {
        try {
            String jobId = (requestedJobId == null || requestedJobId.trim().isEmpty())
                    ? "pro_" + System.currentTimeMillis() + "_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                    : requireAvaflowJobId(requestedJobId);
            File jobDir = new File(proJobsRootDir(), jobId);
            File inputDir = new File(jobDir, "inputs");
            Files.createDirectories(inputDir.toPath());

            Map<String, MultipartFile> picked = new LinkedHashMap<>();
            if (zb != null && !zb.isEmpty()) {
                picked.put("zb", zb);
            }
            if (zl != null && !zl.isEmpty()) {
                picked.put("zl", zl);
            }
            if (hw != null && !hw.isEmpty()) {
                picked.put("hw", hw);
            }
            if (files != null) {
                for (MultipartFile f : files) {
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    String key = classifyProInput(f.getOriginalFilename());
                    if (key != null) {
                        picked.putIfAbsent(key, f);
                    }
                }
                // 文件名无法识别时按 zb / zl / hw 顺序补齐空位
                for (MultipartFile f : files) {
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    for (String key : Arrays.asList("zb", "zl", "hw")) {
                        if (!picked.containsKey(key)) {
                            picked.put(key, f);
                            break;
                        }
                    }
                }
            }
            for (String key : Arrays.asList("zb", "zl", "hw")) {
                if (!picked.containsKey(key)) {
                    return ResponseEntity.badRequest().body("\u7f3a\u5c11\u8f93\u5165\u6587\u4ef6: " + key);
                }
            }

            Map<String, String> savedInputs = new LinkedHashMap<>();
            for (Map.Entry<String, MultipartFile> e : picked.entrySet()) {
                // 保留原始扩展名：txt/asc（ESRI ASCII）不再被强行改名成 .tif
                String fileName = proInputFileName(e.getKey(), e.getValue().getOriginalFilename());
                removeOtherProInputs(inputDir, e.getKey(), fileName);
                File target = new File(inputDir, fileName);
                e.getValue().transferTo(target);
                savedInputs.put(e.getKey(), fileName);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("jobId", jobId);
            resp.put("inputs", savedInputs);

            String probeCrs = (sourceCrs == null || sourceCrs.trim().isEmpty()) ? proSourceCrs : sourceCrs.trim();
            List<String> probeCmd = new ArrayList<>(Arrays.asList(
                    pythonExe, proScriptPath(), "--probe", "--job-dir", jobDir.getAbsolutePath(),
                    "--source-crs", probeCrs));
            if (anchorLon != null && !anchorLon.trim().isEmpty()
                    && anchorLat != null && !anchorLat.trim().isEmpty()) {
                probeCmd.add("--anchor-lon");
                probeCmd.add(anchorLon.trim());
                probeCmd.add("--anchor-lat");
                probeCmd.add(anchorLat.trim());
            }
            ProcessResult pr = runProcess(
                    probeCmd,
                    new File(projectRoot), 300, "[pro_probe] ", StandardCharsets.UTF_8);
            String probeJson = extractSentinel(pr.output, "PROBE_JSON=");
            if (probeJson.isEmpty()) {
                resp.put("status", "error");
                resp.put("message", "\u8f93\u5165\u6805\u683c\u63a2\u6d4b\u5931\u8d25: " + tail(pr.output, 500));
                return ResponseEntity.badRequest().body(resp);
            }
            Map<String, Object> probe = OBJECT_MAPPER.readValue(probeJson, new TypeReference<Map<String, Object>>() {
            });
            resp.put("probe", probe);
            if (!"ok".equals(String.valueOf(probe.get("status")))) {
                resp.put("status", "error");
                resp.put("message", String.valueOf(probe.get("message")));
                return ResponseEntity.badRequest().body(resp);
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("\u4e0a\u4f20\u5931\u8d25: " + e.getMessage());
        }
    }

    /**
     * 启动 Pro 模型：后端调用 suanfa/Pro/run_pro.py（python_port 数值内核），
     * 结果直接写成 ASC 帧，前端复用 DebrisFlow 渲染 + 时间轴。
     */
    @PostMapping("/pro_start")
    public ResponseEntity<?> startProModel(@RequestBody Map<String, Object> body) {
        String requestedJobId = str(body, "jobId");
        final String jobId;
        if (requestedJobId == null || requestedJobId.trim().isEmpty()) {
            // 前端只回传参数时（无上传文件）由后端生成任务号
            jobId = "pro_" + System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } else {
            try {
                jobId = requireAvaflowJobId(requestedJobId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }
        }

        File jobDir = new File(proJobsRootDir(), jobId);
        try {
            Files.createDirectories(jobDir.toPath());
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body("\u65e0\u6cd5\u521b\u5efa\u4efb\u52a1\u76ee\u5f55: " + e.getMessage());
        }

        // 输入来源：优先用上传到 <jobDir>/inputs 的输入文件（tif/tiff/asc/txt），
        // 否则用请求指定 / 配置默认的任务数据目录（zB/zL/hW.txt 等）
        final File inputDir;
        File uploadedInputs = new File(jobDir, "inputs");
        if (hasUploadedProInputs(uploadedInputs)) {
            inputDir = uploadedInputs;
        } else {
            try {
                inputDir = resolveProInputDir(str(body, "inputDir"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }
        }
        String requestedCrs = str(body, "sourceCrs");
        final String inputSourceCrs = requestedCrs.isEmpty() ? proSourceCrs : requestedCrs;
        String requestedName = str(body, "name");
        final String simulationName = requestedName.length() > 60
                ? requestedName.substring(0, 60) : requestedName;

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (body.get("params") instanceof Map)
                ? (Map<String, Object>) body.get("params")
                : Collections.<String, Object>emptyMap();

        final double bed = numOf(params, "bed", 0.2);
        final double nn = numOf(params, "nn", 0.0125);
        final double dx = Math.max(0.0, numOf(params, "dx", 0.0));
        final double dy = Math.max(0.0, numOf(params, "dy", 0.0));
        final double rous = numOf(params, "rous", 2700.0);
        final double rouf = numOf(params, "rouf", 1000.0);
        final double interval = Math.max(1e-6, numOf(params, "interval", 10.0));
        final double tmax = Math.max(1e-6, numOf(params, "tmax", 100.0));
        // 物源层厚度比例（0-1）：1.0 = 不削薄；<1 时把 zB-zL 按该比例削薄后再算
        final double depthScale = Math.min(1.0, Math.max(0.0, numOf(params, "depthScale", 1.0)));
        // 物源层厚度上限（米）：<=0 表示不限
        final double depthCap = Math.max(0.0, numOf(params, "depthCap", 0.0));

        // 长时段模拟（如 Tmax=1000s）墙钟可达 1 小时以上，固定超时会在中途杀掉进程
        // （前端表现为「pro 模型执行失败, 退出码: 1」）。实测节拍随水流
        // 扩展而逐渐变慢（帧间隔从约 40s 增至约 350s），故按 Tmax 线性放大 12 倍并留
        // 300s 余量；最少沿用配置值，最多 6 小时。
        final long jobTimeoutSeconds = Math.max(proTimeoutSeconds,
                Math.min(21600L, (long) Math.ceil(tmax * 12.0) + 300L));
        final int maxFrames = (int) Math.max(1, Math.min(300, numOf(params, "maxFrames", proDefaultMaxFrames)));
        String field = str(params, "field");
        if (!Arrays.asList("total", "water", "solid", "speed").contains(field)) {
            field = "total";
        }
        final String fieldArg = field;
        final String targetCrs = str(params, "targetCrs");
        final Double anchorLon = optNum(params, "anchorLon", body);
        final Double anchorLat = optNum(params, "anchorLat", body);

        // Terrain regulation: painted polygons + raise value submitted by the frontend.
        Object terrainEditsRaw = body.get("terrainEdits");
        if (terrainEditsRaw == null) {
            terrainEditsRaw = params.get("terrainEdits");
        }
        final boolean hasTerrainEdits = (terrainEditsRaw instanceof Collection)
                && !((Collection<?>) terrainEditsRaw).isEmpty();
        final File terrainEditsFile = new File(jobDir, "terrain_edits.json");
        if (hasTerrainEdits) {
            try {
                OBJECT_MAPPER.writeValue(terrainEditsFile, terrainEditsRaw);
            } catch (IOException e) {
                return ResponseEntity.badRequest().body("\u5730\u5f62\u8c03\u63a7\u53c2\u6570\u5199\u5165\u5931\u8d25: " + e.getMessage());
            }
        }

        File outDir = new File(new File(staticDir, proStaticSubdir), jobId);
        final File framesDir = new File(outDir, "frames");
        if (!framesDir.isDirectory() && !framesDir.mkdirs()) {
            return ResponseEntity.internalServerError().body("\u65e0\u6cd5\u521b\u5efa\u8f93\u51fa\u76ee\u5f55");
        }
        final String prefix = jobId.startsWith("pro_") ? jobId : "pro_" + jobId;
        final String outBase = "/ng/" + proStaticSubdir + "/" + jobId;

        Map<String, Object> job = new java.util.concurrent.ConcurrentHashMap<>();
        job.put("jobId", jobId);
        job.put("name", simulationName);
        job.put("prefix", prefix);
        job.put("status", "running");
        job.put("phase", "simulation");
        job.put("progress", 0);
        job.put("frames", 0);
        job.put("maxFrames", maxFrames);
        job.put("field", fieldArg);
        job.put("tmax", tmax);
        job.put("interval", interval);
        job.put("message", "\u6570\u503c\u8ba1\u7b97\u4e2d...");
        job.put("startedAt", System.currentTimeMillis());
        proJobs.put(jobId, job);

        new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>(Arrays.asList(
                        pythonExe, proScriptPath(),
                        "--job-dir", jobDir.getAbsolutePath(),
                        "--input-dir", inputDir.getAbsolutePath(),
                        "--source-crs", inputSourceCrs,
                        "--static-dir", staticDir,
                        "--out-subdir", proStaticSubdir,
                        "--frames-dir", framesDir.getAbsolutePath(),
                        "--out-base", outBase,
                        "--prefix", prefix,
                        "--bed", String.valueOf(bed),
                        "--nn", String.valueOf(nn),
                        "--dx", String.valueOf(dx),
                        "--dy", String.valueOf(dy),
                        "--rous", String.valueOf(rous),
                        "--rouf", String.valueOf(rouf),
                        "--interval", String.valueOf(interval),
                        "--tmax", String.valueOf(tmax),
                        "--depth-scale", String.valueOf(depthScale),
                        "--depth-cap", String.valueOf(depthCap),
                        "--max-frames", String.valueOf(maxFrames),
                        "--field", fieldArg));
                if (!simulationName.trim().isEmpty()) {
                    cmd.add("--name");
                    cmd.add(simulationName.trim());
                }
                if (!targetCrs.isEmpty()) {
                    cmd.add("--target-crs");
                    cmd.add(targetCrs);
                }
                if (anchorLon != null && anchorLat != null) {
                    cmd.add("--anchor-lon");
                    cmd.add(String.valueOf(anchorLon));
                    cmd.add("--anchor-lat");
                    cmd.add(String.valueOf(anchorLat));
                }
                if (hasTerrainEdits) {
                    cmd.add("--terrain-edits");
                    cmd.add(terrainEditsFile.getAbsolutePath());
                }

                ProcessResult pr = runProcess(cmd, new File(projectRoot), jobTimeoutSeconds, "[pro] ", StandardCharsets.UTF_8);
                String resultJson = extractSentinel(pr.output, "PRO_RESULT_JSON=");
                Map<String, Object> result = resultJson.isEmpty() ? null
                        : OBJECT_MAPPER.readValue(resultJson, new TypeReference<Map<String, Object>>() {
                        });
                if (pr.exitCode != 0 || result == null || !"ok".equals(String.valueOf(result.get("status")))) {
                    job.put("status", "error");
                    job.put("phase", "error");
                    String msg = (result != null && result.get("message") != null)
                            ? String.valueOf(result.get("message"))
                            : ("pro \u6a21\u578b\u6267\u884c\u5931\u8d25, \u9000\u51fa\u7801: " + pr.exitCode);
                    if (pr.output != null && pr.output.contains("[timeout]")) {
                        // 区分「超时被杀」与「算法自身报错」，给出可操作提示
                        msg = "\u8ba1\u7b97\u8d85\u65f6\uff1a\u6a21\u62df " + (long) tmax
                                + "s \u8d85\u8fc7\u540e\u7aef\u7b49\u5f85\u4e0a\u9650\uff08" + jobTimeoutSeconds
                                + "s\uff09\u5df2\u88ab\u7ec8\u6b62\uff0c\u8bf7\u8c03\u5c0f\u300c\u8ba1\u7b97\u65f6\u95f4\u300d"
                                + "\u6216\u589e\u5927 app.pro.timeout";
                    }
                    job.put("message", msg);
                    job.put("log", tail(pr.output, 2000));
                    return;
                }

                job.put("phase", "converting");
                job.put("progress", 97);
                job.put("message", "\u7ed3\u679c\u6574\u7406\u4e2d...");

                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (result.get("meta") instanceof Map)
                        ? (Map<String, Object>) result.get("meta")
                        : Collections.<String, Object>emptyMap();
                String sourceCrs = (meta.get("sourceCrs") == null)
                        ? ""
                        : String.valueOf(meta.get("sourceCrs")).trim();
                if (sourceCrs.isEmpty()) {
                    job.put("status", "error");
                    job.put("phase", "error");
                    job.put("message", "\u8f93\u51fa\u6805\u683c\u7f3a\u5c11\u5750\u6807\u7cfb\uff1a\u65e0\u5934\u90e8 txt \u5fc5\u987b\u5728\u754c\u9762\u586b\u5199\u7f51\u683c\u4e2d\u5fc3\u7ecf\u7eac\u5ea6\u4e0e\u6570\u636e\u5750\u6807\u7cfb\uff08\u5982 EPSG:32646\uff09\u540e\u518d\u8fd0\u884c");
                    return;
                }

                Map<String, Object> conv = prepareProFrames(framesDir, prefix, jobId, sourceCrs);
                int frameCount = ((Number) conv.get("frameCount")).intValue();
                if (frameCount <= 0) {
                    job.put("status", "error");
                    job.put("phase", "error");
                    job.put("message", "\u672a\u627e\u5230\u8f93\u51fa\u5e27(" + prefix + "_hflowNNNN.asc)");
                    return;
                }
                job.put("status", "done");
                job.put("phase", "done");
                job.put("progress", 100);
                job.put("outputBase", conv.get("outputBase"));
                job.put("ascBase", conv.get("ascBase"));
                job.put("frameFiles", conv.get("frameFiles"));
                job.put("frameCount", frameCount);
                job.put("bbox", conv.get("bbox"));
                job.put("meta", conv.get("meta"));
                job.put("message", "\u5b8c\u6210, \u8f93\u51fa " + frameCount + " \u5e27");
            } catch (Exception e) {
                job.put("status", "error");
                job.put("phase", "error");
                job.put("message", "Error: " + e.getMessage());
            }
        }, "pro-" + jobId).start();

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "accepted");
        resp.put("jobId", jobId);
        resp.put("message", "\u5df2\u542f\u52a8, \u8bf7\u8f6e\u8be2\u72b6\u6001\u83b7\u53d6\u7ed3\u679c");
        return ResponseEntity.ok(resp);
    }

    /** Pro 模型运行状态（含 python 侧 progress.json 中的实时进度）。 */
    @GetMapping("/pro_start_status")
    public ResponseEntity<?> proStartStatus(@RequestParam("jobId") String jobId) {
        Map<String, Object> job = proJobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body("\u672a\u77e5\u4efb\u52a1: " + jobId);
        }
        Object startedAt = job.get("startedAt");
        if (startedAt instanceof Number) {
            job.put("elapsedSeconds", (System.currentTimeMillis() - ((Number) startedAt).longValue()) / 1000);
        }
        if ("running".equals(job.get("status"))) {
            File progressFile = new File(new File(proJobsRootDir(), jobId), "progress.json");
            if (progressFile.isFile()) {
                try {
                    Map<String, Object> progress = OBJECT_MAPPER.readValue(progressFile,
                            new TypeReference<Map<String, Object>>() {
                            });
                    if (progress.get("percent") != null) {
                        job.put("progress", progress.get("percent"));
                    }
                    if (progress.get("frame") != null) {
                        job.put("frames", progress.get("frame"));
                    }
                    String stage = progress.get("stage") == null ? "" : String.valueOf(progress.get("stage"));
                    if (!stage.isEmpty() && !"done".equals(stage) && !"error".equals(stage)) {
                        job.put("phase", stage);
                    }
                    if (progress.get("message") != null) {
                        job.put("progressMessage", progress.get("message"));
                    }
                } catch (Exception ignored) {
                    // progress.json 可能正在写入，忽略本次读取
                }
            }
        }
        return ResponseEntity.ok(job);
    }

    /**
     * 历史模拟记录：扫描静态目录 <staticDir>/<proStaticSubdir>/ 下的历史任务目录，
     * 逐个读取 frames/ 与 frames_meta.json，返回可直接回放的结果列表。
     */
    @GetMapping("/pro_history")
    public ResponseEntity<?> proHistory(@RequestParam(value = "limit", required = false) Integer limit) {
        int max = (limit == null || limit <= 0) ? 30 : Math.min(200, limit);
        File root = new File(staticDir, proStaticSubdir);
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
            for (File dir : dirs) {
                File framesDir = new File(dir, "frames");
                if (!framesDir.isDirectory()) {
                    continue;
                }
                String jobId = dir.getName();
                if (!jobId.matches("[A-Za-z0-9_-]{1,96}")) {
                    continue;
                }
                Map<String, Object> pythonMeta = new LinkedHashMap<>();
                File metaFile = new File(framesDir, "frames_meta.json");
                if (metaFile.isFile()) {
                    try {
                        pythonMeta = OBJECT_MAPPER.readValue(metaFile,
                                new TypeReference<Map<String, Object>>() {
                                });
                    } catch (Exception ignored) {
                        // 元数据损坏时仍尝试按帧文件名回放
                    }
                }
                String sourceCrs = (pythonMeta.get("sourceCrs") == null)
                        ? ""
                        : String.valueOf(pythonMeta.get("sourceCrs")).trim();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("jobId", jobId);
                long createdEpoch = 0L;
                String[] idParts = jobId.split("_");
                if (idParts.length >= 2) {
                    try {
                        createdEpoch = Long.parseLong(idParts[1]);
                    } catch (NumberFormatException ignored) {
                        createdEpoch = 0L;
                    }
                }
                if (createdEpoch <= 0L) {
                    createdEpoch = dir.lastModified();
                }
                item.put("createdAtEpoch", createdEpoch);
                item.put("createdAtText", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date(createdEpoch)));
                item.put("field", pythonMeta.get("field"));
                item.put("name", pythonMeta.get("name"));
                item.put("tmax", pythonMeta.get("tmax"));
                item.put("interval", pythonMeta.get("interval"));
                item.put("globalMax", pythonMeta.get("globalMax"));
                Double depthScale = null;
                Object thinning = pythonMeta.get("depthThinning");
                if (thinning instanceof Map) {
                    Object scale = ((Map<?, ?>) thinning).get("scale");
                    if (scale instanceof Number) {
                        depthScale = ((Number) scale).doubleValue();
                    }
                }
                item.put("depthScale", depthScale);
                Object edits = pythonMeta.get("terrainEdits");
                item.put("terrainEdited", (edits instanceof Collection) && !((Collection<?>) edits).isEmpty());

                try {
                    Map<String, Object> conv = prepareProFrames(framesDir, jobId, jobId, sourceCrs);
                    int frameCount = ((Number) conv.get("frameCount")).intValue();
                    if (frameCount <= 0) {
                        continue;
                    }
                    item.put("frameCount", frameCount);
                    item.put("bbox", conv.get("bbox"));
                    item.put("meta", conv.get("meta"));
                    item.put("result", conv);
                } catch (Exception e) {
                    System.err.println("Pro 历史记录读取失败 " + jobId + ": " + e.getMessage());
                    continue;
                }
                total++;
                if (items.size() < max) {
                    items.add(item);
                }
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("total", total);
        resp.put("items", items);
        return ResponseEntity.ok(resp);
    }
    /** 汇总 Pro 输出帧的网格元数据（供前端 DebrisFlow 渲染与相机定位）。 */
    private Map<String, Object> prepareProFrames(File framesDir, String prefix, String jobId, String sourceCrs)
            throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outputBase", "/ng/" + proStaticSubdir + "/" + jobId);
        out.put("ascBase", "/ng/" + proStaticSubdir + "/" + jobId + "/frames");

        File[] files = framesDir.listFiles((d, name) ->
                name.matches(Pattern.quote(prefix) + "_hflow\\d{4}\\.asc"));
        List<String> frameFiles = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                frameFiles.add(f.getName());
            }
        }
        out.put("frameFiles", frameFiles);
        out.put("frameCount", frameFiles.size());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceCrs", sourceCrs);
        meta.put("frameCount", frameFiles.size());
        meta.put("prefix", prefix);

        File metaFile = new File(framesDir, "frames_meta.json");
        if (metaFile.isFile()) {
            try {
                Map<String, Object> pythonMeta = OBJECT_MAPPER.readValue(metaFile,
                        new TypeReference<Map<String, Object>>() {
                        });
                // terrainEdits / depthThinning 一并透传给前端：前者用于确认「拦挡范围是否真的生效」，
                // 后者用于说明本次物源削薄比例。
                for (String key : Arrays.asList("field", "globalMax", "globalMin", "dx", "dy",
                        "interval", "tmax", "ncols", "nrows", "cellsize",
                        "terrainEdits", "depthThinning")) {
                    if (pythonMeta.get(key) != null) {
                        meta.put(key, pythonMeta.get(key));
                    }
                }
            } catch (Exception e) {
                System.err.println("frames_meta.json \u89e3\u6790\u5931\u8d25: " + e.getMessage());
            }
        }

        if (!frameFiles.isEmpty()) {
            File first = new File(framesDir, frameFiles.get(0));
            AscGridMetadataReader.GridInfo info = AscGridMetadataReader.read(first);
            meta.putIfAbsent("ncols", info.ncols);
            meta.putIfAbsent("nrows", info.nrows);
            meta.putIfAbsent("cellsize", info.cellSize);
            if (info.hasValue) {
                meta.putIfAbsent("globalMax", info.maxValue);
                meta.putIfAbsent("globalMin", info.minValue);
            }
            try {
                double[] center = AscGridMetadataReader.transformCenter(info, sourceCrs);
                double[] bbox = AscGridMetadataReader.transformBbox(info, sourceCrs);
                meta.put("centerLon", center[0]);
                meta.put("centerLat", center[1]);
                meta.put("bbox", Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
            } catch (Exception e) {
                meta.put("centerLon", null);
                meta.put("centerLat", null);
                meta.put("bbox", null);
                System.err.println("Pro \u5750\u6807\u8f6c\u6362\u5931\u8d25: " + e.getMessage());
            }
        }

        out.put("meta", meta);
        out.put("bbox", meta.get("bbox"));
        return out;
    }


    private String requireAvaflowJobId(String value) {
        String jobId = value == null ? "" : value.trim();
        if (!jobId.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("\u65e0\u6548\u7684\u4efb\u52a1ID");
        }
        return jobId;
    }
    // ==================== SDP 启动物源结果：历史列表与动力学模型串联 ====================

    /** 从 SDP 结果目录挑选代表性 ZMAX 栅格（优先最终状态）。 */
    private static String pickSdpZmaxFile(File dir) {
        File finalTif = new File(dir, "ZMAX_final.tif");
        if (finalTif.isFile() && finalTif.length() > 0) {
            return finalTif.getAbsolutePath();
        }
        File[] tifs = dir.listFiles((d, n) -> n.matches("ZMAX_t\\d+\\.tif"));
        if (tifs == null || tifs.length == 0) {
            return null;
        }
        Arrays.sort(tifs, Comparator.comparingLong(File::lastModified));
        return tifs[tifs.length - 1].getAbsolutePath();
    }

    private static String newSdpRunId() {
        return "sdp_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String newAlphaJobIdForSdp() {
        return "alpha_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String requireRunId(String value, String label) {
        String runId = value == null ? "" : value.trim();
        if (!runId.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("无效的" + label);
        }
        return runId;
    }

    private static long epochOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 冰川泥石流动力学模型任务目录，与 AvaflowAlphaController 保持一致。 */
    private File alphaJobDir(String jobId) {
        File root = alphaJobsRoot == null || alphaJobsRoot.trim().isEmpty()
                ? new File(projectRoot, "suanfa/avaflow_alpha/jobs")
                : new File(alphaJobsRoot.trim());
        return new File(root, jobId);
    }

    /** 把 base64 预览图落盘为 PNG（兼容 data URL 前缀）。 */
    private static void writeBase64Png(String base64, File out) throws IOException {
        String data = base64 == null ? "" : base64;
        int comma = data.indexOf(',');
        if (comma >= 0) {
            data = data.substring(comma + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(data.trim());
        Files.write(out.toPath(), bytes);
    }

    /** SDP 运行完成后写入 result_meta.json，作为历史列表与动力学模型串联的索引。 */
    private void writeSdpResultMeta(String runId, File outputDir, String sdpName,
                                    Map<String, Object> frame, Map<String, Object> rawFrame,
                                    String zmaxPath, Map<String, Object> body) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("runId", runId);
            meta.put("name", sdpName);
            meta.put("createdAt", System.currentTimeMillis());
            meta.put("createdAtText", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            meta.put("zmaxFile", new File(zmaxPath).getName());
            if (body.containsKey("ice_content")) {
                meta.put("iceContent", body.get("ice_content"));
            }
            if (body.containsKey("zmax_boost")) {
                meta.put("zmaxBoost", body.get("zmax_boost"));
            }
            meta.put("stats", rawFrame.get("stats"));
            meta.put("valueRange", frame.get("valueRange"));
            meta.put("width", frame.get("width"));
            meta.put("height", frame.get("height"));
            meta.put("minLng", frame.get("minLng"));
            meta.put("minLat", frame.get("minLat"));
            meta.put("maxLng", frame.get("maxLng"));
            meta.put("maxLat", frame.get("maxLat"));
            meta.put("targetCrs", alphaSourceCrs);
            Object preview = frame.get("imageBase64");
            if (preview != null) {
                writeBase64Png(preview.toString(), new File(outputDir, "preview.png"));
                meta.put("preview", "preview.png");
            }
            // 记录本次 SDP 内部使用的 DEM，仅供追溯；动力学模型 z.asc 由用户上传
            File dem = new File(projectRoot, "suanfa/SDP_Start/dem.tif");
            if (dem.isFile()) {
                Files.copy(dem.toPath(), new File(outputDir, "dem_used.tif").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                meta.put("demFile", "dem_used.tif");
            }
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(outputDir, "result_meta.json"), meta);
        } catch (Exception e) {
            System.err.println("SDP 结果元数据写入失败 " + runId + ": " + e.getMessage());
        }
    }

    /** SDP 历史结果列表。 */
    @GetMapping("/sdp_history")
    public ResponseEntity<?> sdpHistory(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        File[] dirs = sdpResultsRootDir().listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                File metaFile = new File(dir, "result_meta.json");
                if (!metaFile.isFile()) {
                    continue;
                }
                try {
                    Map<String, Object> meta = OBJECT_MAPPER.readValue(metaFile,
                            new TypeReference<Map<String, Object>>() {});
                    String runId = meta.get("runId") == null ? dir.getName() : meta.get("runId").toString();
                    meta.put("runId", runId);
                    meta.put("previewUrl", "/testapi/admin/user/sdp_preview?runId=" + runId);
                    if (meta.get("createdAt") == null) {
                        meta.put("createdAt", dir.lastModified());
                    }
                    items.add(meta);
                } catch (Exception e) {
                    System.err.println("SDP 历史记录读取失败 " + dir.getName() + ": " + e.getMessage());
                }
            }
        }
        items.sort((a, b) -> Long.compare(epochOf(b.get("createdAt")), epochOf(a.get("createdAt"))));
        int max = limit <= 0 ? 50 : Math.min(limit, 200);
        if (items.size() > max) {
            items = new ArrayList<>(items.subList(0, max));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("total", items.size());
        resp.put("items", items);
        return ResponseEntity.ok(resp);
    }

    /** SDP 结果预览图。 */
    @GetMapping("/sdp_preview")
    public ResponseEntity<?> sdpPreview(@RequestParam("runId") String runId) {
        String id;
        try {
            id = requireRunId(runId, "SDP 结果 ID");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        File png = new File(new File(sdpResultsRootDir(), id), "preview.png");
        if (!png.isFile()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(png.toPath());
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(bytes);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("预览图读取失败: " + e.getMessage());
        }
    }
    /** 读取 SDP 历史结果，重建可直接渲染的帧（含 base64 预览图）。 */
    @GetMapping("/sdp_result")
    public ResponseEntity<?> sdpResult(@RequestParam("runId") String runId) {
        String id;
        try {
            id = requireRunId(runId, "SDP 结果 ID");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        File dir = new File(sdpResultsRootDir(), id);
        File metaFile = new File(dir, "result_meta.json");
        File png = new File(dir, "preview.png");
        if (!metaFile.isFile() || !png.isFile()) {
            return ResponseEntity.notFound().build();
        }
        try {
            Map<String, Object> meta = OBJECT_MAPPER.readValue(metaFile,
                    new TypeReference<Map<String, Object>>() {});
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("runId", id);
            frame.put("name", meta.get("name"));
            frame.put("minLng", meta.get("minLng"));
            frame.put("minLat", meta.get("minLat"));
            frame.put("maxLng", meta.get("maxLng"));
            frame.put("maxLat", meta.get("maxLat"));
            frame.put("width", meta.get("width"));
            frame.put("height", meta.get("height"));
            frame.put("valueRange", meta.get("valueRange"));
            frame.put("stats", meta.get("stats"));
            frame.put("imageBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(png.toPath())));
            return ResponseEntity.ok(frame);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("SDP 结果读取失败: " + e.getMessage());
        }
    }


    /**
     * 用 SDP 启动物源结果准备冰川泥石流动力学模型输入：
     * 用户上传的 z.asc 决定计算网格，SDP 的 ZMAX 栅格重投影到动力学模型坐标系后生成 h.asc。
     */
    @PostMapping("/prepare_alpha_from_sdp")
    public ResponseEntity<?> prepareAlphaFromSdp(
            @RequestParam("zFile") MultipartFile zFile,
            @RequestParam("sdpRunId") String sdpRunId,
            @RequestParam(value = "name", required = false) String name) {
        if (zFile == null || zFile.isEmpty()) {
            return ResponseEntity.badRequest().body("请上传 z.asc（地形高程）");
        }
        String runId;
        try {
            runId = requireRunId(sdpRunId, "SDP 结果 ID");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        File sdpDir = new File(sdpResultsRootDir(), runId);
        File zmax = new File(sdpDir, "ZMAX_final.tif");
        if (!zmax.isFile()) {
            String picked = pickSdpZmaxFile(sdpDir);
            if (picked == null) {
                return ResponseEntity.badRequest().body("SDP 结果缺少 ZMAX 栅格: " + runId);
            }
            zmax = new File(picked);
        }

        String jobId = newAlphaJobIdForSdp();
        File inputDir = new File(alphaJobDir(jobId), "input");
        try {
            Files.createDirectories(inputDir.toPath());
            File zAsc = new File(inputDir, "z.asc");
            zFile.transferTo(zAsc);

            String script = projectRoot + "/suanfa/SDP_Start/python/sdp_to_alpha.py";
            if (!new File(script).isFile()) {
                return ResponseEntity.internalServerError().body("缺少转换脚本: " + script);
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add(script);
            cmd.add("--z-asc");
            cmd.add(zAsc.getAbsolutePath());
            cmd.add("--zmax-tif");
            cmd.add(zmax.getAbsolutePath());
            cmd.add("--out-h");
            cmd.add(new File(inputDir, "h.asc").getAbsolutePath());
            cmd.add("--target-crs");
            cmd.add(alphaSourceCrs);
            ProcessResult pr = runProcess(cmd, new File(projectRoot), processTimeoutSeconds,
                    "[sdp->alpha] ", StandardCharsets.UTF_8);
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError()
                        .body("SDP 结果转 h.asc 失败: " + tail(pr.output, 800));
            }
            String statsJson = extractSentinel(pr.output, "RESULT_JSON=");
            Map<String, Object> stats = new LinkedHashMap<>();
            if (!statsJson.isEmpty()) {
                stats = OBJECT_MAPPER.readValue(statsJson, new TypeReference<Map<String, Object>>() {});
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("jobId", jobId);
            resp.put("sdpRunId", runId);
            resp.put("name", name == null ? "" : name);
            resp.put("sourceCrs", alphaSourceCrs);
            resp.put("stats", stats);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }


    // ==================== 冰岩崩启动模型：手绘源区 + 稳定性判别 + 启动动画 ====================

    private static double numOr(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(v) ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String strOr(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    /**
     * 冰岩崩启动模型：手绘源区多边形 + 冰层厚度 + 物源比例。
     * 先用既有稳定性模型判别源区，minFos &lt; 1 时生成与冰川泥石流动力学模型同格式的 ASC 动画帧。
     */
    @PostMapping("/avainit_region")
    public ResponseEntity<?> runAvainitRegion(@RequestBody Map<String, Object> body) {
        Object rawPolygon = body.get("polygon");
        if (!(rawPolygon instanceof List) || ((List<?>) rawPolygon).size() < 3) {
            return ResponseEntity.badRequest().body("请先在地图上绘制源区（至少 3 个顶点）");
        }
        try {
            List<double[]> ring = new ArrayList<>();
            for (Object item : (List<?>) rawPolygon) {
                if (!(item instanceof List) || ((List<?>) item).size() < 2) {
                    continue;
                }
                List<?> p = (List<?>) item;
                double lon = numOr(p.get(0), Double.NaN);
                double lat = numOr(p.get(1), Double.NaN);
                if (Double.isFinite(lon) && Double.isFinite(lat)) {
                    ring.add(new double[]{lon, lat});
                }
            }
            if (ring.size() < 3) {
                return ResponseEntity.badRequest().body("源区多边形至少需要 3 个有效顶点");
            }

            CoordinateReferenceSystem wgs = CRS.decode("EPSG:4326", true);
            CoordinateReferenceSystem modelCrs = CRS.decode(alphaSourceCrs, true);
            MathTransform toModel = CRS.findMathTransform(wgs, modelCrs, true);
            MathTransform toWgs = CRS.findMathTransform(modelCrs, wgs, true);

            int n = ring.size();
            double[] xs = new double[n];
            double[] ys = new double[n];
            double[] src = new double[2];
            double[] dst = new double[2];
            for (int i = 0; i < n; i++) {
                src[0] = ring.get(i)[0];
                src[1] = ring.get(i)[1];
                toModel.transform(src, 0, dst, 0, 1);
                xs[i] = dst[0];
                ys[i] = dst[1];
            }
            // 鞋带公式：源区面积与形心（投影坐标下）
            double area2 = 0.0;
            double cx = 0.0;
            double cy = 0.0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double cross = xs[i] * ys[j] - xs[j] * ys[i];
                area2 += cross;
                cx += (xs[i] + xs[j]) * cross;
                cy += (ys[i] + ys[j]) * cross;
            }
            if (Math.abs(area2) < 1e-6) {
                return ResponseEntity.badRequest().body("源区多边形面积过小，请重新绘制");
            }
            double area = Math.abs(area2) / 2.0;
            cx /= (3.0 * area2);
            cy /= (3.0 * area2);
            double[] centerWgs = new double[]{cx, cy};
            toWgs.transform(centerWgs, 0, centerWgs, 0, 1);

            double iceThickness = Math.max(0.0, numOr(body.get("ice_thickness"), 4.0));
            double sourceRatio = numOr(body.get("source_ratio"), 100.0);
            sourceRatio = Math.max(0.0, Math.min(100.0, sourceRatio));
            double slideLength = numOr(body.get("slide_length"), 0.0);
            if (!(slideLength > 0)) {
                slideLength = Math.sqrt(area);
            }

            int mode = (int) numOr(body.get("mode"), 1);
            String scriptName;
            List<String> args = new ArrayList<>();
            if (mode == 2) {
                // 反倾：坡度角 + 反倾角 + 边坡高度 + 层面间隔
                scriptName = "bedding_inverse.py";
                args.add(strOr(body, "melt_duration", "240"));
                args.add(strOr(body, "slope_angle", "30"));
                args.add(strOr(body, "inverse_angle", "70"));
                args.add(String.valueOf(iceThickness));
                args.add(strOr(body, "slope_height", "10"));
                args.add(strOr(body, "bedding_space", "20"));
                args.add(strOr(body, "cohesion", "15"));
                args.add(strOr(body, "friction_angle", "20"));
                args.add(strOr(body, "rock_density", "20"));
                args.add(strOr(body, "permeability", "0.0001"));
            } else if (mode == 3) {
                // 楔形：法向量 + 滑面面积（缺省用源区面积）+ 边坡高度 + 裂隙
                scriptName = "bedding_wedget.py";
                double square = numOr(body.get("square"), 0.0);
                if (!(square > 0)) {
                    square = area;
                }
                args.add(strOr(body, "melt_duration", "240"));
                args.add(strOr(body, "slope_angle", "55"));
                args.add(strOr(body, "normal_vector", "1,1,1"));
                args.add(String.valueOf(iceThickness));
                args.add(String.valueOf(square));
                args.add(strOr(body, "slope_height", "10"));
                args.add(strOr(body, "fracture", "0.5"));
                args.add(strOr(body, "cohesion", "15"));
                args.add(strOr(body, "friction_angle", "20"));
                args.add(strOr(body, "rock_density", "20"));
                args.add(strOr(body, "permeability", "0.0001"));
            } else {
                // 顺层：坡度角 + 滑面角 + 滑面长度（缺省用 sqrt(面积)）+ 裂隙高度
                scriptName = "bedding.py";
                args.add(strOr(body, "melt_duration", "240"));
                args.add(strOr(body, "slope_angle", "60"));
                args.add(strOr(body, "slide_angle", "15"));
                args.add(String.valueOf(iceThickness));
                args.add(strOr(body, "fissure_height", "10"));
                args.add(String.valueOf(slideLength));
                args.add(strOr(body, "cohesion", "15"));
                args.add(strOr(body, "friction_angle", "20"));
                args.add(strOr(body, "rock_density", "20"));
                args.add(strOr(body, "permeability", "0.0001"));
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe);
            cmd.add(projectRoot + "/suanfa/avainit/" + scriptName);
            cmd.addAll(args);
            ProcessResult pr = runProcess(cmd, new File(projectRoot), processTimeoutSeconds,
                    "[avainit] ", StandardCharsets.UTF_8);
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError()
                        .body("冰岩崩启动模型计算失败: " + tail(pr.output, 800));
            }
            String json = extractSentinel(pr.output, "RESULT_JSON=");
            if (json.isEmpty()) {
                return ResponseEntity.internalServerError().body("冰岩崩启动模型未返回结果");
            }
            Map<String, Object> modelResult = OBJECT_MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            Object modelError = modelResult.get("error");
            if (modelError != null && !String.valueOf(modelError).trim().isEmpty()) {
                return ResponseEntity.badRequest().body(String.valueOf(modelError));
            }
            double minFos = 1.0;
            boolean firstFos = true;
            Object fosRaw = modelResult.get("fos");
            if (fosRaw instanceof List) {
                for (Object v : (List<?>) fosRaw) {
                    double f = numOr(v, Double.NaN);
                    if (!Double.isFinite(f) || f <= 0) {
                        continue;
                    }
                    minFos = firstFos ? f : Math.min(minFos, f);
                    firstFos = false;
                }
            }
            boolean stable = !(minFos < 1.0);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("mode", body.get("mode"));
            resp.put("minFos", minFos);
            resp.put("stable", stable);
            resp.put("slideLength", slideLength);
            resp.put("iceVolume", area * iceThickness);
            resp.put("sourceVolume", area * iceThickness * sourceRatio / 100.0);

            List<List<Double>> ringOut = new ArrayList<>();
            double minLon = Double.POSITIVE_INFINITY;
            double minLat = Double.POSITIVE_INFINITY;
            double maxLon = Double.NEGATIVE_INFINITY;
            double maxLat = Double.NEGATIVE_INFINITY;
            for (double[] p : ring) {
                ringOut.add(Arrays.asList(p[0], p[1]));
                minLon = Math.min(minLon, p[0]);
                minLat = Math.min(minLat, p[1]);
                maxLon = Math.max(maxLon, p[0]);
                maxLat = Math.max(maxLat, p[1]);
            }
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("area", area);
            region.put("centroidLon", centerWgs[0]);
            region.put("centroidLat", centerWgs[1]);
            region.put("vertexCount", n);
            region.put("polygon", ringOut);
            region.put("bbox", Arrays.asList(minLon, minLat, maxLon, maxLat));
            // 前端 Cesium 地形采样得到的真实坡向/坡度（可选，仅用于追溯）
            Object flowAzimuthRaw = body.get("flow_azimuth");
            if (flowAzimuthRaw != null && Double.isFinite(numOr(flowAzimuthRaw, Double.NaN))) {
                region.put("flowAzimuth", numOr(flowAzimuthRaw, 0));
            }
            Object demSlopeRaw = body.get("dem_slope");
            if (demSlopeRaw != null && Double.isFinite(numOr(demSlopeRaw, Double.NaN))) {
                region.put("demSlope", numOr(demSlopeRaw, 0));
            }
            resp.put("region", region);

            // 按手绘范围 + 冰层厚度生成源区厚度场（ASC），由前端据此合成启动动画
            String jobId = "avainit_" + System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            File jobDir = new File(new File(staticDir, "avainit"), jobId);
            File ascFile = new File(jobDir, "source.asc");
            Files.createDirectories(jobDir.toPath());

            List<List<Double>> projected = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                projected.add(Arrays.asList(xs[i], ys[i]));
            }
            File ringFile = new File(jobDir, "ring.json");
            OBJECT_MAPPER.writeValue(ringFile, projected);

            List<String> fieldCmd = new ArrayList<>(Arrays.asList(
                    pythonExe,
                    projectRoot + "/suanfa/avainit/source_field.py",
                    "--polygon-json", ringFile.getAbsolutePath(),
                    "--ice-thickness", String.valueOf(iceThickness),
                    "--cellsize", "30",
                    "--out-asc", ascFile.getAbsolutePath()));
            ProcessResult fieldRun = runProcess(fieldCmd, new File(projectRoot),
                    processTimeoutSeconds, "[avainit-field] ", StandardCharsets.UTF_8);
            if (fieldRun.exitCode != 0) {
                return ResponseEntity.internalServerError()
                        .body("源区厚度场生成失败: " + tail(fieldRun.output, 800));
            }
            String fieldJson = extractSentinel(fieldRun.output, "RESULT_JSON=");
            if (fieldJson.isEmpty()) {
                return ResponseEntity.internalServerError().body("源区厚度场未返回结果");
            }
            Map<String, Object> fieldStats = OBJECT_MAPPER.readValue(fieldJson,
                    new TypeReference<Map<String, Object>>() {});

            Map<String, Object> sourceField = new LinkedHashMap<>();
            sourceField.put("ascText",
                    new String(Files.readAllBytes(ascFile.toPath()), StandardCharsets.UTF_8));
            sourceField.put("url", "/ng/avainit/" + jobId + "/source.asc");
            sourceField.put("crs", alphaSourceCrs);
            sourceField.put("ncols", fieldStats.get("ncols"));
            sourceField.put("nrows", fieldStats.get("nrows"));
            sourceField.put("cellsize", fieldStats.get("cellsize"));
            sourceField.put("xllcorner", fieldStats.get("xllcorner"));
            sourceField.put("yllcorner", fieldStats.get("yllcorner"));
            sourceField.put("maxValue", fieldStats.get("maxValue"));
            sourceField.put("meanValue", fieldStats.get("meanValue"));
            sourceField.put("volume", fieldStats.get("volume"));
            sourceField.put("cells", fieldStats.get("cells"));
            sourceField.put("area", fieldStats.get("area"));
            try {
                AscGridMetadataReader.GridInfo fieldInfo = AscGridMetadataReader.read(ascFile);
                double[] fieldBbox = AscGridMetadataReader.transformBbox(fieldInfo, alphaSourceCrs);
                double[] fieldCenter = AscGridMetadataReader.transformCenter(fieldInfo, alphaSourceCrs);
                sourceField.put("bbox", Arrays.asList(fieldBbox[0], fieldBbox[1], fieldBbox[2], fieldBbox[3]));
                sourceField.put("centerLon", fieldCenter[0]);
                sourceField.put("centerLat", fieldCenter[1]);
            } catch (Exception ex) {
                sourceField.put("bbox", null);
                sourceField.put("centerLon", null);
                sourceField.put("centerLat", null);
            }

            // 崩落体会推进到源区之外，给出外扩后的渲染网格与 WGS84 包围盒
            double fieldCell = numOr(fieldStats.get("cellsize"), 30.0);
            int fieldNcols = (int) numOr(fieldStats.get("ncols"), 0);
            int fieldNrows = (int) numOr(fieldStats.get("nrows"), 0);
            double fieldXll = numOr(fieldStats.get("xllcorner"), 0.0);
            double fieldYll = numOr(fieldStats.get("yllcorner"), 0.0);
            int padCells = (int) Math.ceil(
                    numOr(fieldStats.get("padMeters"), 0.0) / Math.max(fieldCell, 1e-6));
            sourceField.put("padCells", padCells);
            sourceField.put("renderNcols", fieldNcols + 2 * padCells);
            sourceField.put("renderNrows", fieldNrows + 2 * padCells);
            try {
                double ex0 = fieldXll - padCells * fieldCell;
                double ey0 = fieldYll - padCells * fieldCell;
                double ex1 = fieldXll + (fieldNcols + padCells) * fieldCell;
                double ey1 = fieldYll + (fieldNrows + padCells) * fieldCell;
                double[][] corners = {{ex0, ey0}, {ex1, ey0}, {ex1, ey1}, {ex0, ey1}};
                double rMinLon = Double.POSITIVE_INFINITY;
                double rMinLat = Double.POSITIVE_INFINITY;
                double rMaxLon = Double.NEGATIVE_INFINITY;
                double rMaxLat = Double.NEGATIVE_INFINITY;
                for (double[] corner : corners) {
                    double[] p = new double[]{corner[0], corner[1]};
                    toWgs.transform(p, 0, p, 0, 1);
                    rMinLon = Math.min(rMinLon, p[0]);
                    rMinLat = Math.min(rMinLat, p[1]);
                    rMaxLon = Math.max(rMaxLon, p[0]);
                    rMaxLat = Math.max(rMaxLat, p[1]);
                }
                sourceField.put("renderBbox",
                        Arrays.asList(rMinLon, rMinLat, rMaxLon, rMaxLat));
            } catch (Exception ex) {
                sourceField.put("renderBbox", sourceField.get("bbox"));
            }
            resp.put("jobId", jobId);
            resp.put("frameCount", 10);
            resp.put("sourceField", sourceField);

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    /** 把启动动画帧装配成与冰川泥石流动力学模型一致的渲染契约。 */
    private Map<String, Object> buildAvainitFrameResult(String jobId, File framesDir) throws IOException {
        List<String> frameFiles = new ArrayList<>();
        AscGridMetadataReader.GridInfo first = null;
        double globalMax = Double.NEGATIVE_INFINITY;
        boolean hasValue = false;
        File[] files = framesDir.listFiles((d, name) -> name.matches("h_\\d{6}\\.asc"));
        if (files != null && files.length > 0) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                AscGridMetadataReader.GridInfo info = AscGridMetadataReader.read(file);
                if (first == null) {
                    first = info;
                }
                if (info.hasValue) {
                    globalMax = Math.max(globalMax, info.maxValue);
                    hasValue = true;
                }
                frameFiles.add(file.getName());
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceCrs", alphaSourceCrs);
        meta.put("field", "solid");
        meta.put("model", "avainit");
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
        meta.put("globalMin", 0.0);
        meta.put("globalMax", hasValue ? globalMax : 0.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("jobId", jobId);
        out.put("frameCount", frameFiles.size());
        out.put("ascBase", "/ng/avainit/" + jobId + "/frames");
        out.put("frameFiles", frameFiles);
        out.put("meta", meta);
        out.put("bbox", meta.get("bbox"));
        return out;
    }


    private List<Map<String, Object>> parseFrameList(String json) throws Exception {
        Object parsed = OBJECT_MAPPER.readValue(json, Object.class);
        List<Map<String, Object>> list = new ArrayList<>();
        if (parsed instanceof List) {
            for (Object o : (List<?>) parsed) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    list.add(m);
                }
            }
        } else if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            list.add(m);
        }
        return list;
    }

    /** 将 tif_to_json.py 的 RESULT_JSON 转为前端帧（WGS84 边界，兼容投影情况）。 */
    private Map<String, Object> buildFrame(Map<String, Object> rawResult) throws Exception {
        boolean isProjected = Boolean.TRUE.equals(rawResult.get("isProjected"));
        double west  = Double.parseDouble(rawResult.get("west").toString());
        double south = Double.parseDouble(rawResult.get("south").toString());
        double east  = Double.parseDouble(rawResult.get("east").toString());
        double north = Double.parseDouble(rawResult.get("north").toString());

        double minLng, minLat, maxLng, maxLat;
        if (isProjected && rawResult.get("crsWkt") != null && !rawResult.get("crsWkt").toString().isEmpty()) {
            String crsWkt = rawResult.get("crsWkt").toString();
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
        } else {
            minLng = west;
            minLat = south;
            maxLng = east;
            maxLat = north;
        }

        Map<String, Object> frame = new HashMap<>();
        frame.put("minLng", Math.round(minLng * 1000000.0) / 1000000.0);
        frame.put("minLat", Math.round(minLat * 1000000.0) / 1000000.0);
        frame.put("maxLng", Math.round(maxLng * 1000000.0) / 1000000.0);
        frame.put("maxLat", Math.round(maxLat * 1000000.0) / 1000000.0);
        frame.put("imageBase64", rawResult.get("imageBase64"));
        frame.put("width", rawResult.get("width"));
        frame.put("height", rawResult.get("height"));
        frame.put("valueRange", rawResult.get("valueRange"));
        return frame;
    }



    @PostMapping("/seismic_dl")
    public ResponseEntity<?> processSeismicDL(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> fileInfo = (Map<String, String>) body.get("file");
            if (fileInfo == null || fileInfo.get("savedPath") == null) {
                return ResponseEntity.badRequest().body("Missing 'file.savedPath'");
            }
            String csvPath = fileInfo.get("savedPath");
            if (!new java.io.File(csvPath).exists()) {
                return ResponseEntity.badRequest().body("CSV not found: " + csvPath);
            }

            ProcessResult pr = runProcess(
                    Arrays.asList(condaPythonExe, transformerScript, csvPath),
                    null, processTimeoutSeconds, "[DL] ", StandardCharsets.UTF_8);
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError().body("DL Python failed, exit: " + pr.exitCode);
            }
            String outputJson = extractSentinel(pr.output, "RESULT_JSON=");
            if (outputJson.isEmpty()) {
                return ResponseEntity.internalServerError().body("DL Python 未返回 RESULT_JSON");
            }
            return ResponseEntity.ok(
                    OBJECT_MAPPER.readValue(outputJson, new TypeReference<Map<String, Object>>() {})
            );
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }



    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * 执行外部进程：合并 stdout/stderr，带超时控制，避免模型进程挂起阻塞请求。
     */
    private ProcessResult runProcess(List<String> command, File workDir, long timeoutSeconds,
                                     String logPrefix, Charset charset)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        String prefix = logPrefix == null ? "" : logPrefix;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append(System.lineSeparator());
                    if (!prefix.isEmpty()) {
                        System.out.println(prefix + line);
                    }
                }
            } catch (IOException e) {
                System.err.println("读取进程输出失败: " + e.getMessage());
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            System.err.println("进程超时(" + timeoutSeconds + "s)，强制终止: " + command);
            process.destroyForcibly();
            process.waitFor();
        }
        reader.join(2000);
        String output = out.toString();
        if (!finished) {
            // 在输出里留标记，便于上层把「超时被杀」与「算法自身报错」区分开
            output = "[timeout] \u8fdb\u7a0b\u8fd0\u884c\u8d85\u8fc7 " + timeoutSeconds
                    + " \u79d2\uff0c\u5df2\u88ab\u5f3a\u5236\u7ec8\u6b62" + System.lineSeparator() + output;
        }
        return new ProcessResult(process.exitValue(), output);
    }

    /** 截取外部进程输出的尾部，便于把算法真实报错带回前端 */
    /** 外部进程输出截断，供既有算法错误回传复用。 */
    private static String tail(String text, int maxChars) {
        return tailOf(text, maxChars);
    }

    private static String tailOf(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return "..." + trimmed.substring(trimmed.length() - maxChars);
    }

    private static String extractSentinel(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

}

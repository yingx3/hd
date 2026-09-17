package com.xyz.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;


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

    @Value("${app.avaflow.wsl-home://wsl.localhost/Ubuntu-20.04/home/wm}")
    private String avaflowWslHome;

    @Value("${app.avaflow.wsl-linux-home:/home/wm}")
    private String avaflowWslLinuxHome;

    @Value("${app.avaflow.jobs-root://wsl.localhost/Ubuntu-20.04/home/wm/avaflow_jobs}")
    private String avaflowJobsRoot;

    @Value("${app.avaflow.source-crs:EPSG:32647}")
    private String avaflowSourceCrs;
    @Value("${app.avaflow.grass-gisdbase:/home/wm/grassdata/demo1/PERMANENT}")
    private String avaflowGrassGisdbase;
    @Value("${app.avaflow.timeout:1800}")
    private long avaflowTimeoutSeconds;

    @Value("${app.avaflow.phases:3}")
    private String avaflowPhases;

    @Value("${app.avaflow.friction:15,0,0,15,0,0,0,0,0.05}")
    private String avaflowFriction;

    @Value("${app.avaflow.time:10,200}")
    private String avaflowTime;

    @Value("${app.avaflow.profile:159256,3319753,158535,3318924,158097,3318218,157556,3317198,157084,3316176,156786,3315547,156579,3314835}")
    private String avaflowProfileDefault;

    @Value("${app.avaflow.expected-frames:21}")
    private int avaflowExpectedFrames;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment env;

    @Value("${app.avaflow.static-dir:E:/softwares/nginx-1.26.2/nginx-1.26.2/html}")
    private String avaflowStaticDir;

    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> avaflowJobs = new java.util.concurrent.ConcurrentHashMap<>();

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
    // [已移除] /yj 端点 — 依赖 WSL Ubuntu，部署环境中不可用
    // @PostMapping("/yj")
    // public String  yjmodelparam(@RequestBody FormData1 formData1)throws Exception{
    //     ... WSL 调用已移除 ...
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
        String outputDir = body.containsKey("output_dir") ? body.get("output_dir").toString() : projectRoot + "/data/SDP_Results";
        double iceContent = body.containsKey("ice_content") ? Double.parseDouble(body.get("ice_content").toString()) : 0.2;
        double zmaxBoost = body.containsKey("zmax_boost") ? Double.parseDouble(body.get("zmax_boost").toString()) : 0.0;
        String tempPattern = body.containsKey("temp_pattern") ? body.get("temp_pattern").toString() : "temp_%d.tif";

        try {
            ProcessResult pr = runProcess(
                    Arrays.asList(pythonExe, pythonScript,
                            "--rain_path", rainPath,
                            "--temp_path", tempPath,
                            "--output_dir", outputDir,
                            "--ice_content", String.valueOf(iceContent),
                            "--temp_pattern", tempPattern,
                            "--zmax_boost", String.valueOf(zmaxBoost)),
                    new File(projectRoot), processTimeoutSeconds, "SDP Python: ", Charset.forName("GBK"));
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError().body("run.py 执行失败，退出码：" + pr.exitCode);
            }

            // 代表性时间节点序列（对应 ZMAX_t<time>.tif）
            List<String> times = new ArrayList<>();
            String timeLine = extractSentinel(pr.output, "TIME_NODES=");
            if (!timeLine.isEmpty()) {
                for (String s : timeLine.split(",")) {
                    if (!s.trim().isEmpty()) {
                        times.add(s.trim());
                    }
                }
            }
            if (times.isEmpty()) {
                times.add("final"); // 兼容旧版：仅 ZMAX_final.tif
            }

            // 只取最后时间节点（最终状态，不做逐帧动画）
            String tk = times.get(times.size() - 1);
            String tifPath = "final".equals(tk)
                    ? outputDir + "/ZMAX_final.tif"
                    : outputDir + "/ZMAX_t" + tk + ".tif";
            if (!new File(tifPath).exists()) {
                return ResponseEntity.internalServerError().body("输出文件不存在: " + tifPath);
            }

            // 单次进程转换最终 tif
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

            // 返回最后一帧（单对象）
            Map<String, Object> frame = buildFrame(rawFrames.get(rawFrames.size() - 1));
            frame.put("time", "final".equals(tk) ? 0.0 : Double.parseDouble(tk));
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

    @PostMapping("/upload_avaflow")
    public ResponseEntity<?> uploadAvaflow(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "jobId", required = false) String requestedJobId) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body("\u7f3a\u5c11\u6587\u4ef6");
        }
        try {
            String jobId = requestedJobId == null || requestedJobId.trim().isEmpty()
                    ? newAvaflowJobId()
                    : requireAvaflowJobId(requestedJobId);
            File inputDir = new File(new File(avaflowJobsRoot, jobId), "inputs");
            Files.createDirectories(inputDir.toPath());

            for (MultipartFile f : files) {
                String name = f.getOriginalFilename();
                if (name == null || name.trim().isEmpty()) continue;
                File target = new File(inputDir, Paths.get(name).getFileName().toString());
                f.transferTo(target);
            }

            List<String> required = Arrays.asList("elev.tif", "debris.tif", "impact_area.tif");
            for (String name : required) {
                File target = new File(inputDir, name);
                if (!target.isFile() || target.length() == 0) {
                    return ResponseEntity.badRequest().body("\u7f3a\u5c11\u8f93\u5165\u6587\u4ef6: " + name);
                }
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("jobId", jobId);
            resp.put("message", "\u4e0a\u4f20\u6210\u529f");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("\u4e0a\u4f20\u5931\u8d25: " + e.getMessage());
        }
    }

    @PostMapping("/yj_beta")
    public ResponseEntity<?> runAvaflowBeta(@RequestBody Map<String, Object> body) {
        final String jobId;
        try {
            jobId = requireAvaflowJobId(str(body, "jobId"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        File jobDir = new File(avaflowJobsRoot, jobId);
        File inputDir = new File(jobDir, "inputs");
        for (String name : Arrays.asList("elev.tif", "debris.tif", "impact_area.tif")) {
            File required = new File(inputDir, name);
            if (!required.isFile() || required.length() == 0) {
                return ResponseEntity.badRequest().body("\u4efb\u52a1\u8f93\u5165\u4e0d\u5b8c\u6574: " + name);
            }
        }

        String area = str(body, "area");

        // 沿程调控：前端手绘范围 + 加高值，先抬高 elevation 栅格再交给 r.avaflow 计算
        Object terrainEditsRaw = body.get("terrainEdits");
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

        String prefix = "beta_" + jobId;
        Map<String, Object> job = new java.util.concurrent.ConcurrentHashMap<>();
        job.put("jobId", jobId);
        job.put("prefix", prefix);
        job.put("status", "running");
        job.put("phase", "simulation");
        job.put("progress", 0);
        job.put("frames", 0);
        job.put("expectedFrames", avaflowExpectedFrames);
        job.put("message", "r.avaflow ???...");
        job.put("startedAt", System.currentTimeMillis());
        avaflowJobs.put(jobId, job);

        new Thread(() -> {
            try {
                String jobDirLinux = toLinuxPath(jobDir.getAbsolutePath());
                String elevationPathLinux = jobDirLinux + "/inputs/elev.tif";
                File elevationFile = new File(inputDir, "elev.tif");
                if (hasTerrainEdits) {
                    job.put("message", "\u5730\u5f62\u8c03\u63a7\u4e2d...");
                    ProcessResult terrainPr = runProcess(
                            Arrays.asList(pythonExe, avaflowTerrainScript(),
                                    "--input", new File(inputDir, "elev.tif").getAbsolutePath(),
                                    "--edits", terrainEditsFile.getAbsolutePath(),
                                    "--output", new File(inputDir, "elev_regulated.tif").getAbsolutePath()),
                            new File(projectRoot), processTimeoutSeconds,
                            "[avaflow_terrain] ", StandardCharsets.UTF_8);
                    String terrainJson = extractSentinel(terrainPr.output, "AVAFLOW_TERRAIN_JSON=");
                    Map<String, Object> terrainResult = terrainJson.isEmpty() ? null
                            : OBJECT_MAPPER.readValue(terrainJson, new TypeReference<Map<String, Object>>() {
                            });
                    if (terrainPr.exitCode != 0 || terrainResult == null
                            || !"ok".equals(String.valueOf(terrainResult.get("status")))) {
                        job.put("status", "error");
                        job.put("phase", "error");
                        job.put("message", "\u5730\u5f62\u8c03\u63a7\u5931\u8d25: "
                                + (terrainResult != null ? terrainResult.get("message") : tail(terrainPr.output, 800)));
                        return;
                    }
                    job.put("terrainEdits", terrainResult.get("applied"));
                    elevationPathLinux = jobDirLinux + "/inputs/elev_regulated.tif";
                    elevationFile = new File(inputDir, "elev_regulated.tif");
                    job.put("message", "r.avaflow \u6a21\u62df\u4e2d...");
                }
                // profile（沿程剖面线）：application.yml 里的默认剖面按波密案例写死，
                // 换案例（如色东普）会落在计算区之外，r.avaflow 会报参数校验失败。
                // 这里用本次 DEM 校验一次：不在区内就按「释放区中心—最陡下降路径」自动生成。
                Map<String, Object> runCtx = prepareAvaflowRunContext(elevationFile,
                        new File(inputDir, "debris.tif"), area);
                String profileOverride = runCtx == null ? null : (String) runCtx.get("profile");
                Object detectedCrs = runCtx == null ? null : runCtx.get("sourceCrs");
                // 坐标系：优先用上传高程栅格自带的 CRS。不同投影带的案例套用全局 source-crs
                // 会让前端把结果换算到错误经纬度（如 46N 数据按 47N 换算会偏出 500+ km）。
                final String effectiveSourceCrs = (detectedCrs == null
                        || String.valueOf(detectedCrs).trim().isEmpty())
                                ? avaflowSourceCrs
                                : String.valueOf(detectedCrs).trim();
                if (!effectiveSourceCrs.equalsIgnoreCase(avaflowSourceCrs)) {
                    System.out.println("[avaflow_beta] 按上传高程栅格识别坐标系: " + effectiveSourceCrs
                            + "（配置值 " + avaflowSourceCrs + " 仅作兜底）");
                }
                String scriptPathLinux = jobDirLinux + "/start_beta.sh";
                String startScript = buildStartScript(prefix, area, jobDirLinux, jobId,
                        elevationPathLinux, profileOverride);
                File startFile = new File(jobDir, "start_beta.sh");
                Files.write(startFile.toPath(), startScript.getBytes(StandardCharsets.UTF_8));

                ProcessResult pr = runProcess(
                        Arrays.asList("wsl", "-d", "Ubuntu-20.04", "--", "bash", "-c",
                                "cd " + shellQuote(avaflowWslLinuxHome)
                                        + " && chmod +x " + shellQuote(scriptPathLinux)
                                        + " && grass " + shellQuote(avaflowGrassGisdbase)
                                        + " --exec bash " + shellQuote(scriptPathLinux)),
                        null, avaflowTimeoutSeconds, "[avaflow_beta] ", StandardCharsets.UTF_8);
                if (pr.exitCode != 0) {
                    job.put("status", "error");
                    job.put("phase", "error");
                    job.put("message", "avaflow \u6267\u884c\u5931\u8d25, \u9000\u51fa\u7801: " + pr.exitCode);
                    job.put("log", tail(pr.output, 1500));
                    return;
                }

                job.put("phase", "converting");
                job.put("progress", 85);
                job.put("message", "\u6a21\u578b\u8ba1\u7b97\u5b8c\u6210, \u6b63\u5728\u51c6\u5907 ASC \u5e27...");

                String asciiDir = new File(
                        new File(avaflowWslHome, prefix + "_results"),
                        prefix + "_ascii").getPath();
                Map<String, Object> conv = prepareAvaflowAscFrames(
                        asciiDir, prefix, avaflowStaticDir, jobId, effectiveSourceCrs);
                int frameCount = ((Number) conv.get("frameCount")).intValue();
                if (frameCount <= 0) {
                    job.put("status", "error");
                    job.put("phase", "error");
                    job.put("message", "\u672a\u627e\u5230\u8f93\u51fa\u5e27(hflowNNNN.asc)");
                    return;
                }

                // 沿程调控：统计「范围内是否真的过流、峰值流深」，并把带多边形的 terrainEdits
                // 注入 meta（前端据此提示调控是否生效并在地图上标注调控范围）
                if (hasTerrainEdits) {
                    List<Map<String, Object>> inspected = inspectAvaflowRegulation(
                            new File(new File(avaflowStaticDir, "avaflow_beta"),
                                    jobId + File.separator + "frames"),
                            prefix, effectiveSourceCrs, terrainEditsFile, job.get("terrainEdits"));
                    if (inspected != null) {
                        job.put("terrainEdits", inspected);
                        Object metaObj = conv.get("meta");
                        if (metaObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) metaObj;
                            m.put("terrainEdits", inspected);
                        }
                    }
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

                // 历史模拟：把本次运行的关键信息落盘，历史列表接口可直接读取（不必回读全部帧做统计）
                writeAvaflowBetaHistoryMeta(jobId, conv, job.get("terrainEdits"), area,
                        effectiveSourceCrs);
            } catch (Exception e) {
                job.put("status", "error");
                job.put("phase", "error");
                job.put("message", "Error: " + e.getMessage());
            }
        }, "avaflow-beta-" + jobId).start();

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "accepted");
        resp.put("jobId", jobId);
        resp.put("message", "\u5df2\u542f\u52a8, \u8bf7\u8f6e\u8be2\u72b6\u6001\u83b7\u53d6\u7ed3\u679c");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/yj_beta_status")
    public ResponseEntity<?> yjBetaStatus(@RequestParam("jobId") String jobId) {
        Map<String, Object> job = avaflowJobs.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body("\u672a\u77e5\u4efb\u52a1: " + jobId);
        }
        if ("running".equals(job.get("status"))) {
            if ("simulation".equals(job.get("phase"))) {
            String prefix = (String) job.get("prefix");
            if (prefix != null) {
                File asciiDir = new File(
                        new File(avaflowWslHome, prefix + "_results"),
                        prefix + "_ascii");
                File[] frames = asciiDir.listFiles((d, name) -> name.matches(prefix + "_hflow\\d{4}\\.asc"));
                int frameCount = frames == null ? 0 : frames.length;
                job.put("frames", frameCount);
                int progress = Math.min(85, (int) Math.round(frameCount * 85.0 / Math.max(1, avaflowExpectedFrames)));
                job.put("progress", progress);
            }
            }
            Object startedAt = job.get("startedAt");
            if (startedAt instanceof Number) {
                job.put("elapsedSeconds", (System.currentTimeMillis() - ((Number) startedAt).longValue()) / 1000);
            }
        }
        return ResponseEntity.ok(job);
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

        File outDir = new File(new File(avaflowStaticDir, proStaticSubdir), jobId);
        final File framesDir = new File(outDir, "frames");
        if (!framesDir.isDirectory() && !framesDir.mkdirs()) {
            return ResponseEntity.internalServerError().body("\u65e0\u6cd5\u521b\u5efa\u8f93\u51fa\u76ee\u5f55");
        }
        final String prefix = jobId.startsWith("pro_") ? jobId : "pro_" + jobId;
        final String outBase = "/ng/" + proStaticSubdir + "/" + jobId;

        Map<String, Object> job = new java.util.concurrent.ConcurrentHashMap<>();
        job.put("jobId", jobId);
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
                        "--static-dir", avaflowStaticDir,
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
        File root = new File(avaflowStaticDir, proStaticSubdir);
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

    /**
     * 冰川泥石流动力学模型（r.avaflow beta）历史模拟记录：扫描
     * &lt;staticDir&gt;/avaflow_beta/ 下的历史任务目录，返回可直接回放的结果列表。
     */
    @GetMapping("/avaflow_beta_history")
    public ResponseEntity<?> avaflowBetaHistory(@RequestParam(value = "limit", required = false) Integer limit) {
        int max = (limit == null || limit <= 0) ? 30 : Math.min(200, limit);
        File root = new File(avaflowStaticDir, "avaflow_beta");
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
            for (File dir : dirs) {
                String jobId = dir.getName();
                if (!jobId.matches("[A-Za-z0-9_-]{1,96}")) {
                    continue;
                }
                File framesDir = new File(dir, "frames");
                if (!framesDir.isDirectory()) {
                    continue;
                }
                try {
                    Map<String, Object> item = buildAvaflowBetaHistoryItem(dir, framesDir, jobId);
                    if (item == null) {
                        continue;
                    }
                    total++;
                    if (items.size() < max) {
                        items.add(item);
                    }
                } catch (Exception e) {
                    System.err.println("avaflow_beta 历史记录读取失败 " + jobId + ": " + e.getMessage());
                }
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("total", total);
        resp.put("items", items);
        return ResponseEntity.ok(resp);
    }

    /** 运行结束时记录历史元数据（history_meta.json），供历史列表快速展示。 */
    private void writeAvaflowBetaHistoryMeta(String jobId, Map<String, Object> conv,
            Object terrainEdits, String area, String sourceCrs) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("jobId", jobId);
            meta.put("model", "avaflow_beta");
            meta.put("createdAtEpoch", System.currentTimeMillis());
            meta.put("sourceCrs", (sourceCrs == null || sourceCrs.trim().isEmpty())
                    ? avaflowSourceCrs : sourceCrs.trim());
            meta.put("frameCount", conv.get("frameCount"));
            meta.put("area", area);
            meta.put("terrainEdits", terrainEdits);
            Object convMeta = conv.get("meta");
            if (convMeta instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) convMeta;
                for (String key : Arrays.asList("ncols", "nrows", "cellsize", "globalMax",
                        "globalMin", "centerLon", "centerLat", "bbox")) {
                    meta.put(key, m.get(key));
                }
            }
            File outDir = new File(new File(avaflowStaticDir, "avaflow_beta"), jobId);
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                return;
            }
            OBJECT_MAPPER.writeValue(new File(outDir, "history_meta.json"), meta);
        } catch (Exception e) {
            System.err.println("avaflow_beta 历史元数据写入失败: " + e.getMessage());
        }
    }

    /**
     * 汇总一条 avaflow_beta 历史记录：帧清单、网格元数据与可回放的 result。
     * 首次遇到没有 history_meta.json 的旧任务时扫描全部帧统计极值，并回写缓存。
     */
    private Map<String, Object> buildAvaflowBetaHistoryItem(File jobDir, File framesDir, String jobId)
            throws Exception {
        String prefix = "beta_" + jobId;
        File[] files = framesDir.listFiles((d, name) ->
                name.matches(Pattern.quote(prefix) + "_hflow\\d{4}\\.asc"));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        int frameCount = files.length;

        Map<String, Object> hist = new LinkedHashMap<>();
        File histFile = new File(jobDir, "history_meta.json");
        if (histFile.isFile()) {
            try {
                hist = OBJECT_MAPPER.readValue(histFile, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                // 缓存损坏时按原始帧重新统计
            }
        }
        String sourceCrs = hist.get("sourceCrs") == null
                ? avaflowSourceCrs
                : String.valueOf(hist.get("sourceCrs")).trim();
        if (sourceCrs.isEmpty()) {
            sourceCrs = avaflowSourceCrs;
        }

        boolean cached = (hist.get("frameCount") instanceof Number)
                && ((Number) hist.get("frameCount")).intValue() == frameCount
                && (hist.get("globalMax") instanceof Number);
        // 缓存里已经有网格几何/中心/bbox 时就不再读帧文件，列表查询更快
        boolean geomCached = cached
                && hist.get("ncols") instanceof Number
                && hist.get("nrows") instanceof Number
                && hist.get("cellsize") instanceof Number
                && hist.get("centerLon") instanceof Number
                && hist.get("centerLat") instanceof Number
                && hist.get("bbox") instanceof List;
        AscGridMetadataReader.GridInfo first = geomCached ? null : AscGridMetadataReader.read(files[0]);
        int gridCols = geomCached ? ((Number) hist.get("ncols")).intValue() : first.ncols;
        int gridRows = geomCached ? ((Number) hist.get("nrows")).intValue() : first.nrows;
        double gridCell = geomCached ? ((Number) hist.get("cellsize")).doubleValue() : first.cellSize;
        double globalMin;
        double globalMax;
        if (cached) {
            globalMin = (hist.get("globalMin") instanceof Number)
                    ? ((Number) hist.get("globalMin")).doubleValue() : 0.0;
            globalMax = ((Number) hist.get("globalMax")).doubleValue();
        } else {
            // 旧任务没有缓存：并行扫描各帧统计极值（16 条各 41 帧的旧记录一次扫描约 1~3 秒），
            // 统计完成后回写 history_meta.json，后续查询直接命中缓存。
            int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            List<java.util.concurrent.Future<double[]>> futures = new ArrayList<>();
            for (File f : files) {
                futures.add(pool.submit(() -> {
                    try {
                        AscGridMetadataReader.GridInfo info = AscGridMetadataReader.read(f);
                        return info.hasValue ? new double[] { info.minValue, info.maxValue } : null;
                    } catch (Exception e) {
                        System.err.println("avaflow_beta 帧统计失败 " + f.getName() + ": " + e.getMessage());
                        return null;
                    }
                }));
            }
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            boolean hasValue = false;
            for (java.util.concurrent.Future<double[]> future : futures) {
                try {
                    double[] stat = future.get();
                    if (stat != null) {
                        min = Math.min(min, stat[0]);
                        max = Math.max(max, stat[1]);
                        hasValue = true;
                    }
                } catch (Exception e) {
                    // 单帧统计失败不影响其余帧
                }
            }
            pool.shutdown();
            globalMin = hasValue ? min : 0.0;
            globalMax = hasValue ? max : 0.0;
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceCrs", sourceCrs);
        meta.put("field", "solid"); // r.avaflow 输出的 hflow 就是泥石流层厚度
        meta.put("ncols", gridCols);
        meta.put("nrows", gridRows);
        meta.put("cellsize", gridCell);
        meta.put("globalMax", globalMax);
        meta.put("globalMin", globalMin);
        meta.put("frameCount", frameCount);
        meta.put("history", true);
        List<Double> bbox = null;
        if (geomCached) {
            meta.put("centerLon", ((Number) hist.get("centerLon")).doubleValue());
            meta.put("centerLat", ((Number) hist.get("centerLat")).doubleValue());
            List<Double> cachedBox = new ArrayList<>();
            for (Object v : (List<?>) hist.get("bbox")) {
                cachedBox.add(v instanceof Number ? ((Number) v).doubleValue() : null);
            }
            bbox = cachedBox;
            meta.put("bbox", bbox);
        } else {
            try {
                double[] center = AscGridMetadataReader.transformCenter(first, sourceCrs);
                double[] box = AscGridMetadataReader.transformBbox(first, sourceCrs);
                meta.put("centerLon", center[0]);
                meta.put("centerLat", center[1]);
                bbox = Arrays.asList(box[0], box[1], box[2], box[3]);
                meta.put("bbox", bbox);
            } catch (Exception e) {
                meta.put("centerLon", null);
                meta.put("centerLat", null);
                meta.put("bbox", null);
                System.err.println("avaflow_beta 历史坐标转换失败 " + jobId + ": " + e.getMessage());
            }
        }

        // 地形调控信息：优先取历史元数据，旧任务回落到任务目录里的 terrain_edits.json
        Object terrainEdits = hist.get("terrainEdits");
        // 只在首次扫描（无缓存）时回查任务目录：WSL 的 UNC 路径每次探测都要上百毫秒，
        // 查过一次就把结果写进 history_meta.json，后续列表不再访问任务目录。
        if (!cached && (!(terrainEdits instanceof Collection) || ((Collection<?>) terrainEdits).isEmpty())) {
            File legacyEdits = new File(new File(avaflowJobsRoot, jobId), "terrain_edits.json");
            try {
                if (legacyEdits.isFile()) {
                    terrainEdits = OBJECT_MAPPER.readValue(legacyEdits, Object.class);
                }
            } catch (Exception ignored) {
                // 任务目录不可达（例如 WSL 未启动）时忽略，结果帧仍可回放
            }
        }
        meta.put("terrainEdits", terrainEdits);

        // 旧任务（没有 history_meta.json）第一次读取时把统计结果缓存下来，后续查询更快
        if (!cached) {
            Map<String, Object> cache = new LinkedHashMap<>(hist);
            cache.put("jobId", jobId);
            cache.put("model", "avaflow_beta");
            if (!(cache.get("createdAtEpoch") instanceof Number)) {
                cache.put("createdAtEpoch", createdEpochOf(jobId, jobDir));
            }
            cache.put("sourceCrs", sourceCrs);
            cache.put("frameCount", frameCount);
            cache.put("globalMin", globalMin);
            cache.put("globalMax", globalMax);
            cache.put("ncols", gridCols);
            cache.put("nrows", gridRows);
            cache.put("cellsize", gridCell);
            cache.put("centerLon", meta.get("centerLon"));
            cache.put("centerLat", meta.get("centerLat"));
            cache.put("bbox", bbox);
            cache.put("terrainEdits", terrainEdits);
            try {
                OBJECT_MAPPER.writeValue(histFile, cache);
            } catch (Exception e) {
                System.err.println("avaflow_beta 历史缓存写入失败 " + jobId + ": " + e.getMessage());
            }
        }

        List<String> frameFiles = new ArrayList<>();
        for (File f : files) {
            frameFiles.add(f.getName());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("outputBase", "/ng/avaflow_beta/" + jobId);
        result.put("ascBase", "/ng/avaflow_beta/" + jobId + "/frames");
        result.put("frameFiles", frameFiles);
        result.put("frameCount", frameCount);
        result.put("bbox", bbox);
        result.put("meta", meta);

        long createdEpoch = createdEpochOf(jobId, jobDir);
        if (hist.get("createdAtEpoch") instanceof Number) {
            createdEpoch = ((Number) hist.get("createdAtEpoch")).longValue();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("jobId", jobId);
        item.put("createdAtEpoch", createdEpoch);
        item.put("createdAtText", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(createdEpoch)));
        item.put("frameCount", frameCount);
        item.put("globalMax", globalMax);
        item.put("area", hist.get("area"));
        item.put("terrainEdited", (terrainEdits instanceof Collection)
                && !((Collection<?>) terrainEdits).isEmpty());
        item.put("bbox", bbox);
        item.put("meta", meta);
        item.put("result", result);
        return item;
    }

    /** 任务时间：优先解析轮询 jobId 里的毫秒时间戳，取不到就用目录修改时间。 */
    private long createdEpochOf(String jobId, File jobDir) {
        String[] parts = jobId.split("_");
        if (parts.length >= 2) {
            try {
                long epoch = Long.parseLong(parts[1]);
                if (epoch > 0L) {
                    return epoch;
                }
            } catch (NumberFormatException ignored) {
                // 兼容非时间戳命名的任务目录
            }
        }
        return jobDir.lastModified();
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


    private String newAvaflowJobId() {
        return "avaflow_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String requireAvaflowJobId(String value) {
        String jobId = value == null ? "" : value.trim();
        if (!jobId.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("\u65e0\u6548\u7684\u4efb\u52a1ID");
        }
        return jobId;
    }

    private String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    private String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }



    /** 将 Windows UNC 路径转为 WSL 内路径： \\wsl.localhost\Ubuntu-20.04\home\wm -> /home/wm */
    private String toLinuxPath(String p) {
        String s = p.replace("\\\\wsl.localhost\\", "").replace("\\\\wsl$\\", "");
        // drop distro name segment if present (Ubuntu-20.04)
        if (s.startsWith("Ubuntu-20.04")) s = s.substring("Ubuntu-20.04".length());
        // convert backslashes
        return s.replace("\\", "/");
    }

    /** r.avaflow 输入高程地形调控脚本（沿程调控：手绘多边形对 elevation 加高）。 */
    private String avaflowTerrainScript() {
        return new File(new File(projectRoot, "suanfa/avaflow"), "apply_terrain_edits.py").getAbsolutePath();
    }

    /** r.avaflow 沿程调控效果自检脚本（范围内是否过流、峰值流深）。 */
    private String avaflowCheckScript() {
        return new File(new File(projectRoot, "suanfa/avaflow"), "check_regulation_effect.py").getAbsolutePath();
    }

    /**
     * 沿程调控效果自检：读取本次输出的全部 ASC 帧求峰值场，统计调控范围内的过流情况，
     * 并把 flowPathCells / flowPathMax 并入 terrainEdits（与断链调控(Pro)侧字段一致，
     * 前端即可复用同一套「是否真的流经该范围」的提示逻辑）。失败时原样返回，不影响结果。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> inspectAvaflowRegulation(File framesDir, String prefix,
            String sourceCrs, File editsFile, Object baseEdits) {
        List<Map<String, Object>> base = new ArrayList<>();
        if (baseEdits instanceof Collection) {
            for (Object o : (Collection<?>) baseEdits) {
                if (o instanceof Map) {
                    base.add(new LinkedHashMap<>((Map<String, Object>) o));
                }
            }
        }
        if (base.isEmpty() || framesDir == null || !framesDir.isDirectory()
                || editsFile == null || !editsFile.isFile()) {
            return base.isEmpty() ? null : base;
        }
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    pythonExe, avaflowCheckScript(),
                    "--frames-dir", framesDir.getAbsolutePath(),
                    "--prefix", prefix,
                    "--crs", sourceCrs,
                    "--edits", editsFile.getAbsolutePath()));
            ProcessResult pr = runProcess(cmd, new File(projectRoot), 600,
                    "[avaflow_check] ", StandardCharsets.UTF_8);
            String json = extractSentinel(pr.output, "AVAFLOW_CHECK_JSON=");
            if (json.isEmpty()) {
                return base;
            }
            Map<String, Object> res = OBJECT_MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            if (!"ok".equals(String.valueOf(res.get("status"))) || !(res.get("applied") instanceof List)) {
                return base;
            }
            for (Object o : (List<?>) res.get("applied")) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) o;
                int idx = m.get("index") instanceof Number ? ((Number) m.get("index")).intValue() : -1;
                for (Map<String, Object> item : base) {
                    int itemIdx = item.get("index") instanceof Number
                            ? ((Number) item.get("index")).intValue() : -1;
                    if (idx > 0 && itemIdx == idx) {
                        item.put("flowPathCells", m.get("flowPathCells"));
                        item.put("flowPathMax", m.get("flowPathMax"));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[avaflow_check] 调控效果自检失败（不影响结果回放）: " + e.getMessage());
        }
        return base;
    }

    /** r.avaflow 剖面线（profile）校验 / 自动生成脚本。 */
    private String avaflowProfileScript() {
        return new File(new File(projectRoot, "suanfa/avaflow"), "make_profile.py").getAbsolutePath();
    }

    /**
     * 计算前准备运行上下文：
     * 1) 校验配置里的 profile 是否落在本次 DEM 内，不在则按「释放区中心—最陡下降路径」自动生成；
     * 2) 读取上传高程栅格自带的坐标系，供前端把 ASC 帧换算到正确的经纬度。
     * 任何异常都返回 null，由调用方回退到配置值（不改变既有案例行为）。
     */
    private Map<String, Object> prepareAvaflowRunContext(File elevationFile, File releaseFile, String area) {
        try {
            if (elevationFile == null || !elevationFile.isFile()) {
                return null;
            }
            String areaKey = (area == null || area.isEmpty()) ? "default" : area;
            String configured = env.getProperty("app.avaflow.profile." + areaKey,
                    env.getProperty("app.avaflow.profile", avaflowProfileDefault));
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    pythonExe, avaflowProfileScript(),
                    "--elev", elevationFile.getAbsolutePath()));
            if (releaseFile != null && releaseFile.isFile()) {
                cmd.add("--release");
                cmd.add(releaseFile.getAbsolutePath());
            }
            if (configured != null && !configured.trim().isEmpty()) {
                cmd.add("--candidate");
                cmd.add(configured.trim());
            }
            ProcessResult pr = runProcess(cmd, new File(projectRoot), 180,
                    "[avaflow_profile] ", StandardCharsets.UTF_8);
            String json = extractSentinel(pr.output, "AVAFLOW_PROFILE_JSON=");
            if (json.isEmpty()) {
                return null;
            }
            Map<String, Object> res = OBJECT_MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            if (!"ok".equals(String.valueOf(res.get("status")))) {
                return null;
            }
            String value = res.get("profile") == null ? "" : String.valueOf(res.get("profile")).trim();
            if (value.isEmpty() || !value.matches("[0-9eE+\\-.,\\s]+")) {
                return null;
            }
            if (!"configured".equals(String.valueOf(res.get("source")))) {
                System.out.println("[avaflow_profile] 配置剖面不在本次 DEM 内，已自动生成: " + value);
            }
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("profile", value);
            ctx.put("sourceCrs", res.get("sourceCrs"));
            return ctx;
        } catch (Exception e) {
            System.err.println("[avaflow_profile] 剖面/坐标系识别失败，沿用配置值: " + e.getMessage());
            return null;
        }
    }

    private String buildStartScript(String prefix, String area, String jobDirLinux, String jobId,
                                    String elevationPathLinux, String profileOverride) {
        String suffix = jobId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String elevRaster = "beta_elev_" + suffix;
        String debrisRaster = "beta_debris_" + suffix;
        String impactRaster = "beta_impact_" + suffix;
        String elevationInput = (elevationPathLinux == null || elevationPathLinux.isEmpty())
                ? jobDirLinux + "/inputs/elev.tif" : elevationPathLinux;

        StringBuilder sb = new StringBuilder();
        sb.append("# r.avaflow beta script (auto-generated)\n");
        sb.append("r.in.gdal -o --overwrite input=")
                .append(shellQuote(elevationInput))
                .append(" output=").append(elevRaster).append("\n");
        sb.append("r.in.gdal -o --overwrite input=")
                .append(shellQuote(jobDirLinux + "/inputs/debris.tif"))
                .append(" output=").append(debrisRaster).append("\n");
        sb.append("r.in.gdal -o --overwrite input=")
                .append(shellQuote(jobDirLinux + "/inputs/impact_area.tif"))
                .append(" output=").append(impactRaster).append("\n");
        sb.append("g.region -s rast=").append(elevRaster).append("\n");

        String areaKey = (area == null || area.isEmpty()) ? "default" : area;
        String configuredProfile = env.getProperty("app.avaflow.profile." + areaKey,
                env.getProperty("app.avaflow.profile", avaflowProfileDefault));
        String profile = (profileOverride == null || profileOverride.isEmpty())
                ? configuredProfile : profileOverride;
        String friction = env.getProperty("app.avaflow.friction", avaflowFriction);
        String time = env.getProperty("app.avaflow.time", avaflowTime);
        String phases = env.getProperty("app.avaflow.phases", avaflowPhases);
        sb.append("r.avaflow.40G prefix=").append(prefix)
                .append(" phases=").append(phases)
                .append(" elevation=").append(elevRaster)
                .append(" hrelease=").append(debrisRaster)
                .append(" rhrelease1=0.8")
                .append(" friction=").append(friction)
                .append(" time=").append(time)
                .append(" impactarea=").append(impactRaster)
                .append(" profile=").append(profile)
                .append(" visualization=0,1.0,5.0,5.0,1,200,5,0,3000,50,0.30,0.30,0.60,0.2,1.0,None,None,None\n");
        sb.append("g.region -d\n");
        return sb.toString();
    }

    /**
     * Copies r.avaflow ASC frames into the nginx static directory and returns
     * grid metadata for the front-end shader renderer.
     */
    private Map<String, Object> prepareAvaflowAscFrames(
            String asciiDir,
            String prefix,
            String staticDir,
            String jobId,
            String sourceCrs) {
        Map<String, Object> out = new HashMap<>();
        File dir = new File(asciiDir);
        File[] files = dir.listFiles((d, name) -> name.matches(prefix + "_hflow\\d{4}\\.asc"));
        File outDir = new File(new File(staticDir, "avaflow_beta"), jobId);
        File framesDir = new File(outDir, "frames");
        if (!framesDir.exists() && !framesDir.mkdirs()) {
            out.put("frameCount", 0);
            out.put("outputBase", "/ng/avaflow_beta/" + jobId);
            out.put("ascBase", "/ng/avaflow_beta/" + jobId + "/frames");
            out.put("frameFiles", Collections.emptyList());
            out.put("meta", Collections.emptyMap());
            return out;
        }

        List<String> frameFiles = new ArrayList<>();
        AscGridMetadataReader.GridInfo first = null;
        double globalMin = Double.POSITIVE_INFINITY;
        double globalMax = Double.NEGATIVE_INFINITY;
        boolean hasValue = false;

        if (files != null && files.length > 0) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File file : files) {
                try {
                    AscGridMetadataReader.GridInfo info = AscGridMetadataReader.read(file);
                    if (first == null) {
                        first = info;
                    }
                    if (info.hasValue) {
                        globalMin = Math.min(globalMin, info.minValue);
                        globalMax = Math.max(globalMax, info.maxValue);
                        hasValue = true;
                    }
                    File target = new File(framesDir, file.getName());
                    Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    frameFiles.add(file.getName());
                } catch (Exception e) {
                    System.err.println("ASC 帧准备失败: " + file.getName() + " -> " + e.getMessage());
                }
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceCrs", sourceCrs);
        if (first != null) {
            meta.put("ncols", first.ncols);
            meta.put("nrows", first.nrows);
            meta.put("cellsize", first.cellSize);
            try {
                double[] center = AscGridMetadataReader.transformCenter(first, sourceCrs);
                double[] bbox = AscGridMetadataReader.transformBbox(first, sourceCrs);
                meta.put("centerLon", center[0]);
                meta.put("centerLat", center[1]);
                meta.put("bbox", Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
            } catch (Exception e) {
                meta.put("centerLon", null);
                meta.put("centerLat", null);
                meta.put("bbox", null);
                System.err.println("ASC 坐标转换失败: " + e.getMessage());
            }
        }
        meta.put("globalMin", hasValue ? globalMin : 0.0);
        meta.put("globalMax", hasValue ? globalMax : 0.0);

        out.put("frameCount", frameFiles.size());
        out.put("outputBase", "/ng/avaflow_beta/" + jobId);
        out.put("ascBase", "/ng/avaflow_beta/" + jobId + "/frames");
        out.put("frameFiles", frameFiles);
        out.put("meta", meta);
        out.put("bbox", meta.get("bbox"));
        return out;
    }
    private Map<String, Object> convertAvaflowFrames(
            String asciiDir,
            String prefix,
            String staticDir,
            String jobId,
            String sourceCrs) {
        Map<String, Object> out = new HashMap<>();
        File dir = new File(asciiDir);
        File[] files = dir.listFiles((d, name) -> name.matches(prefix + "_hflow\\d{4}\\.asc"));
        int count = 0;
        double[] bbox = null;
        File outDir = new File(new File(staticDir, "avaflow_beta"), jobId);
        if (!outDir.exists() && !outDir.mkdirs()) {
            out.put("frameCount", 0);
            out.put("outputBase", "/ng/avaflow_beta/" + jobId);
            out.put("bbox", null);
            return out;
        }

        if (files != null && files.length > 0) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : files) {
                File geo = new File(outDir, "avaflow_output" + (count + 1) + ".geojson");
                try {
                    double[] frameBbox = AscToGeoJSONConverter.convert(f, geo, sourceCrs);
                    if (geo.isFile() && geo.length() > 0) {
                        count++;
                        if (frameBbox != null) {
                            if (bbox == null) {
                                bbox = frameBbox.clone();
                            } else {
                                bbox[0] = Math.min(bbox[0], frameBbox[0]);
                                bbox[1] = Math.min(bbox[1], frameBbox[1]);
                                bbox[2] = Math.max(bbox[2], frameBbox[2]);
                                bbox[3] = Math.max(bbox[3], frameBbox[3]);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("\u8f6c\u6362\u5931\u8d25: " + f.getName() + " -> " + e.getMessage());
                }
            }
        }
        out.put("frameCount", count);
        out.put("outputBase", "/ng/avaflow_beta/" + jobId);
        out.put("bbox", bbox == null
                ? null
                : Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
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

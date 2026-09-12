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

    @Value("${app.avaflow.time:10,400}")
    private String avaflowTime;

    @Value("${app.avaflow.profile:159256,3319753,158535,3318924,158097,3318218,157556,3317198,157084,3316176,156786,3315547,156579,3314835}")
    private String avaflowProfileDefault;

    @Value("${app.avaflow.expected-frames:41}")
    private int avaflowExpectedFrames;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment env;

    @Value("${app.avaflow.static-dir:E:/softwares/nginx-1.26.2/nginx-1.26.2/html}")
    private String avaflowStaticDir;

    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> avaflowJobs = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${app.process-timeout:600}")
    private long processTimeoutSeconds;

    // ---- 山洪泥石流启动动力学模型（Pro / python_port 数值内核）----
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

        //批量执行多时间段（3、6、12、24h等）
        for(int i=0;i<time.size();i++){
            int nums=time.size();
            String x = TRIGRS(projectRoot, time.get(i),rsl,depth,diffus,ksat,zmax,color,nums);
            nums_n.add(x);
        }
        Set<String> processedPids = new HashSet<>();
        Set<String> p_name = new HashSet<>();
        //检测exe执行完毕后生成相应的小时图
        for (int i = 0; i < time.size(); i++) {
            // 每次执行时开始检查所有进程是否完成
            boolean allPidsFinished = false;
            // 循环检查进程状态，直到所有进程都完成
            while (!allPidsFinished) {
                // 检查是否有 PID 未在运行
                allPidsFinished = true;

            if (checkAndExecute(projectRoot, nums_n, color, time.get(i),processedPids,p_name)) {
                break;
            }
            // 检查是否还有 PID 在运行
            for (String pid : nums_n) {
                if (isPidRunning(pid)) {
                    allPidsFinished = false; // 如果有任何 PID 在运行，则标记为未完成
                    break;
                }
            }
                // 等待 1 秒后再检查一次
                if (!allPidsFinished)
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
        }  System.out.println("completed for time: " + time.get(i));
        }

        System.out.println("模型执行完毕");
        // 将 Set 转换为 List
        List<String> list = new ArrayList<>(p_name);
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
    public static boolean checkAndExecute(String projectRoot, List<String> pids, String color, String time, Set<String> processedPids,Set<String> p_name) throws Exception {
        boolean anyPidStopped = false;
        for (String pid : pids) {
            if (!isPidRunning(pid)&& !processedPids.contains(pid)) {
                // 如果有 PID 不在运行，则执行代码
                processedPids.add(pid); // 标记该 PID 已处理
                String z=GrayscaleImageGenerator(projectRoot, color, time);
                p_name.add(z);
//                return true; // 执行过操作

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
                ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "start", "", "/D", new java.io.File(projectRoot).getAbsolutePath(), new java.io.File(projectRoot, "TRIGRS.exe").getAbsolutePath());//cmd启动新线程执行TRIGRS
                builder.directory(new java.io.File(projectRoot));
                 builder.start();//启动cmd，进而启动TRIGRS.exe
                ProcessBuilder builder1 = new ProcessBuilder("cmd", "/c","tasklist", "/FI", "IMAGENAME eq TRIGRS.exe");
//                ProcessBuilder builder1 = new ProcessBuilder("cmd", "/c","tasklist");

                // 获取 tasklist 命令的输出流
                Process process =  builder1.start();
                process.waitFor();
                BufferedReader reader1 = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("GBK")));
//                String line1;
                // 用于存储所有行的列表
                List<String> lines = new ArrayList<>();
                String line1;
//                int index =0;  // 用于追踪当前存储到 pid 数组的位置

                while ((line1 = reader1.readLine()) != null) {
                    lines.add(line1);
//                    System.out.println(line1);
                }
                // 检查是否有内容，并匹配倒数第一行
                if (!lines.isEmpty()) {
                    // 获取倒数第一行
                    String lastLine = lines.get(lines.size() - 1);
                    // 使用正则表达式匹配 PID（假设 PID 是每行的第二个字段）
                    // 定义正则表达式匹配规则
                    Pattern pattern = Pattern.compile("\\s+(\\d+)\\s+Console"); // 匹配示例正则
                    Matcher matcher = pattern.matcher(lastLine);
                    if (matcher.find()) {

                        String matchedValue = matcher.group(1); // 获取匹配的第一个捕获组
                        result1[0] = matchedValue;
//                        System.out.println("匹配结果: " + matchedValue);
                    }else {
                        System.out.println("倒数第一行未匹配到内容！");
                    }
                }else{
                    System.out.println("没有内容可读取！");
                }
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

    //风险txt文件转为png
    public static String GrayscaleImageGenerator(String projectRoot,String color,String time) throws Exception {
        System.out.println("开始生成图-----");
        String File = new java.io.File(projectRoot, "data/result/TRfs_min_tutorial_1.txt").getAbsolutePath(); // 输入文件路径
        // 自动创建唯一的临时文件，前缀为 "temp_"，后缀为 ".txt"
        Path inputFile = Files.createTempFile("temp_", ".txt");
        // 拷贝原文件到临时文件
        Files.copy(Paths.get(File), inputFile, StandardCopyOption.REPLACE_EXISTING);
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

            CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:32646");
            CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326");
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
                uniqueFileName= "dangerLevel_" + timestamp +"_"+time+ ".png";
            }else{
                uniqueFileName= "redGradient_" + timestamp + ".png";
            }
            ImageIO.write(image, "png", new java.io.File(staticDir, uniqueFileName));
            // 删除临时文件
            Files.deleteIfExists(inputFile);
//            System.out.println("临时文件已销毁。");
            System.out.println("图像生成为:"+uniqueFileName);
            z=destPts;
            q=uniqueFileName;
        } catch (IOException e) {
            e.printStackTrace();
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
    @PostMapping("/GBM")
    public ResponseEntity<?> runInference(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
            Map<String, Object> form = (Map<String, Object>) body.get("form");
            String jsonStr = OBJECT_MAPPER.writeValueAsString(form);

            if (files == null || files.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing 'files'");
            }

            String firstPath = (String) files.get(0).get("savedPath");
            if (firstPath == null) {
                return ResponseEntity.badRequest().body("Invalid file path");
            }
            File firstFile = new File(firstPath);
            String folder = firstFile.getParent();
            String shpfile = firstFile.getName();

            ProcessResult pr = runProcess(
                    Arrays.asList(pythonExe, inferenceScript, shpfile, jsonStr),
                    null, processTimeoutSeconds, "[Python] ", StandardCharsets.UTF_8);
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError().body("Python script failed");
            }
            String outputShpPath = extractSentinel(pr.output, "OUTPUT_PATH=");
            if (outputShpPath.isEmpty()) {
                return ResponseEntity.internalServerError().body("No output shapefile path from Python");
            }

            //  Shapefile to GeoJSON
            System.setProperty("org.geotools.shapefile.charset", "GBK");
            File shpFile = new File(outputShpPath);
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
                return ResponseEntity.internalServerError().body("Python script failed");
            }

            String outputJson = extractSentinel(pr.output, "RESULT_JSON=");
            String echarts_data = extractSentinel(pr.output, "echarts=");
            if (outputJson.isEmpty()) {
                return ResponseEntity.internalServerError().body("No result from Python");
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
                String scriptPathLinux = jobDirLinux + "/start_beta.sh";
                String startScript = buildStartScript(prefix, area, jobDirLinux, jobId);
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
                        asciiDir, prefix, avaflowStaticDir, jobId, avaflowSourceCrs);
                int frameCount = ((Number) conv.get("frameCount")).intValue();
                if (frameCount <= 0) {
                    job.put("status", "error");
                    job.put("phase", "error");
                    job.put("message", "\u672a\u627e\u5230\u8f93\u51fa\u5e27(hflowNNNN.asc)");
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
    // 山洪泥石流启动动力学模型（Pro）：调用 suanfa/Pro/python_port 数值内核
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

    /** 是否已上传 zb/zl/hw 三幅 tif 到 <jobDir>/inputs。 */
    private static boolean hasUploadedProInputs(File inputDir) {
        if (inputDir == null || !inputDir.isDirectory()) {
            return false;
        }
        for (String name : Arrays.asList("zb.tif", "zl.tif", "hw.tif")) {
            File f = new File(inputDir, name);
            if (!f.isFile() || f.length() == 0) {
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
     */
    @PostMapping("/pro_upload")
    public ResponseEntity<?> uploadProInputs(
            @RequestParam(value = "zb", required = false) MultipartFile zb,
            @RequestParam(value = "zl", required = false) MultipartFile zl,
            @RequestParam(value = "hw", required = false) MultipartFile hw,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "jobId", required = false) String requestedJobId) {
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

            for (Map.Entry<String, MultipartFile> e : picked.entrySet()) {
                File target = new File(inputDir, e.getKey() + ".tif");
                e.getValue().transferTo(target);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("jobId", jobId);

            ProcessResult pr = runProcess(
                    Arrays.asList(pythonExe, proScriptPath(), "--probe", "--job-dir", jobDir.getAbsolutePath()),
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

        // 输入来源：优先用上传到 <jobDir>/inputs 的 tif，
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
        final int maxFrames = (int) Math.max(1, Math.min(300, numOf(params, "maxFrames", proDefaultMaxFrames)));
        String field = str(params, "field");
        if (!Arrays.asList("total", "water", "solid", "speed").contains(field)) {
            field = "total";
        }
        final String fieldArg = field;
        final String targetCrs = str(params, "targetCrs");

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
                        "--max-frames", String.valueOf(maxFrames),
                        "--field", fieldArg));
                if (!targetCrs.isEmpty()) {
                    cmd.add("--target-crs");
                    cmd.add(targetCrs);
                }

                ProcessResult pr = runProcess(cmd, new File(projectRoot), proTimeoutSeconds, "[pro] ", StandardCharsets.UTF_8);
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
                String sourceCrs = (meta.get("sourceCrs") == null || String.valueOf(meta.get("sourceCrs")).isEmpty())
                        ? avaflowSourceCrs
                        : String.valueOf(meta.get("sourceCrs"));

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
                for (String key : Arrays.asList("field", "globalMax", "globalMin", "dx", "dy",
                        "interval", "tmax", "ncols", "nrows", "cellsize")) {
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

    private String buildStartScript(String prefix, String area, String jobDirLinux, String jobId) {
        String suffix = jobId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String elevRaster = "beta_elev_" + suffix;
        String debrisRaster = "beta_debris_" + suffix;
        String impactRaster = "beta_impact_" + suffix;

        StringBuilder sb = new StringBuilder();
        sb.append("# r.avaflow beta script (auto-generated)\n");
        sb.append("r.in.gdal -o --overwrite input=")
                .append(shellQuote(jobDirLinux + "/inputs/elev.tif"))
                .append(" output=").append(elevRaster).append("\n");
        sb.append("r.in.gdal -o --overwrite input=")
                .append(shellQuote(jobDirLinux + "/inputs/debris.tif"))
                .append(" output=").append(debrisRaster).append("\n");
        sb.append("r.in.gdal -o --overwrite input=")
                .append(shellQuote(jobDirLinux + "/inputs/impact_area.tif"))
                .append(" output=").append(impactRaster).append("\n");
        sb.append("g.region -s rast=").append(elevRaster).append("\n");

        String areaKey = (area == null || area.isEmpty()) ? "default" : area;
        String profile = env.getProperty("app.avaflow.profile." + areaKey,
                env.getProperty("app.avaflow.profile", avaflowProfileDefault));
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
        return new ProcessResult(process.exitValue(), out.toString());
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

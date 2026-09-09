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
    @Value("${app.avaflow.grass-gisdbase:/home/wm/grassdata/demo1/PERMANENT}")
    private String avaflowGrassGisdbase;

    @Value("${app.avaflow.static-dir:D:/practice/nginx-1.24.0/html}")
    private String avaflowStaticDir;

    @Value("${app.process-timeout:600}")
    private long processTimeoutSeconds;

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
            String x = TRIGRS(time.get(i),rsl,depth,diffus,ksat,zmax,color,nums);
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

            if (checkAndExecute(nums_n, color, time.get(i),processedPids,p_name)) {
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
    public static boolean checkAndExecute(List<String> pids, String color, String time, Set<String> processedPids,Set<String> p_name) throws Exception {
        boolean anyPidStopped = false;
        for (String pid : pids) {
            if (!isPidRunning(pid)&& !processedPids.contains(pid)) {
                // 如果有 PID 不在运行，则执行代码
                processedPids.add(pid); // 标记该 PID 已处理
                String z=GrayscaleImageGenerator(color, time);
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
    public static String TRIGRS(String time,String rsl,String depth,String diffus,String ksat,String zmax,String color,int nums) throws IOException, InterruptedException {

        final String[] result1 = new String[1];
//        int[] pid = new int[nums];
        // 备份原始文件
        backupFile(ORIG_FILE_PATH, BACKUP_FILE_PATH);

        // 设置tr_in.txt 文件路径
//        String filePath = "D:/code/c/demo1/tr_in.txt";
        String filePath = "./tr_in.txt";


        //读取栅格图的行列号
        String filePath3 = "./data/tutorial/dem.asc";
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
        String filePath4 = "./data/tutorial/TIcelindxList_tutorial.txt";
        BufferedReader reader4 = new BufferedReader(new FileReader(filePath4));
        int lineCount = 0;
        while (reader4.readLine() != null) {
            lineCount++;
        }
        reader4.close();
        String lineCountStr = String.valueOf(lineCount); // 将 lineCount 转换为字符串
        //System.out.println("像元个数: " + lineCountStr);

        //读取nwf（影响像元个数）
        String filePath5 = "./data/tutorial/TIwfactorList_tutorial.txt";
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
//        restoreFile(ORIG_FILE_PATH, BACKUP_FILE_PATH);

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
//        restoreFile(ORIG_FILE_PATH, BACKUP_FILE_PATH);

        CompletableFuture<Void> processFuture = CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "start", "./TRIGRS.exe");//cmd启动新线程执行TRIGRS
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
        restoreFile(ORIG_FILE_PATH, BACKUP_FILE_PATH);

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
    private static final String ORIG_FILE_PATH = "./tr_in.txt";
    private static final String BACKUP_FILE_PATH = "./tr_in_b.txt";
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
    public static String GrayscaleImageGenerator(String color,String time) throws Exception {
        System.out.println("开始生成图-----");
        String File = "./data/result/TRfs_min_tutorial_1.txt"; // 输入文件路径
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
    public ResponseEntity<?> uploadAvaflow(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body("缺少文件");
        }
        try {
            File dir = new File(avaflowWslHome, "DATA1");
            if (!dir.exists()) dir.mkdirs();
            for (MultipartFile f : files) {
                String name = f.getOriginalFilename();
                if (name != null && !name.isEmpty()) {
                    f.transferTo(new File(dir, name));
                }
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("message", "上传成功: " + dir.getAbsolutePath());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/yj_beta")
    public ResponseEntity<?> runAvaflowBeta(@RequestBody Map<String, Object> body) {
        try {
            String area = str(body, "area");
            String prefix = "beta_" + (area == null || area.isEmpty() ? "area" : area) + "_" + System.currentTimeMillis();
            String phases = (str(body, "phases") == null || str(body, "phases").isEmpty()) ? "3" : str(body, "phases");
            String cf = str(body, "cf");
            String bf = str(body, "bf");
            String ff = str(body, "ff");

            // 1) 生成 start_beta.sh（基于 upload 到 DATA1 的文件 + 表单参数）
            String startScript = buildStartScript(prefix, phases, cf, bf, ff);
            File startFile = new File(avaflowWslHome, "start_beta.sh");
            Files.write(startFile.toPath(), startScript.getBytes(StandardCharsets.UTF_8));

            // 2) WSL 执行
            String wslHome = avaflowWslHome.replace("\\\\", "//").replace("\\", "/");
            // UNCs: \wsl.localhost\Ubuntu-20.04\home\wm -> /mnt/... ? 直接用 wsl 内路径约定
            ProcessResult pr = runProcess(
                    Arrays.asList("wsl", "-d", "Ubuntu-20.04", "--", "bash", "-c",
                            "cd " + avaflowWslLinuxHome + " && chmod +x start_beta.sh && grass --exec " + avaflowGrassGisdbase + " bash ./start_beta.sh"),
                    null, processTimeoutSeconds, "[avaflow_beta] ", StandardCharsets.UTF_8);
            if (pr.exitCode != 0) {
                return ResponseEntity.internalServerError().body("avaflow 执行失败，退出码：" + pr.exitCode + "\n" + pr.output);
            }

            // 3) 转换 hflow_max*.asc -> GeoJSON（落到静态目录）
            String asciiDir = new File(avaflowWslHome, prefix + "_results/" + prefix + "_ascii").getPath();
            Map<String, Object> conv = convertAvaflowFrames(asciiDir, prefix, avaflowStaticDir);

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("outputBase", conv.get("outputBase"));
            resp.put("frameCount", conv.get("frameCount"));
            resp.put("message", "洪水泥石流启动动力学模型_beta 完成，输出 " + conv.get("frameCount") + " 帧");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    /** 将 Windows UNC 路径转为 WSL 内路径： \\wsl.localhost\Ubuntu-20.04\home\wm -> /home/wm */
    private String toLinuxPath(String p) {
        String s = p.replace("\\\\wsl.localhost\\", "").replace("\\\\wsl$\\", "");
        // drop distro name segment if present (Ubuntu-20.04)
        if (s.startsWith("Ubuntu-20.04")) s = s.substring("Ubuntu-20.04".length());
        // convert backslashes
        return s.replace("\\", "/");
    }

    private String buildStartScript(String prefix, String phases, String cf, String bf, String ff) {
        String friction = cf + "," + bf + "," + ff + ",0,0,0,0,0,0.05";
        String profile = "159256,3319753,158535,3318924,158097,3318218,157556,3317198,157084,3316176,156786,3315547,156579,3314835";
        StringBuilder sb = new StringBuilder();
        sb.append("# r.avaflow beta script (auto-generated)\n");
        sb.append("r.in.gdal -o --overwrite input=DATA1/elev.tif output=bh_elev\n");
        sb.append("r.in.gdal -o --overwrite input=DATA1/debris.tif output=bh_debrisflow\n");
        sb.append("r.in.gdal -o --overwrite input=DATA1/impact_area.tif output=bh_impactarea\n");
        sb.append("g.region -s rast=bh_elev\n");
        sb.append("r.avaflow.40G prefix=" + prefix + " phases=" + phases + " elevation=bh_elev hrelease=bh_debrisflow rhrelease1=0.8 friction=" + friction + " time=10,400 impactarea=bh_impactarea profile=" + profile + " visualization=0,1.0,5.0,5.0,1,200,5,0,3000,50,0.30,0.30,0.60,0.2,1.0,None,None,None\n");
        sb.append("g.region -d\n");
        return sb.toString();
    }

    private Map<String, Object> convertAvaflowFrames(String asciiDir, String prefix, String staticDir) {
        Map<String, Object> out = new HashMap<>();
        File dir = new File(asciiDir);
        File[] files = dir.listFiles((d, name) -> name.matches(prefix + "_hflow\\d{4}\\.asc"));
        int count = 0;
        String outputBase = avaflowStaticDir + "/avaflow_beta";
        if (files != null && files.length > 0) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            File outDir = new File(outputBase);
            if (!outDir.exists()) outDir.mkdirs();
            // 复用 AscToGeoJSONConverter 逐个转换
            for (File f : files) {
                int idx = count + 1;
                File geo = new File(outDir, "avaflow_output" + idx + ".geojson");
                try {
                    AscToGeoJSONConverter.convert(f, geo);
                } catch (Exception e) {
                    System.err.println("转换失败: " + f.getName() + " -> " + e.getMessage());
                }
                count++;
            }
        }
        out.put("frameCount", count);
        out.put("outputBase", "/ng/avaflow_beta");
        return out;
    }

    /**
     * 解析 tif_to_json.py 的 RESULT_JSON：
     * 兼容批量输出（JSON 数组）与旧版单帧输出（JSON 对象）。
     */
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

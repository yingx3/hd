package com.xyz.controller;

import lombok.Data;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;


import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.HashSet;

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

import static jdk.jfr.internal.SecuritySupport.getAbsolutePath;

@RestController
@RequestMapping("/admin/user")
public class AdminUserController {
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
        if (nums_n.size()==1){
            return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称1:"+list.get(0);
        }else if(nums_n.size()==2){
            return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称1:"+list.get(0)+",图片名称2:"+list.get(1);
        }else if(nums_n.size()==3){
            return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称1:"+list.get(0)+",图片名称2:"+list.get(1)+",图片名称3:"+list.get(2);
        }else if(nums_n.size()==4){
            return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称1:"+list.get(0)+",图片名称2:"+list.get(1)+",图片名称3:"+list.get(2)+",图片名称4:"+list.get(3);
        }else if(nums_n.size()==5){
            return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称1:"+list.get(0)+",图片名称2:"+list.get(1)+",图片名称3:"+list.get(2)+",图片名称4:"+list.get(3)+",图片名称5:"+list.get(4);
        }else {
            return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称1:"+list.get(0)+",图片名称2:"+list.get(1)+",图片名称3:"+list.get(2)+",图片名称4:"+list.get(3)+",图片名称5:"+list.get(4)+",图片名称6:"+list.get(5);
        }
//        return "左下经度:" + z[1] + ", 左下纬度:" + z[0] + ", 右上经度:" + z[3] + ", 右上纬度:" + z[2]+",图片名称："+z[4];
//        return "左下经度:" +  "97.50895326289057"+ ",左下纬度:" + "31.04328676214011" + ",右上经度:" + "97.6075307037595" + ",右上纬度:" + "31.17971326461056"+",图片名称:"+"dangerLevel_20250121_161228_914.png";
    }
    @PostMapping("/yj")
    public String  yjmodelparam(@RequestBody FormData1 formData1)throws Exception{
        System.out.println(formData1);
        System.out.println("接收数据成功！");
        //启动ubuntu20.04
        // 启动 Ubuntu 系统命令，这里以启动 GNOME 终端为例
        try {
            // 创建命令：启动 WSL、切换到 /home/syl 并执行 start1.sh
            String command = "cmd /c start wsl -d Ubuntu-20.04 -- bash -c \"cd /home/syl && sh start1.sh && exec bash\"";

            // 启动 WSL 控制台并执行脚本
            Process process = Runtime.getRuntime().exec(command);

            // 获取命令的输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            // 获取错误输出
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String errorLine;
            while ((errorLine = errorReader.readLine()) != null) {
                System.err.println(errorLine);
            }

            // 等待命令执行完毕
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("WSL 控制台启动成功并执行了 start1.sh 脚本！");
            } else {
                System.out.println("启动失败，退出代码：" + exitCode);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("启动命令失败：" + e.getMessage());
        }
        return "成功执行";
    }
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
        try {
            // 调用 tasklist 命令
            Process process = Runtime.getRuntime().exec("tasklist /FI \"PID eq " + pid + "\"");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(pid)) {
                    return true; // PID 在运行
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false; // PID 不在运行
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
            BufferedImage image = new BufferedImage((int) (width * cellSize), (int) (height * cellSize), BufferedImage.TYPE_INT_ARGB);
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
                        g2d.fillRect((int) (col * cellSize), (int) (row * cellSize), (int) cellSize, (int) cellSize);
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
            ImageIO.write(image, "png", new java.io.File("E:\\softwares\\nginx-1.26.2\\nginx-1.26.2\\html\\"+uniqueFileName));
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
            //  获取文件数组
            List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
            if (files == null || files.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing 'files'");
            }

            //  从第一条文件获取所在目录（假设同一 shapefile 各部件都在同一目录）
            String firstPath = (String) files.get(0).get("savedPath");
            if (firstPath == null) {
                return ResponseEntity.badRequest().body("Invalid file path");
            }

            File firstFile = new File(firstPath);
            String folder = firstFile.getParent();

            //  调用 Python 脚本
            String pythonDir = "./scripts/python";
            // 生成 干净的、解析好的绝对路径（没有 ..）
            File file = new File(pythonDir).getCanonicalFile();
            String realPath = file.getAbsolutePath();
            // 打印最终真实路径
            System.out.println("✅ 解析后真实路径：" + realPath);
            String pythonExe = "./scripts/python/python.exe";
            String pythonScript = "../demo/src/assets/src/inference.py";

            ProcessBuilder pb = new ProcessBuilder(pythonExe, pythonScript, folder);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
//            System.out.print(reader);
            String line;
            String outputShpPath = null;
            while ((line = reader.readLine()) != null) {
                System.out.println("[Python] " + line);
                if (line.startsWith("OUTPUT_PATH=")) {
                    outputShpPath = line.substring("OUTPUT_PATH=".length()).trim();
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return ResponseEntity.internalServerError().body("Python script failed");
            }

            if (outputShpPath == null) {
                return ResponseEntity.internalServerError().body("No output shapefile path from Python");
            }

            //  Shapefile → GeoJSON
            System.setProperty("org.geotools.shapefile.charset", "GBK");
            File shpFile = new File(outputShpPath);
            ShapefileDataStore store = new ShapefileDataStore(shpFile.toURI().toURL());
            store.setCharset(Charset.forName("GBK"));
            SimpleFeatureSource featureSource = store.getFeatureSource();
            SimpleFeatureCollection collection = featureSource.getFeatures();

            CoordinateReferenceSystem sourceCRS = featureSource.getSchema().getCoordinateReferenceSystem();
            CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326", true);
            if (sourceCRS != null && !CRS.equalsIgnoreMetadata(sourceCRS, targetCRS)) {
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
//            System.out.print(resp);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/seismic")
    public ResponseEntity<?> processSeismic(@RequestBody Map<String, Object> body) {
        try {
            // 获取文件路径（从前端传递）
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

            // 调用Python脚本
//            String pythonExe = "D:\\application\\miniconda3\\envs\\test\\python.exe";  // 调整为您的Python环境
            String pythonExe = "C:\\Users\\32838\\.conda\\envs\\anuga_env\\python.exe";  // 调整为您的Python环境
            String pythonScript = "D:\\practice\\seismic.py";  // 修改后的Python文件路径
            ProcessBuilder pb = new ProcessBuilder(pythonExe, pythonScript, excelPath);  // 传递文件路径作为参数
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            String outputJson = "";
            while ((line = reader.readLine()) != null) {
                System.out.println("[Python] " + line);
                if (line.startsWith("RESULT_JSON=")) {
                    outputJson = line.substring("RESULT_JSON=".length()).trim();
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return ResponseEntity.internalServerError().body("Python script failed");
            }

            if (outputJson.isEmpty()) {
                return ResponseEntity.internalServerError().body("No result from Python");
            }

            // 解析JSON（简单手动解析，或用Jackson）
            boolean detected = outputJson.contains("\"detected\": true");

            Map<String, Object> resp = new HashMap<>();
            resp.put("detected", detected);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}



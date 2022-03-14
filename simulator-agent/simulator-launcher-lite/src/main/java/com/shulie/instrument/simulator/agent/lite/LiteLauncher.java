/*
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.shulie.instrument.simulator.agent.lite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.shulie.instrument.simulator.agent.lite.util.JpsCommand;
import com.shulie.instrument.simulator.agent.lite.util.JpsCommand.JpsResult;
import com.shulie.instrument.simulator.agent.lite.util.LogUtil;
import com.shulie.instrument.simulator.agent.lite.util.PropertiesReader;
import com.shulie.instrument.simulator.agent.lite.util.RuntimeMXBeanUtils;
import com.sun.tools.attach.VirtualMachine;

/**
 * @Description agent attach入口
 * @Author ocean_wll
 * @Date 2021/12/16 2:09 下午
 */
public class LiteLauncher {

    /**
     * 对象锁
     */
    private static final Object LOCK = new Object();

    /**
     * agentHome地址
     */
    private static final String DEFAULT_AGENT_HOME
        = new File(LiteLauncher.class.getProtectionDomain().getCodeSource().getLocation().getFile())
        .getParent();

    /**
     * PID文件目录
     */
    private static final String PIDS_DIRECTORY_PATH = DEFAULT_AGENT_HOME + File.separator + "pids";

    /**
     * simulator-launcher-instrument.jar 包地址
     */
    private static final String SIMULATOR_BEGIN_JAR_PATH = DEFAULT_AGENT_HOME + File.separator
        + "simulator-launcher-instrument.jar";

    /**
     * ignore.config 文件地址
     */
    private static final String IGNORE_CONFIG_PATH = DEFAULT_AGENT_HOME + File.separator + "config" + File.separator
        + "ignore.config";

    /**
     * agent.properties 文件地址
     */
    private static final String AGENT_PROPERTIES_PATH = DEFAULT_AGENT_HOME + File.separator + "config" + File.separator
        + "agent.properties";

    /**
     * 定时任务线程池
     */
    private static final ScheduledThreadPoolExecutor poolExecutor = new ScheduledThreadPoolExecutor(1,
        runnable -> new Thread(runnable, "Takin-Lite-Scan-Server"));

    /**
     * agent启动参数配置
     */
    private static final String AGENT_START_PARAM
        = ";simulator.use.premain=true;simulator.lite=%s;simulator.delay=%s;simulator.app.name=%s;simulator.load"
        + ".ttl=%s";

    public static void main(String[] args) throws FileNotFoundException {
        LogUtil.info("simulator-launcher-lite 开始启动");
        PropertiesReader agentProperties = new PropertiesReader(AGENT_PROPERTIES_PATH);
        //首次执行延迟1分钟，之后1分钟执行一次
        poolExecutor.scheduleAtFixedRate(() -> {
            try {
                // 获取attach的Pid列表
                List<JpsResult> systemProcessList = JpsCommand.getSystemProcessList();
                LogUtil.info("system process list：" + systemProcessList);
                if (systemProcessList.size() > 0) {
                    List<JpsResult> attachList = getAttachList(systemProcessList);
                    LogUtil.info("attach list: " + attachList);
                    //生产需要attach的PID列表和生产对应的PID文件
                    attachAgent(agentProperties, attachList);
                    clearSurplusPidFiles(
                        systemProcessList.stream().map(JpsResult::getPid).collect(Collectors.toList()));
                }
            } catch (Throwable t) {
                LogUtil.error(String.format("simulator-launcher-lite error. msg:%s \n stack:%s", t.getMessage(),
                    Arrays.toString(t.getStackTrace())));
            }

        }, 2, 1, TimeUnit.MINUTES);
        LogUtil.info("simulator-launcher-lite 启动成功");
    }

    /**
     * 加载agent
     *
     * @param agentProperties agent配置
     * @param attachList      目标进程PID
     * @throws Exception
     */
    private static void attachAgent(PropertiesReader agentProperties, List<JpsResult> attachList) throws Exception {
        VirtualMachine vm = null;
        for (JpsResult jpsResult : attachList) {
            try {
                vm = VirtualMachine.attach(jpsResult.getPid());
                if (vm != null) {
                    vm.loadAgent(LiteLauncher.SIMULATOR_BEGIN_JAR_PATH, buildStartParam(jpsResult, agentProperties));
                }
                LogUtil.info(jpsResult + ", attach success");
            } catch (Throwable e) {
                deletePidFile(jpsResult.getPid());
                LogUtil.error(
                    String.format("%s, attach error. msg:%s \n stack:%s", jpsResult.getPid(), e.getMessage(),
                        Arrays.toString(e.getStackTrace())));
            } finally {
                if (null != vm) {
                    vm.detach();
                }
            }
        }
    }

    /**
     * 构建agent启动参数
     *
     * @param jpsResult       java进程信息
     * @param agentProperties agent.properties配置信息
     * @return
     */
    private static String buildStartParam(JpsResult jpsResult, PropertiesReader agentProperties) {
        String isLite = agentProperties.getProperty("simulator.lite", "true");
        String delay = agentProperties.getProperty("simulator.delay", "0");
        // 应用名为:jar包名(userId)
        String projectName = jpsResult.getAppName() + "(" + agentProperties.getProperty("pradar.user.id", "-1") + ")";
        String loadTTL = agentProperties.getProperty("simulator.load.ttl", "true");

        return String.format(AGENT_START_PARAM, isLite, delay, projectName, loadTTL);
    }

    /**
     * 获得需要attach的进程列表,并生成对应的PID文件
     *
     * @param systemProcessList 进程列表
     */
    private static List<JpsResult> getAttachList(List<JpsResult> systemProcessList) {
        // 读取文件获取需要跳过的应用名
        List<String> ignoreApps = ignoreAppList();
        File directory = new File(PIDS_DIRECTORY_PATH);
        if (!directory.exists()) {
            //如果目录不存在则进行创建
            directory.mkdirs();
        }
        List<JpsResult> attachList = new ArrayList<>();
        for (JpsResult jpsResult : systemProcessList) {
            // 过滤数据
            if (!jpsResult.isLegal()
                || needIgnore(ignoreApps, jpsResult.getOriginalName().trim())
                || jpsResult.getPid().equals(String.valueOf(RuntimeMXBeanUtils.getPid()))) {
                LogUtil.info("ignore.config exist config, ignore: " + jpsResult);
                continue;
            }
            //创建文件
            String fileName = PIDS_DIRECTORY_PATH + File.separator + jpsResult.getPid();
            File file = new File(fileName);
            try {
                if (!file.exists()) {
                    synchronized (LOCK) {
                        if (!file.exists()) {
                            file.createNewFile();
                            //如果创建了则代表需要attach
                            attachList.add(jpsResult);
                        }
                    }
                } else {
                    LogUtil.info("attached, ignore: " + jpsResult);
                }
            } catch (IOException e) {
                LogUtil.error(Arrays.toString(e.getStackTrace()));
            }
        }
        return attachList;
    }

    /**
     * 判断当前应用是否需要跳过
     *
     * @param ignoreApps   忽略应用列表,只要原始的应用名中包含这个字符串就跳过
     * @param originalName 原始的应用名
     * @return true:需要跳过,false:不需要跳过
     */
    private static boolean needIgnore(List<String> ignoreApps, String originalName) {
        if (ignoreApps == null || ignoreApps.size() == 0) {
            return false;
        }
        for (String ignoreApp : ignoreApps) {
            if (originalName.contains(ignoreApp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取 ignore.config 文件获取需要忽略的应用名
     *
     * @return 需要忽略的应用名
     */
    private static List<String> ignoreAppList() {
        List<String> ignoreAppList = new ArrayList<>();
        File file = new File(IGNORE_CONFIG_PATH);
        if (!file.exists()) {
            return ignoreAppList;
        }
        try (FileInputStream fileInputStream = new FileInputStream(file);
             BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(fileInputStream));) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                ignoreAppList.add(line.trim());
            }
        } catch (Exception e) {
            LogUtil.error("read " + IGNORE_CONFIG_PATH + " error。" + Arrays.toString(e.getStackTrace()));
        }
        return ignoreAppList;
    }

    /**
     * 清理多余的pid文件
     *
     * @param savePidList 保留的pid文件集合
     */
    private static void clearSurplusPidFiles(List<String> savePidList) {
        File[] pidFileList = new File(PIDS_DIRECTORY_PATH).listFiles();
        if (pidFileList == null || pidFileList.length == 0) {
            return;
        }
        List<String> pidNameList = new ArrayList<>();
        Arrays.stream(pidFileList).forEach(pidFile -> pidNameList.add(pidFile.getName()));
        //pid文件根据pid列表移除，剩下的就是不需要的，size=0说明没有重启过，或者刚好一致，
        // 如果>0就说明剩下的文件没有进程与之匹配需要删除
        pidNameList.removeAll(savePidList);
        if (pidNameList.size() > 0) {
            pidNameList.forEach(item -> new File(PIDS_DIRECTORY_PATH + File.separator + item).delete());
        }
    }

    /**
     * 删除对应的pid文件
     *
     * @param deletePid 需要删除的pid
     */
    private static void deletePidFile(String deletePid) {
        File file = new File(PIDS_DIRECTORY_PATH + File.separator + deletePid);
        if (file.exists()) {
            file.delete();
        }
    }
}
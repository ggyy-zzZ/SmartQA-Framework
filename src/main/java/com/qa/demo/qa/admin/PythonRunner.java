package com.qa.demo.qa.admin;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 抽出 LocalKnowledgeOpsService 的 ProcessBuilder + 行流模式，给 AdminOpsService 复用。
 * 每行 stdout 通过 lineConsumer 推送给调用方（通常是 SSE emitter）。
 *
 * <p>行为约定：
 * <ul>
 *   <li>合并 stdout + stderr（redirectErrorStream=true）</li>
 *   <li>非零退出码抛 IOException，由调用方写 failed 状态</li>
 *   <li>继承 DASHSCOPE_API_KEY 到子进程环境</li>
 * </ul>
 */
@Component
public class PythonRunner {

    private final Path projectRoot;
    private final String pythonCommand;
    private final QaAssistantProperties properties;

    public PythonRunner(QaAssistantProperties properties) {
        this.properties = properties;
        this.projectRoot = resolveProjectRoot();
        this.pythonCommand = detectPythonCommand();
    }

    /**
     * 同步执行 Python 脚本；每行 stdout 推送到 lineConsumer。
     */
    public void runScript(List<String> scriptArgs, Consumer<String> lineConsumer) throws IOException, InterruptedException {
        if (pythonCommand == null) {
            throw new IOException("未找到 Python，请安装 Python 3 并加入 PATH。");
        }
        List<String> command = new ArrayList<>();
        command.add(pythonCommand);
        command.addAll(scriptArgs);
        emit(lineConsumer, "执行: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        applyProcessEnv(pb.environment());
        Charset charset = Charset.defaultCharset();
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                emit(lineConsumer, line);
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("脚本退出码 " + code);
        }
        emit(lineConsumer, "脚本执行成功。");
    }

    public Path projectRootPath() {
        return projectRoot;
    }

    public String pythonCommand() {
        return pythonCommand;
    }

    private static void emit(Consumer<String> sink, String line) {
        if (line == null || sink == null) {
            return;
        }
        sink.accept(line);
    }

    private void applyProcessEnv(Map<String, String> env) {
        String dashKey = properties.getDashscopeApiKey();
        if (dashKey != null && !dashKey.isBlank()) {
            env.put("DASHSCOPE_API_KEY", dashKey);
        }
    }

    private static Path resolveProjectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("scripts/ops"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("scripts/ops"))) {
            return parent;
        }
        return cwd;
    }

    private static String detectPythonCommand() {
        for (String cmd : List.of("python", "python3", "py")) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }
}

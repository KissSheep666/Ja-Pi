package dev.jpi.ui;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 终端输入输出 + 行编辑 + 历史 + 补全。
 *
 * <p>非交互环境（管道、重定向）会退回 dumb terminal：没有行编辑，
 * 但 readLine 仍然可用，方便脚本化冒烟测试。
 */
public final class TerminalIO implements AutoCloseable {

    private static final List<String> COMMANDS = List.of(
            "/help", "/model", "/provider", "/think", "/compact", "/clear",
            "/budget", "/keep", "/tools", "/mcp", "/permissions", "/usage", "/quit", "/exit");

    private final Terminal terminal;
    private final LineReader reader;
    private final DefaultHistory history;
    private final Path historyFile;
    private final Ansi ansi;
    private final Object writeLock = new Object();

    private TerminalIO(Terminal terminal, LineReader reader, DefaultHistory history,
                       Path historyFile, Ansi ansi) {
        this.terminal = terminal;
        this.reader = reader;
        this.history = history;
        this.historyFile = historyFile;
        this.ansi = ansi;
    }

    public static TerminalIO open(boolean colorRequested) throws IOException {
        Terminal terminal = buildTerminal();

        boolean color = colorRequested
                && !"dumb".equalsIgnoreCase(terminal.getType())
                && System.getenv("NO_COLOR") == null;

        DefaultHistory history = new DefaultHistory();
        Path historyFile = Path.of(System.getProperty("user.home", "."), ".jpi", "history");
        try {
            if (Files.exists(historyFile)) {
                history.read(historyFile, false);
            }
        } catch (Exception ignored) {
            // 历史文件损坏不应该阻止启动
        }

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .completer(new StringsCompleter(COMMANDS))
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.AUTO_FRESH_LINE, true)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .build();

        return new TerminalIO(terminal, reader, history, historyFile, new Ansi(color));
    }

    private static Terminal buildTerminal() throws IOException {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .name("jpi")
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            if (terminal != null) {
                return terminal;
            }
        } catch (Exception ignored) {
            // native terminal 不可用时退回 dumb
        }
        return TerminalBuilder.builder()
                .dumb(true)
                .streams(System.in, System.out)
                .encoding(StandardCharsets.UTF_8)
                .build();
    }

    public Ansi ansi() {
        return ansi;
    }

    public Terminal terminal() {
        return terminal;
    }

    public boolean interactive() {
        return !"dumb".equalsIgnoreCase(terminal.getType());
    }

    public String readLine(String prompt) throws UserInterruptException, EndOfFileException {
        String line = reader.readLine(prompt);
        // dumb terminal 不会像真实终端那样在回车后换行，管道模式下补一个
        if (!interactive()) {
            println();
        }
        return line;
    }

    public void print(String text) {
        synchronized (writeLock) {
            terminal.writer().print(text);
            terminal.writer().flush();
        }
    }

    public void println(String text) {
        print(text + System.lineSeparator());
    }

    public void println() {
        print(System.lineSeparator());
    }

    public void printf(String format, Object... args) {
        print(String.format(format, args));
    }

    public void flush() {
        synchronized (writeLock) {
            terminal.writer().flush();
        }
    }

    @Override
    public void close() {
        try {
            Path parent = historyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            history.write(historyFile, false);
        } catch (Exception ignored) {
            // 历史写失败不影响退出
        }
        try {
            terminal.close();
        } catch (Exception ignored) {
            // 关闭失败没有补救动作
        }
    }
}

package dev.jpi.tool.impl;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.PatternSyntaxException;

/**
 * glob 匹配的兼容层。
 *
 * <p>Java 的 {@code glob:**}{@code /*.java} 要求路径里至少有一层目录，
 * 匹配不到顶层的 {@code Main.java}；{@code src/**}{@code /*.java} 也匹配不到
 * 直接放在 {@code src/} 下的文件。这两个缺口会让 glob/grep 静默漏文件，
 * 所以这里补两条回退规则：
 * <ul>
 *   <li>模式不含分隔符时，再拿文件名匹配一次；</li>
 *   <li>模式含 {@code **}/ 时，去掉它再匹配一次（相对路径和文件名都试）。</li>
 * </ul>
 */
final class GlobPattern {

    private final PathMatcher primary;
    private final PathMatcher fallback;
    private final boolean bareFilename;

    private GlobPattern(PathMatcher primary, PathMatcher fallback, boolean bareFilename) {
        this.primary = primary;
        this.fallback = fallback;
        this.bareFilename = bareFilename;
    }

    static GlobPattern compile(String glob) throws PatternSyntaxException {
        PathMatcher primary = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        PathMatcher fallback = null;
        if (glob.contains("**/")) {
            try {
                fallback = FileSystems.getDefault()
                        .getPathMatcher("glob:" + glob.replace("**/", ""));
            } catch (PatternSyntaxException ignored) {
                // 去掉 **/ 后语法不合法就只用原模式
            }
        }
        return new GlobPattern(primary, fallback, !glob.contains("/"));
    }

    boolean matches(Path relative) {
        if (primary.matches(relative)) {
            return true;
        }
        Path fileName = relative.getFileName();
        if (bareFilename && fileName != null && primary.matches(fileName)) {
            return true;
        }
        if (fallback != null) {
            return fallback.matches(relative)
                    || (fileName != null && fallback.matches(fileName));
        }
        return false;
    }
}

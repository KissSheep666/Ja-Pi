package dev.jpi.tool;

import dev.jpi.permission.PermissionManager;
import dev.jpi.util.Cancellation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具执行上下文：工作目录 + 路径沙箱 + 权限 + 取消令牌。
 *
 * <p>横切关注点集中在这里：新增能力时，受影响的是一个类，而不是每个工具。
 */
public final class ToolContext {

    private final Path root;
    private final Path rootReal;
    private final PermissionManager permissions;
    private volatile Cancellation cancellation;

    public ToolContext(Path root, PermissionManager permissions) {
        this.root = root.toAbsolutePath().normalize();
        this.permissions = permissions;
        this.rootReal = realPathOrSelf(this.root);
    }

    public Path root() {
        return root;
    }

    public PermissionManager permissions() {
        return permissions;
    }

    public Cancellation cancellation() {
        return cancellation;
    }

    public void setCancellation(Cancellation cancellation) {
        this.cancellation = cancellation;
    }

    /**
     * 把模型给的路径解析成绝对路径，并确保它没有跑出工作目录。
     *
     * <p>除了 {@code ../} 和绝对路径，这里还处理了符号链接：
     * 逐段检查真实路径，指向外部的软链接（包括断链）一律拒绝。
     * 这是参考实现里刻意留下的缺口，产品版补上了。
     *
     * @throws IllegalArgumentException 路径越界或无法校验
     */
    public Path resolve(String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }

        Path candidate;
        try {
            candidate = root.resolve(userPath).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法路径：" + userPath + "（" + e.getMessage() + "）");
        }

        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("路径越出工作目录，已拒绝：" + userPath);
        }

        // 逐段检查软链接；断链或指向外部都拒绝
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                Path real;
                try {
                    real = current.toRealPath();
                } catch (IOException e) {
                    throw new IllegalArgumentException("路径包含失效的符号链接，已拒绝：" + userPath);
                }
                if (!real.startsWith(rootReal)) {
                    throw new IllegalArgumentException(
                            "路径通过符号链接越出工作目录，已拒绝：" + userPath);
                }
            }
        }

        // 再校验最近一个已存在祖先的真实路径。这一条能兜住 Windows junction /
        // 其他 reparse point —— 它们不一定被 isSymbolicLink 识别。
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                if (!existing.toRealPath().startsWith(rootReal)) {
                    throw new IllegalArgumentException(
                            "路径的真实位置越出工作目录，已拒绝：" + userPath);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("无法校验路径的真实位置：" + userPath);
            }
        }
        return candidate;
    }

    /** 绝对路径 → 相对工作目录、用 / 分隔的显示形式。 */
    public String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return absolute.toString();
        }
        Path relative = root.relativize(absolute);
        return relative.toString().isEmpty() ? "." : relative.toString().replace('\\', '/');
    }

    private static Path realPathOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }
}

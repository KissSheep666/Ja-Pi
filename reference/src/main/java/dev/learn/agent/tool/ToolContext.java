package dev.learn.agent.tool;

import dev.learn.agent.permission.PermissionManager;

import java.nio.file.Path;

/**
 * 工具执行时的上下文。
 *
 * <p>M2 里只有工作目录，M3 加上了权限管理器。
 *
 * <p>⚠️ 注意 M2 写的三个工具<b>一行都不用改</b>就获得了权限检查能力。
 * 这就是"把横切关注点放进上下文"的价值：新增能力时，
 * 受影响的是<b>一个</b>类，而不是每一个工具。
 */
public final class ToolContext {

    private final Path root;
    private final PermissionManager permissions;

    public ToolContext(Path root, PermissionManager permissions) {
        this.root = root.toAbsolutePath().normalize();
        this.permissions = permissions;
    }

    public Path root() {
        return root;
    }

    public PermissionManager permissions() {
        return permissions;
    }

    /**
     * 把模型给的路径解析成绝对路径，并<b>确保它没有跑出工作目录</b>。
     *
     * <p>三种越界都能挡住：{@code ../../etc/passwd}、{@code /etc/passwd}、
     * {@code src/../../../x}。
     *
     * <p>⚠️ 这里<b>没有</b>处理符号链接（symlink）。工作目录里如果有一个
     * 指向外部的软链接，仍然能绕过去。真实产品要用 {@code toRealPath()}。
     * 对学习项目来说，知道这个缺口存在就够了 —— 但你必须知道。
     *
     * @throws IllegalArgumentException 路径越界
     */
    public Path resolve(String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path candidate = root.resolve(userPath).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("路径越出工作目录，已拒绝：" + userPath);
        }
        return candidate;
    }

    /** 绝对路径 → 相对工作目录、用 / 分隔的显示形式。给模型看的路径越短越省 token。 */
    public String display(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            return abs.toString();
        }
        return root.relativize(abs).toString().replace('\\', '/');
    }
}

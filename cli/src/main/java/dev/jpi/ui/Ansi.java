package dev.jpi.ui;

/** ANSI 颜色。关闭时所有方法原样返回文本。 */
public final class Ansi {

    public static final String RESET = "[0m";
    public static final String BOLD = "[1m";
    public static final String DIM = "[2m";
    public static final String ITALIC = "[3m";
    public static final String RED = "[31m";
    public static final String GREEN = "[32m";
    public static final String YELLOW = "[33m";
    public static final String BLUE = "[34m";
    public static final String MAGENTA = "[35m";
    public static final String CYAN = "[36m";
    public static final String GRAY = "[90m";

    private final boolean enabled;

    public Ansi(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public String c(String code, String text) {
        return enabled ? code + text + RESET : text;
    }

    public String bold(String text) {
        return c(BOLD, text);
    }

    public String dim(String text) {
        return c(DIM, text);
    }

    public String red(String text) {
        return c(RED, text);
    }

    public String green(String text) {
        return c(GREEN, text);
    }

    public String yellow(String text) {
        return c(YELLOW, text);
    }

    public String blue(String text) {
        return c(BLUE, text);
    }

    public String magenta(String text) {
        return c(MAGENTA, text);
    }

    public String cyan(String text) {
        return c(CYAN, text);
    }

    public String gray(String text) {
        return c(GRAY, text);
    }
}

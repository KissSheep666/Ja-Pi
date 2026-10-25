package dev.jpi.permission;

/** 一次操作的风险等级。 */
public enum Risk {

    /** 基本无副作用。read_file / glob / grep 之类。 */
    LOW,

    /** 会改变工作区状态，但影响可控。写文件、改文件、普通的 rm/mv。 */
    MEDIUM,

    /**
     * 可能造成不可逆的破坏。删库、强推、格式化……
     * HIGH 永不适用"本会话总是允许"—— 每次都必须重新确认。
     */
    HIGH
}

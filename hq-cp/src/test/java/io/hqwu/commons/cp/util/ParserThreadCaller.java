package io.hqwu.commons.cp.util;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 测试辅助：由 SqlMaskerTest 用独立的 ClassLoader / ProtectionDomain 重新定义本类，
 * 模拟「应用代码」帧在调用栈上时触发创建解析线程
 */
public class ParserThreadCaller implements BiFunction<Function<Runnable, Thread>, Runnable, Thread> {
    @Override
    public Thread apply(Function<Runnable, Thread> factory, Runnable task) {
        return factory.apply(task);
    }
}

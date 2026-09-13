package io.hqwu.commons.bean;

import lombok.Data;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 复现：同一对 src/target 连续转换多次时，包装类 -> 原始类型的属性从第二次起丢失。
 */
class BeanConverterRepeatTest {

    @Test
    void repeatedConvertKeepsWrapperToPrimitiveProperty() {
        Src src = new Src();
        src.setName("n");
        src.setFlag(Boolean.TRUE);
        src.setCount(7);

        for (int i = 1; i <= 3; i++) {
            Target target = BeanConverter.convert(src, Target.class);
            assertEquals("n", target.getName(), "第 " + i + " 次转换 name");
            assertEquals(7, target.getCount(), "第 " + i + " 次转换 count");
            assertEquals(true, target.isFlag(), "第 " + i + " 次转换 flag（Boolean -> boolean）");
        }
    }

    @Data
    public static class Src {
        private String name;
        private Boolean flag;
        private Integer count;
    }

    @Data
    public static class Target {
        private String name;
        private boolean flag;
        private Integer count;
    }
}

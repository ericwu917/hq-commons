package io.hqwu.commons.bean;

import io.hqwu.commons.annotation.SourceProperty;
import io.hqwu.commons.util.ClassUtil;
import io.hqwu.commons.util.Logger;
import io.hqwu.commons.util.StringUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.cglib.beans.BeanCopier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationConfigurationException;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Bean 转换工具类，用于在不同类型的 Java Bean 之间进行属性拷贝与类型转换。
 *
 * <p>该工具类结合了 Spring {@link BeanUtils} 的灵活性与 CGLIB {@link BeanCopier} 的高性能，主要功能包括：
 * <ul>
 *     <li><b>自动拷贝：</b> 默认拷贝源对象与目标对象中名称和类型相同的属性。</li>
 *     <li><b>注解驱动映射：</b> 通过 {@link SourceProperty} 注解指定源属性名称、忽略字段或配置字符串缩略。</li>
 *     <li><b>自定义转换：</b> 支持通过 {@link ValueOf} 接口及其实现类定义复杂的字段转换逻辑，并支持从 Spring 上下文获取转换器实例。</li>
 *     <li><b>深度转换：</b> 自动处理嵌套对象以及集合类型（List, Set）的递归转换。</li>
 *     <li><b>数值兼容：</b> 内置常见数值类型（如 Integer, Long, BigDecimal 等）之间的自动转换。</li>
 * </ul>
 *
 * <p><b>已知限制（待实现功能）：</b>
 * <ol>
 *     <li><b>Map 类型的嵌套转换（Line 374）：</b>
 *         <ul>
 *             <li>当前不支持 Map 中元素的递归类型转换</li>
 *             <li>Map 属性会被直接复制引用，元素类型不会自动转换</li>
 *             <li>限制影响：无法将 {@code Map<K, SrcType>} 自动转换为 {@code Map<K, TargetType>}</li>
 *             <li>原因：泛型类型在运行时擦除，难以自动推断键值类型</li>
 *         </ul>
 *     </li>
 *     <li><b>Number 到基本类型的转换（Line 378）：</b>
 *         <ul>
 *             <li>number2SpecificClass 方法不支持基本类型（int, long 等）参数</li>
 *             <li>{@code Number.class.isAssignableFrom(primitiveType)} 对基本类型返回 false</li>
 *             <li>限制影响：Number 包装类到基本类型的转换不通过专门的转换方法</li>
 *             <li>原因：基本类型不是对象，反射 API 对其支持有限</li>
 *             <li>说明：包装类型之间的转换（Integer → Double）正常工作</li>
 *         </ul>
 *     </li>
 *     <li><b>Queue 接口的集合转换（Line 461）：</b>
 *         <ul>
 *             <li>不支持 Queue 接口作为目标集合类型</li>
 *             <li>当前行为：抛出 {@code UnsupportedOperationException}</li>
 *             <li>限制影响：无法将 List 或其他集合转换为 Queue 接口类型</li>
 *             <li>原因：需要选择合适的默认实现，存在多种可能（LinkedList, ArrayDeque 等）</li>
 *             <li>说明：具体的 Queue 实现类（如 LinkedBlockingQueue）可能通过 BeanUtils.instantiateClass 实例化</li>
 *         </ul>
 *     </li>
 *     <li><b>抽象集合类的实例化（Line 464）：</b>
 *         <ul>
 *             <li>不支持抽象集合类（AbstractList, AbstractSet 等）作为目标类型</li>
 *             <li>当前行为：抛出 {@code UnsupportedOperationException}</li>
 *             <li>限制影响：无法将集合转换为抽象集合类型</li>
 *             <li>原因：无法直接实例化抽象类</li>
 *             <li>说明：具体集合类（ArrayList, HashSet）正常工作</li>
 *         </ul>
 *     </li>
 * </ol>
 *
 * <p><b>替代方案：</b>
 * <ul>
 *     <li>对于 Map 转换：手动实现或使用自定义的 {@link ValueOf} 转换器</li>
 *     <li>对于基本类型：依赖 BeanCopier 的自动装箱或使用包装类型</li>
 *     <li>对于 Queue 和抽象集合：使用具体的实现类（ArrayList, LinkedBlockingQueue 等）</li>
 *     <li>对于复杂场景：推荐使用 MapStruct 等编译时代码生成工具</li>
 * </ul>
 *
 * <p>注意：由于反射和动态处理的开销，在高性能要求的静态映射场景下，建议优先使用 MapStruct。
 * 本类主要用于处理 MapStruct 难以覆盖的动态映射或高度抽象的转换需求。
 *
 * @author taige
 * @since 2020/5/4
 * @deprecated 推荐使用 MapStruct 作为替代方案，性能更好且类型安全。但在需要动态转换功能的场景下仍可保留使用。
 * @see <a href="https://mapstruct.org/">MapStruct</a>
 */
@Deprecated
@Component
public class BeanConverter implements ApplicationContextAware {
    private static final Logger LOGGER = new Logger();

    private static ApplicationContext applicationContext;

    private static ConcurrentMap<String, ValueOf<?, ?>> valueOfCache = new ConcurrentHashMap<>();

    private static ConcurrentMap<String, ConvertibleCopier<?, ?>> converterMappings = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static <F, T> ConvertibleCopier<F, T> getConverterMapping(Class<?> srcClass, Class<?> targetClass) {
        String mapKey = srcClass.getName() + ":" + targetClass.getName();
        return (ConvertibleCopier<F, T>) converterMappings.computeIfAbsent(mapKey,
                (Function<String, ConvertibleCopier<F, T>>) key -> {
            ConvertibleCopier<F, T> convertibleCopier = new ConvertibleCopier<>();
            Class<?>[] sourceClasses = null;
            if (targetClass.isAnnotationPresent(SourceProperty.class)) {
                SourceProperty sourceProperty = targetClass.getAnnotation(SourceProperty.class);
                if (sourceProperty.sourceClasses().length > 0) {
                    sourceClasses = sourceProperty.sourceClasses();
                }
            }
            for (Field field: ClassUtil.getAllFields(targetClass, new ArrayList<>())) {
                // if same classes, no convert or mapping, simply do copy
                boolean doConvertOrMapping = srcClass != targetClass;
                if (doConvertOrMapping && field.isAnnotationPresent(SourceProperty.class)) {
                    introspectField(field, srcClass, targetClass, convertibleCopier, sourceClasses);
                } else {
                    // temporarily save all properties mapping, will be remove if copied by BeanCopier
                    String targFieldName = field.getName();
                    PropertyDescriptor targPropDesc = BeanUtils.getPropertyDescriptor(targetClass, targFieldName);
                    PropertyDescriptor srcPropDesc = BeanUtils.getPropertyDescriptor(srcClass, targFieldName);
                    if (targPropDesc != null && srcPropDesc != null) {
                        convertibleCopier.nameMapping.put(targPropDesc, srcPropDesc);
                    }
                }
            }
            return convertibleCopier;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <F, T> void introspectField(Field field, Class<?> srcClass, Class<?> targetClass,
                                               ConvertibleCopier<F, T> convertibleCopier, Class<?>[] defaultSourceClasses) {
        SourceProperty sourceProperty = field.getAnnotation(SourceProperty.class);
        if (! StringUtil.equals(sourceProperty.name(), sourceProperty.value())
                && StringUtil.areNotEmpty(sourceProperty.name(), sourceProperty.value())) {
            throw new AnnotationConfigurationException(String.format(
                    "Different @AliasFor mirror values for annotation [%s]declared on %s.%s; attribute " +
                            "'name' and its alias 'value' are declared with values of [%s] and [%s].",
                    SourceProperty.class.getName(), field.getDeclaringClass().getTypeName(), field.getName(),
                    ObjectUtils.nullSafeToString(sourceProperty.name()),
                    ObjectUtils.nullSafeToString(sourceProperty.value())));
        }
        String targFieldName = field.getName();
        String srcFieldName = targFieldName;
        if (StringUtil.isNotBlank(sourceProperty.name()) || StringUtil.isNotBlank(sourceProperty.value())) {
            srcFieldName = StringUtil.isNotBlank(sourceProperty.name()) ? sourceProperty.name() : sourceProperty.value();
            if ('A' <= srcFieldName.charAt(0) && srcFieldName.charAt(0) <= 'Z') {
                srcFieldName = srcFieldName.substring(0, 1).toLowerCase() + srcFieldName.substring(1);
            }
        }
        if (sourceProperty.sourceClasses().length > 0) {
            if (Arrays.stream(sourceProperty.sourceClasses())
                    .noneMatch(sourceClass -> sourceClass.isAssignableFrom(srcClass))) {
                return;
            }
        } else if (defaultSourceClasses != null) {
            if (Arrays.stream(defaultSourceClasses)
                    .noneMatch(defaultClass -> defaultClass.isAssignableFrom(srcClass))) {
                return;
            }
        }
        try {
            PropertyDescriptor targPropDesc = BeanUtils.getPropertyDescriptor(targetClass, targFieldName);
            if (targPropDesc == null) {
                if (sourceProperty.ignore()) {
                    return;
                }
                throw new IntrospectionException(
                        String.format("The target property `%s` is not defined correctly in class `%s`.",
                                targFieldName, targetClass.getName()));
            }
            String setterName = targPropDesc.getWriteMethod().getName();
            if (sourceProperty.ignore()) {
                convertibleCopier.converterMapping.put(setterName,
                        (srcValue, srcBean, srcProperty, targetBean, targetProperty) -> null);
                return;
            }
            PropertyDescriptor srcPropDesc = BeanUtils.getPropertyDescriptor(srcClass, srcFieldName);
            convertibleCopier.nameMapping.put(targPropDesc, srcPropDesc);
            if (sourceProperty.valueOf().length > 0) {
                Class<? extends ValueOf> cvtClass = sourceProperty.valueOf()[0];
                String[] params = sourceProperty.params();
                ValueOf<F, T> converter = (ValueOf<F, T>) valueOfCache.computeIfAbsent(cvtClass.getName(), s -> {
                    if (applicationContext != null) {
                        try {
                            if (StringUtil.isNotBlank(sourceProperty.qualifier())) {
                                return applicationContext.getBean(sourceProperty.qualifier(), cvtClass);
                            } else {
                                return applicationContext.getBean(cvtClass);
                            }
                        } catch (BeansException ignored) {
                        }
                    }
                    return BeanUtils.instantiateClass(cvtClass);
                });
                if (srcPropDesc == null && ! (converter instanceof ValueOfContext)) {
                    throw new IntrospectionException(
                            String.format("The source property `%s` is not defined correctly in class `%s`.",
                                    srcFieldName, srcClass.getName()));
                }
                convertibleCopier.converterMapping.put(setterName,
                        (srcValue, srcBean, srcProperty, targetBean, targetProperty) -> {
                    T obj = converter.valueOf(srcValue, srcBean, srcProperty, targetBean, targetProperty, params);
                    if (obj instanceof String && sourceProperty.abbreviate() >= 4) {
                        return (T) StringUtils.abbreviate((String) obj, sourceProperty.abbreviate());
                    }
                    return obj;
                });
            } else if (srcPropDesc == null) {
                throw new IntrospectionException(
                        String.format("The source property `%s` is not defined correctly in class `%s`.",
                                srcFieldName, srcClass.getName()));
            } else if (sourceProperty.abbreviate() >=4
                    && targPropDesc.getPropertyType() == String.class
                    && srcPropDesc.getPropertyType() == String.class) {
                convertibleCopier.converterMapping.put(setterName,
                        (srcValue, srcBean, srcProperty, targetBean, targetProperty) ->
                                (T) StringUtils.abbreviate((String) srcValue, sourceProperty.abbreviate())
                );
            }
        } catch (IntrospectionException e) {
            throw new RuntimeIntrospectionException(e);
        }
    }

    /**
     * convert bean from srcBean to targetBean of class T:
     *    1. instantiate targetBean of class T;
     *    2. copy same name & type properties from srcBean to targetBean, see {@link BeanCopier}
     *    3. convert properties with annotation {@link SourceProperty} on class T,
     *          which specific the source property name {@link SourceProperty#name} and/or
     *          convert method {@link SourceProperty#valueOf} (class of {@link ValueOf}).
     *    4. other properties (not copied or converted by 2 & 3):
     *          will use {@link #toString()} as value if target property type is String,
     *          else will not be copied.
     * @param srcBean     Conversion source bean
     * @param targetClass Class of conversion target
     * @param <T>         Target bean type
     * @return            Conversion target bean
     * @throws RuntimeException when invoking target property setter,
     *                          properties/methods access denied
     *                          or properties definition incorrect
     */
    public static <T> T convert(Object srcBean, Class<T> targetClass) throws RuntimeException {
        if (srcBean == null) {
            return null;
        }
        Class<?> srcClass = srcBean.getClass();
        T targetBean = BeanUtils.instantiateClass(targetClass);
        try {
            ConvertibleCopier<?, T> converter = getConverterMapping(srcClass, targetClass);
            converter.copy(srcBean, targetBean);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        return targetBean;
    }

    public static <T> T copy(Object srcBean, T targetBean) throws RuntimeException {
        if (srcBean == null) {
            return targetBean;
        }
        Class<?> srcClass = srcBean.getClass();
        Class<T> targetClass = (Class<T>) targetBean.getClass();
        try {
            ConvertibleCopier<?, T> converter = getConverterMapping(srcClass, targetClass);
            converter.copy(srcBean, targetBean);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        return targetBean;
    }

    /**
     * try to get source property name if the property has {@link SourceProperty} annotation.
     *
     * @param srcClass      the source class
     * @param targetClass   the property name containing class (assumed)
     * @param propertyName  the property name querying
     * @return              the property name in source class if it defined, else null
     */
    @Nullable
    public static <F, T> String getSourcePropertyName(Class<F> srcClass, Class<T> targetClass, String propertyName) {
        ConvertibleCopier<F, T> converter = getConverterMapping(srcClass, targetClass);
        Optional<PropertyDescriptor> optional = converter.nameMapping.entrySet().stream()
                .filter(e -> e.getKey().getName().equals(propertyName))
                .findFirst().map(Map.Entry::getValue);
        if (! optional.isPresent()) {
            PropertyDescriptor pd = BeanUtils.getPropertyDescriptor(srcClass, propertyName);
            if (pd == null) {
                return null;
            } else {
                return propertyName;
            }
        }
        return optional.get().getName();
    }

    @Override
    public void setApplicationContext(ApplicationContext appContext) throws BeansException {
        applicationContext = appContext;
        valueOfCache.clear();
        converterMappings.clear();
    }

    public static class RuntimeIntrospectionException extends RuntimeException {
        private final IntrospectionException introspectionException;
        public RuntimeIntrospectionException(IntrospectionException cause) {
            super(cause);
            this.introspectionException = cause;
        }

        public IntrospectionException getIntrospectionException() {
            return introspectionException;
        }
    }

    private static class ConvertibleCopier<F, T> {
        private volatile BeanCopier copier;
        // target -> src
        final Map<PropertyDescriptor, PropertyDescriptor> nameMapping = new ConcurrentHashMap<>();
        final Map<String, ParamsValueOf<F, T>> converterMapping = new HashMap<>();
        final Set<Object> settersByCopier = ConcurrentHashMap.newKeySet();

        public void copy(Object srcBean, Object targetBean) throws InvocationTargetException, IllegalAccessException {
            if (converterMapping.size() == 0 && nameMapping.size() == 0) {
                if (copier == null || settersByCopier.size() > 0) {
                    synchronized (this) {
                        if (copier == null || settersByCopier.size() > 0) {
                            // re-create `BeanCopier` because all copy-things finished without a `converter`
                            copier = BeanCopier.create(srcBean.getClass(), targetBean.getClass(), false);
                            settersByCopier.clear();
                        }
                    }
                }
                // copy bean using `BeanCopier` without converter
                copier.copy(srcBean, targetBean, null);
                return;
            } else if (copier == null) {
                synchronized (this) {
                    if (copier == null) {
                        copier = BeanCopier.create(srcBean.getClass(), targetBean.getClass(), true);
                    }
                }
            }
            // copy same name properties using `BeanCopier`
            copier.copy(srcBean, targetBean, (origValue, targetClass, targSetterName) -> {
                Object obj = convert(origValue, targetClass, targSetterName, targetBean, null, srcBean);
                if (! settersByCopier.contains(targSetterName) && obj != null && obj.equals(origValue)) {
                    synchronized (this) {
                        settersByCopier.add(targSetterName);
                        if (targSetterName instanceof String) {
                            PropertyDescriptor propertyDescriptor =
                                    getPropertyDescriptor(targetBean, (String) targSetterName, targetClass);
                            PropertyDescriptor srcPropDesc = nameMapping.get(propertyDescriptor);
                            // `obj.equals(origValue)` 只说明 converter 没改值，不代表不带 converter 的 BeanCopier 也会拷它：
                            // 后者仅在类型可直接赋值时才拷，靠自动装箱/拆箱的（如 Boolean -> boolean）会被静默跳过。
                            // 所以只有类型确实可直接赋值时，才能把该属性交给 BeanCopier、从 nameMapping 移除。
                            if (srcPropDesc != null
                                    && propertyDescriptor.getPropertyType().isAssignableFrom(srcPropDesc.getPropertyType())) {
                                // remove properties mapping because handled by BeanCopier
                                nameMapping.remove(propertyDescriptor);
                            }
                        }
                    }
                }
                return obj;
            });
            // handle properties with name mapping
            for (Map.Entry<PropertyDescriptor, PropertyDescriptor> entry: nameMapping.entrySet()) {
                PropertyDescriptor targProperty = entry.getKey();
                PropertyDescriptor srcProperty = entry.getValue();
                Class<?> targetClass = targProperty.getPropertyType();
                Object targetValue = convert(srcProperty == null ? null : srcProperty.getReadMethod().invoke(srcBean),
                        targetClass, targProperty.getWriteMethod().getName(), targetBean,
                        srcProperty == null ? null : srcProperty.getName(), srcBean);
                if (targetClass.isPrimitive() && targetValue == null) {
                    LOGGER.info("skip set primitive property `%s` in %s with NULL value",
                            targProperty.getName(), targetBean.getClass().getName());
                } else {
                    targProperty.getWriteMethod().invoke(targetBean, targetValue);
                }
            }
        }

        @NonNull
        private PropertyDescriptor getPropertyDescriptor(Object targetBean, String targSetterName, Class<?> targetClass) {
            Method method = BeanUtils.findMethod(targetBean.getClass(), targSetterName, targetClass);
            assert method != null;  // Shouldn't happen
            PropertyDescriptor propertyDescriptor = BeanUtils.findPropertyForMethod(method);
            assert propertyDescriptor != null; // Shouldn't happen ?
            return propertyDescriptor;
        }

        @SuppressWarnings("unchecked")
        private Object convert(Object origValue, Class<?> targetClass, Object targSetterName, Object targetBean,
                               String srcPropertyName, Object srcBean) {
            if (! (targSetterName instanceof String)) {
                if (origValue == null || targetClass.isAssignableFrom(origValue.getClass())) {
                    return origValue;
                }
                LOGGER.warn("targSetterName(%s) expected a String, but instanceof %s, converter can't work",
                        targSetterName, targSetterName.getClass().getName());
                return null;
            }
            PropertyDescriptor propertyDescriptor = getPropertyDescriptor(targetBean, (String) targSetterName, targetClass);
            String targFieldName = propertyDescriptor.getName();
            LOGGER.trace("converting property=`%s`, origValue='%s'(%s), targetClass=%s, targSetterName=%s",
                    targFieldName, origValue, origValue == null ? "" : origValue.getClass().getName(), targetClass.getName(), targSetterName);
            if (converterMapping.containsKey(targSetterName)) {
                return valueOf(origValue, targetClass, (String) targSetterName, targetBean, srcPropertyName, srcBean, targFieldName);
            } else if (origValue == null) {
                return null;
            } else if (targetClass.isAssignableFrom(origValue.getClass())) {
                if (Collection.class.isAssignableFrom(targetClass)) {
                    return convertCollection((Collection<Object>) origValue, srcBean,
                            srcPropertyName == null ? targFieldName : srcPropertyName,
                            targetBean, targFieldName, (Class<Collection<Object>>) targetClass);
                }
                // TODO support Map
                return origValue;
            } else if (Number.class.isAssignableFrom(targetClass)
                    && Number.class.isAssignableFrom(origValue.getClass())) {
                // todo support primitive type
                // DONE test
                return number2SpecificClass((Number) origValue, (Class<Number>) targetClass);
            } else if (targetClass.isAssignableFrom(String.class)) {
                LOGGER.trace("default convert everything to String(%s) for property `%s`", origValue, targFieldName);
                return origValue.toString();
            } else if (targetClass.isPrimitive()) {
                if (ClassUtil.isAssignable(origValue.getClass(), targetClass)) {
                    return origValue;
                }
            } else {
                try {
                    return BeanConverter.convert(origValue, targetClass);
                } catch (Exception e) {
                    LOGGER.warn("convert property `%s` from %s to %s with value '%s' failed: ", targFieldName,
                            origValue.getClass().getName(), targetClass.getName(), origValue, e);
                }
            }
            LOGGER.info("didn't convert property `%s` from %s to %s with value '%s'", targFieldName,
                    origValue.getClass().getName(), targetClass.getName(), origValue);
            return null;
        }

        @SuppressWarnings("unchecked")
        private Object valueOf(@Nullable Object origValue, Class<?> targetClass, String targSetterName,
                               Object targetBean, String srcPropertyName, Object srcBean, String targFieldName) {
            Object targValue;
            ParamsValueOf<F, T> valueOf = converterMapping.get(targSetterName);
            try {
                targValue = valueOf.paramsValueOf((F) origValue, srcBean,
                        srcPropertyName == null ? targFieldName : srcPropertyName, targetBean, targFieldName);
            } catch (ClassCastException e) {
                LOGGER.warn("FROM Cast Error: call %s for property `%s` fail: %s",
                        valueOf.getClass().getName(), targFieldName, e.getMessage());
                return null;
            } catch (NullPointerException e) {
                if (origValue == null) {
                    return null;
                }
                throw e;
            }
            if (targValue != null && Number.class.isAssignableFrom(targetClass)
                    && Number.class.isAssignableFrom(targValue.getClass())) {
                targValue = number2SpecificClass((Number) targValue, (Class<Number>) targetClass);
            }
            if (targValue != null && ! targetClass.isAssignableFrom(targValue.getClass())) {
                LOGGER.warn("TO Case Error: property `%s` is %s, but got %s(%s)",
                        targFieldName, targetClass.getName(), targValue.getClass().getName(), targValue);
                return null;
            }
            return targValue;
        }

    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> convertCollection(Collection<Object> srcValue, Object srcBean, String srcProperty, Object targetBean, String targetProperty, Class<Collection<Object>> targetClass) {
        try {
            Type srcTypes = ((ParameterizedType) srcBean.getClass().getDeclaredField(
                    srcProperty == null ? targetProperty : srcProperty).getGenericType()).getActualTypeArguments()[0];
            Type targTypes = ((ParameterizedType) targetBean.getClass().getDeclaredField(
                    targetProperty).getGenericType()).getActualTypeArguments()[0];
            if (! (srcTypes instanceof Class) || ! (targTypes instanceof Class)) {
                return srcValue;
            }
            Collection<Object> targCollection = instantiateCollection(targetClass); //BeanUtils.instantiateClass(targetClass);
            for (Object src: srcValue) {
                Object targ = BeanConverter.convert(src, (Class<Object>) targTypes);
                targCollection.add(targ);
            }
            return targCollection;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static Collection<Object> instantiateCollection(Class<Collection<Object>> collectionClass) {
        if (Modifier.isInterface(collectionClass.getModifiers())) {
            if (List.class.isAssignableFrom(collectionClass)) {
                return new ArrayList<>();
            } else if (Set.class.isAssignableFrom(collectionClass)) {
                return new HashSet<>();
            }
            // TODO support Queue
            throw new UnsupportedOperationException(String.format("unsupported collection interface: %s", collectionClass.getName()));
        } else if (Modifier.isAbstract(collectionClass.getModifiers())) {
            // TODO ?
            throw new UnsupportedOperationException(String.format("unsupported abstract collection: %s", collectionClass.getName()));
        }
        return BeanUtils.instantiateClass(collectionClass);
    }

    private static Number number2SpecificClass(Number n, Class<? extends Number> nClass) {
        if (nClass == Double.class) {
            return n.doubleValue();
        } else if (nClass == Float.class) {
            return n.floatValue();
        } else if (nClass == Long.class) {
            return n.longValue();
        } else if (nClass == Integer.class) {
            return n.intValue();
        } else if (nClass == Short.class) {
            return n.shortValue();
        } else if (nClass == Byte.class) {
            return n.byteValue();
        } else if (nClass == BigDecimal.class) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        LOGGER.warn("BeanConverter.number2SpecificClass didn't really convert %s(%s) -> %s, may cause exception",
                n.getClass().getName(), n, nClass.getName());
        return n;
    }

    @FunctionalInterface
    interface ParamsValueOf<F, T> {
        T paramsValueOf(F srcValue, Object srcBean, String srcProperty, Object targetBean, String targetProperty);
    }

}

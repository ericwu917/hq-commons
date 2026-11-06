package io.hqwu.commons.cp.autoconfigure;

import io.hqwu.commons.cp.HqcpDataSource;
import io.hqwu.commons.cp.HqcpDataSourceBoot;
import io.hqwu.commons.cp.PasswordDecryptor;
import io.hqwu.commons.security.SecurityService;
import io.hqwu.commons.security.SecurityServiceLocalImpl;
import io.hqwu.commons.util.Logger;
import io.hqwu.commons.util.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Hqcp 数据源 Spring Boot 4 自动配置类。
 * <p>
 * 与 hq-cp-boot-starter 里的同名类逻辑一致，区别在于：
 * <ul>
 *   <li>{@link DataSourceAutoConfiguration} / {@link DataSourceProperties} 在 Spring Boot 4 中
 *       迁到了 {@code org.springframework.boot.jdbc.autoconfigure}；</li>
 *   <li>排序用 {@code @AutoConfiguration(before = ...)} 而不是已废弃的 {@code @AutoConfigureBefore}。</li>
 * </ul>
 * 该配置类在 {@link DataSourceAutoConfiguration} 之前运行，
 * 旨在根据 {@link DataSourceProperties} 和可用的 {@link SecurityService} 装配 {@link HqcpDataSource}。
 * <p>
 * 上下文中有 {@link PasswordDecryptor} bean 时注入数据源，取代内置 AES 口令解密
 * （多个且无 {@code @Primary} 时由 Spring 报错）。
 *
 * @author taige (Wu, Hongqiang)
 * @since 2026-09-12
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass(HqcpDataSource.class)
public class HqcpDataSourceAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger();

    @Bean(initMethod = "init")
    @ConditionalOnMissingBean
    public DataSource dataSource(DataSourceProperties basicProperties, ObjectProvider<SecurityService> securityServiceProvider,
                                 ObjectProvider<PasswordDecryptor> passwordDecryptorProvider) {
        LOGGER.debug("init HqcpDataSource");
        SecurityService selectedService = selectPreferRemote(securityServiceProvider);
        return new HqcpDataSourceBoot(basicProperties, selectedService, passwordDecryptorProvider.getIfAvailable());
    }

    /**
     * 从所有可用的 SecurityService Bean 中选择一个。
     * 优先选择非 SecurityServiceLocalImpl 的实例（即远程实现）。
     * @param provider Spring 提供的 ObjectProvider
     * @return 选中的 SecurityService，如果没有则返回 null
     */
    private SecurityService selectPreferRemote(ObjectProvider<SecurityService> provider) {
        // 获取所有 SecurityService 实例
        List<SecurityService> services = provider.orderedStream().collect(Collectors.toList());

        if (services.isEmpty()) {
            LOGGER.debug("No SecurityService bean found, will use default local implementation.");
            return null;
        }

        SecurityService fallback = null;
        for (SecurityService service : services) {
            if (!(service instanceof SecurityServiceLocalImpl)) {
                // 找到了一个远程实现，这是最佳选择
                LOGGER.info("Using remote SecurityService implementation: {}", service.getClass().getName());
                return service;
            }
            // 这是一个本地实现，先存起来作为备选
            fallback = service;
        }

        // 如果循环结束，说明只找到了本地实现
        LOGGER.info("Using local SecurityService implementation: {}", fallback.getClass().getName());
        return fallback;
    }

}

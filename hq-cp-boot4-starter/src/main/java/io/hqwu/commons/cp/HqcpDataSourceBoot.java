package io.hqwu.commons.cp;

import io.hqwu.commons.security.SecurityService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

/**
 * Hqcp 数据源的 Spring Boot 4 自动配置实现类。
 *
 * <p>与 hq-cp-boot-starter 里的同名类逻辑一致，区别只在 {@link DataSourceProperties}
 * 在 Spring Boot 4 中迁到了 {@code org.springframework.boot.jdbc.autoconfigure}（artifact {@code spring-boot-jdbc}）。
 * 主要功能包括：
 * <ul>
 *   <li>支持通过 {@code spring.datasource.hqcp} 前缀绑定自定义配置属性。</li>
 *   <li>在未定义特定属性时，自动回退到 {@link DataSourceProperties} 提供的标准 Spring 数据源配置。</li>
 *   <li>集成 {@link SecurityService} 以支持数据库凭据的安全处理（如密码解密）。</li>
 *   <li>支持注入 {@link PasswordDecryptor}，注入后取代内置 AES 口令解密。</li>
 *   <li>管理数据源的生命周期，负责容器启动时的初始化与关闭时的资源释放。</li>
 * </ul>
 *
 * @author taige (Wu, Hongqiang)
 * @since 2026-09-12
 */
@ConfigurationProperties(prefix = "spring.datasource.hqcp")
public class HqcpDataSourceBoot extends HqcpDataSource implements InitializingBean, DisposableBean {

    private final DataSourceProperties basicProperties;
    private final SecurityService securityService;
    private final PasswordDecryptor passwordDecryptor;

    public HqcpDataSourceBoot(DataSourceProperties basicProperties) {
        this(basicProperties, null);
    }

    public HqcpDataSourceBoot(DataSourceProperties basicProperties, SecurityService securityService) {
        this(basicProperties, securityService, null);
    }

    public HqcpDataSourceBoot(DataSourceProperties basicProperties, SecurityService securityService,
                              PasswordDecryptor passwordDecryptor) {
        this.basicProperties = basicProperties;
        this.securityService = securityService;
        this.passwordDecryptor = passwordDecryptor;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // 注入 SecurityService
        if (this.securityService != null) {
            super.setSecurityService(this.securityService);
        }
        // 注入 PasswordDecryptor，须在下面首次 getPassword()（触发惰性解密）之前
        if (this.passwordDecryptor != null) {
            super.setPasswordDecryptor(this.passwordDecryptor);
        }

        // if not found prefix 'spring.datasource.hqcp' jdbc properties,
        // 'spring.datasource' prefix jdbc properties will be used.
        if (super.getUsername() == null) {
            super.setUsername(basicProperties.determineUsername());
        }
        if (super.getPassword() == null) {
            super.setPassword(basicProperties.determinePassword());
        }
        if (super.getDriverClassName() == null) {
            super.setDriverClassName(basicProperties.getDriverClassName());
        }
        if (super.getUrl() == null) {
            super.setUrl(basicProperties.determineUrl());
        }
    }

    @Override
    public void destroy() {
        // 关闭数据源，资源释放
        super.shutdown();
    }

}

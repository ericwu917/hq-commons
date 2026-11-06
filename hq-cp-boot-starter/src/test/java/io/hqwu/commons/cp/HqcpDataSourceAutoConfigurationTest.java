package io.hqwu.commons.cp;

import io.hqwu.commons.cp.autoconfigure.HqcpDataSourceAutoConfiguration;
import io.hqwu.commons.cp.util.SqlMasker;
import io.hqwu.commons.security.SecurityService;
import io.hqwu.commons.security.SecurityServiceLocalImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HqcpDataSourceAutoConfiguration 单元测试
 */
class HqcpDataSourceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HqcpDataSourceAutoConfiguration.class
            ));

    @Test
    void testAutoConfigurationLoaded() {
        this.contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.username=root",
                        "spring.datasource.password=password"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(HqcpDataSourceBoot.class);
                });
    }

    @Test
    void testAutoConfigurationWithHqcpProperties() {
        this.contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/basic",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.username=basic_user",
                        "spring.datasource.password=basic_password",
                        "spring.datasource.hqcp.url=jdbc:mysql://localhost:3306/hqcp",
                        "spring.datasource.hqcp.username=hqcp_user",
                        "spring.datasource.hqcp.password=hqcp_password",
                        "spring.datasource.hqcp.min-connections=5",
                        "spring.datasource.hqcp.max-connections=20"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    var dataSource = context.getBean(HqcpDataSourceBoot.class);
                    assertThat(dataSource).isNotNull();
                    assertThat(dataSource.getUsername()).isEqualTo("hqcp_user");
                    assertThat(dataSource.getPassword()).isEqualTo("hqcp_password");
                    assertThat(dataSource.getUrl()).isEqualTo("jdbc:mysql://localhost:3306/hqcp");
                    assertThat(dataSource.getMinConnections()).isEqualTo(5);
                    assertThat(dataSource.getMaxConnections()).isEqualTo(20);
                });
    }

    @Test
    void testAutoConfigurationBeforeDataSourceAutoConfiguration() {
        this.contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password="
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    // HqcpDataSourceAutoConfiguration 应该先于 DataSourceAutoConfiguration 执行
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(HqcpDataSourceBoot.class);
                });
    }

    @Test
    void testConditionalOnMissingBean() {
        this.contextRunner
                .withUserConfiguration(CustomDataSourceConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    // 应该使用用户自定义的 DataSource，而不是自动配置的
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(CustomDataSource.class);
                });
    }

    @Test
    void testWithOnlyBasicProperties() {
        this.contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/basic",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.username=basic_user",
                        "spring.datasource.password=basic_pass"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    var dataSource = context.getBean(HqcpDataSourceBoot.class);
                    // 验证使用了基础属性
                    assertThat(dataSource.getUsername()).isEqualTo("basic_user");
                    assertThat(dataSource.getPassword()).isEqualTo("basic_pass");
                    assertThat(dataSource.getUrl()).isEqualTo("jdbc:mysql://localhost:3306/basic");
                });
    }

    @Test
    void testMaskPropertiesBound() {
        this.contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.hqcp.sensitive-fields=password,bank_account:0:4",
                        "spring.datasource.hqcp.mask-keep-head=1",
                        "spring.datasource.hqcp.mask-keep-tail=2",
                        "spring.datasource.hqcp.sensitive-fields-exact-match=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var dataSource = context.getBean(HqcpDataSourceBoot.class);
                    assertThat(dataSource.getSensitiveFields()).containsExactlyInAnyOrder("password", "bank_account:0:4");
                    assertThat(dataSource.getMaskKeepHead()).isEqualTo(1);
                    assertThat(dataSource.getMaskKeepTail()).isEqualTo(2);
                    assertThat(dataSource.isSensitiveFieldsExactMatch()).isTrue();
                    // 按绑定结果构造的 SqlMasker：按列后缀、全局首尾保留、精确匹配均生效
                    SqlMasker masker = new SqlMasker(dataSource.getMaskPattern(), dataSource.getSensitiveFields(),
                            dataSource.getMaskKeepHead(), dataSource.getMaskKeepTail(), dataSource.isSensitiveFieldsExactMatch());
                    assertThat(masker.maskSensitiveFields(
                            "UPDATE t SET bank_account = '6222021234567890', password = 'secret123', password_hint = 'abcdef'"))
                            .isEqualTo("UPDATE t SET bank_account = '****7890', password = 's****23', password_hint = 'abcdef'");
                });
    }

    @Test
    void testIllegalSensitiveFieldSuffixFailsStartup() {
        this.contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.hqcp.sensitive-fields=password,pin:a:0"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void testConfigurationIsConditionalOnClass() {
        this.contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
                )
                .run(context -> {
                    // 验证配置类被正确加载
                    assertThat(context).hasBean("dataSource");
                    assertThat(context.getBean("dataSource")).isInstanceOf(HqcpDataSourceBoot.class);
                });
    }

    @Test
    void testSelectPreferRemote_WithRemoteSecurityService() {
        this.contextRunner
                .withUserConfiguration(RemoteSecurityServiceConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.username=testuser",
                        "spring.datasource.password=testpass"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).hasBean("remoteSecurityService");
                    // 验证 DataSource 被正确创建
                    var dataSource = context.getBean(HqcpDataSourceBoot.class);
                    assertThat(dataSource).isNotNull();
                });
    }

    @Test
    void testSelectPreferRemote_WithLocalSecurityService() {
        this.contextRunner
                .withUserConfiguration(LocalSecurityServiceConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.username=testuser",
                        "spring.datasource.password=testpass"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).hasBean("localSecurityService");
                    // 验证 DataSource 被正确创建
                    var dataSource = context.getBean(HqcpDataSourceBoot.class);
                    assertThat(dataSource).isNotNull();
                });
    }

    @Test
    void testSelectPreferRemote_WithMixedSecurityServices() {
        this.contextRunner
                .withUserConfiguration(MixedSecurityServiceConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.username=testuser",
                        "spring.datasource.password=testpass"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).hasBean("remoteSecurityService");
                    assertThat(context).hasBean("localSecurityService");
                    // 验证 DataSource 被正确创建，应优先使用远程实现
                    var dataSource = context.getBean(HqcpDataSourceBoot.class);
                    assertThat(dataSource).isNotNull();
                });
    }

    @Test
    void testPasswordDecryptorBeanUsed() {
        this.contextRunner
                .withUserConfiguration(PasswordDecryptorConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.hqcp.password=vault:v1:cipher"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var dataSource = context.getBean(HqcpDataSourceBoot.class);
                    assertThat(dataSource.getPassword()).isEqualTo("plain-of-vault:v1:cipher");
                });
    }

    @Test
    void testPasswordDecryptorFailureFailsStartup() {
        this.contextRunner
                .withUserConfiguration(FailingPasswordDecryptorConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:mysql://localhost:3306/test",
                        "spring.datasource.hqcp.password=vault:v1:cipher"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class).hasMessage("decrypt failed");
                });
    }

    @Test
    void testMultiplePasswordDecryptorsFailStartup() {
        this.contextRunner
                .withUserConfiguration(PasswordDecryptorConfiguration.class, FailingPasswordDecryptorConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:mysql://localhost:3306/test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class);
                });
    }

    @Configuration
    static class PasswordDecryptorConfiguration {
        @Bean
        public PasswordDecryptor passwordDecryptor() {
            return p -> "plain-of-" + p;
        }
    }

    @Configuration
    static class FailingPasswordDecryptorConfiguration {
        @Bean
        public PasswordDecryptor failingPasswordDecryptor() {
            return p -> {
                throw new IllegalStateException("decrypt failed");
            };
        }
    }

    /**
     * 自定义 DataSource 配置（用于测试 @ConditionalOnMissingBean）
     */
    @Configuration
    static class CustomDataSourceConfiguration {
        @Bean
        public DataSource dataSource() {
            return new CustomDataSource();
        }
    }

    /**
     * 远程 SecurityService 实现配置
     */
    @Configuration
    static class RemoteSecurityServiceConfiguration {
        @Bean
        public SecurityService remoteSecurityService() {
            return new RemoteSecurityService();
        }
    }

    /**
     * 本地 SecurityService 实现配置
     */
    @Configuration
    static class LocalSecurityServiceConfiguration {
        @Bean
        public SecurityService localSecurityService() {
            return new SecurityServiceLocalImpl();
        }
    }

    /**
     * 混合 SecurityService 实现配置（同时包含远程和本地）
     */
    @Configuration
    static class MixedSecurityServiceConfiguration {
        @Bean
        public SecurityService remoteSecurityService() {
            return new RemoteSecurityService();
        }

        @Bean
        public SecurityService localSecurityService() {
            return new SecurityServiceLocalImpl();
        }
    }

    /**
     * 自定义 DataSource 实现（用于测试）
     */
    static class CustomDataSource extends HqcpDataSource {
        // 标记类，用于类型判断
    }

    /**
     * 远程 SecurityService 实现（用于测试）
     */
    static class RemoteSecurityService implements SecurityService {
        @Override
        public String generateKey(String keyType, int keySize) throws GeneralSecurityException {
            return null;
        }

        @Override
        public B64KeyPair generateKeyPair(String keyType, int keySize) throws GeneralSecurityException {
            return null;
        }

        @Override
        public String encryptByAES(byte[] data, String keyAlias, String alg) throws GeneralSecurityException, IllegalArgumentException {
            return null;
        }

        @Override
        public byte[] decryptByAES(byte[] cipher, String keyAlias, String alg) throws GeneralSecurityException, IllegalArgumentException {
            return new byte[0];
        }

        @Override
        public byte[] decryptByAES(String cipher, String keyAlias, String alg) throws GeneralSecurityException, IllegalArgumentException {
            return new byte[0];
        }

        @Override
        public String encryptByPublicKey(byte[] data, String publicKey) throws GeneralSecurityException, IllegalArgumentException {
            return null;
        }

        @Override
        public byte[] decryptByPrivateKey(byte[] cipher, String privateKeyAlias) throws GeneralSecurityException, IllegalArgumentException {
            return new byte[0];
        }

        @Override
        public byte[] decryptByPrivateKey(String cipher, String privateKeyAlias) throws GeneralSecurityException, IllegalArgumentException {
            return new byte[0];
        }

        @Override
        public String sign(byte[] data, String privateKeyAlias, String signType) throws Exception {
            return null;
        }

        @Override
        public boolean verify(byte[] data, String publicKey, String sign, String signType) throws Exception {
            return false;
        }

        @Override
        public String encryptKey(String key) throws GeneralSecurityException {
            return null;
        }

        @Override
        public String hmac(byte[] data, String keyAlias, String alg) throws GeneralSecurityException {
            return null;
        }
    }
}

package io.hqwu.commons.cp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HqcpDataSourceBoot 单元测试
 */
class HqcpDataSourceBootTest {

    @Test
    void testAfterPropertiesSet_WithHqcpProperties() throws Exception {
        // 准备基础属性
        var basicProperties = new DataSourceProperties();
        basicProperties.setUsername("basic_user");
        basicProperties.setPassword("basic_password");
        basicProperties.setUrl("jdbc:mysql://basic:3306/db");
        basicProperties.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 创建 HqcpDataSourceBoot 实例并设置 hqcp 特定属性
        var dataSource = new HqcpDataSourceBoot(basicProperties);
        dataSource.setUsername("hqcp_user");
        dataSource.setPassword("hqcp_password");
        dataSource.setUrl("jdbc:mysql://hqcp:3306/db");
        dataSource.setDriverClassName("org.h2.Driver");

        // 执行初始化
        dataSource.afterPropertiesSet();

        // 验证使用了 hqcp 特定属性，而不是基础属性
        assertThat(dataSource.getUsername()).isEqualTo("hqcp_user");
        assertThat(dataSource.getPassword()).isEqualTo("hqcp_password");
        assertThat(dataSource.getUrl()).isEqualTo("jdbc:mysql://hqcp:3306/db");
        assertThat(dataSource.getDriverClassName()).isEqualTo("org.h2.Driver");
    }

    @Test
    void testAfterPropertiesSet_WithoutHqcpProperties() throws Exception {
        // 准备基础属性
        var basicProperties = new DataSourceProperties();
        basicProperties.setUsername("basic_user");
        basicProperties.setPassword("basic_password");
        basicProperties.setUrl("jdbc:mysql://basic:3306/db");
        basicProperties.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 创建 HqcpDataSourceBoot 实例，不设置 hqcp 特定属性
        var dataSource = new HqcpDataSourceBoot(basicProperties);

        // 执行初始化
        dataSource.afterPropertiesSet();

        // 验证回退到基础属性
        assertThat(dataSource.getUsername()).isEqualTo("basic_user");
        assertThat(dataSource.getPassword()).isEqualTo("basic_password");
        assertThat(dataSource.getUrl()).isEqualTo("jdbc:mysql://basic:3306/db");
        assertThat(dataSource.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void testAfterPropertiesSet_PartialHqcpProperties() throws Exception {
        // 准备基础属性
        var basicProperties = new DataSourceProperties();
        basicProperties.setUsername("basic_user");
        basicProperties.setPassword("basic_password");
        basicProperties.setUrl("jdbc:mysql://basic:3306/db");
        basicProperties.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 创建 HqcpDataSourceBoot 实例，只设置部分 hqcp 属性
        var dataSource = new HqcpDataSourceBoot(basicProperties);
        dataSource.setUsername("hqcp_user");
        dataSource.setUrl("jdbc:mysql://hqcp:3306/db");

        // 执行初始化
        dataSource.afterPropertiesSet();

        // 验证混合使用 hqcp 属性和基础属性
        assertThat(dataSource.getUsername()).isEqualTo("hqcp_user");
        assertThat(dataSource.getPassword()).isEqualTo("basic_password");
        assertThat(dataSource.getUrl()).isEqualTo("jdbc:mysql://hqcp:3306/db");
        assertThat(dataSource.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void testAfterPropertiesSet_NullBasicProperties() throws Exception {
        // 准备基础属性但不设置值
        var basicProperties = new DataSourceProperties();

        // 创建 HqcpDataSourceBoot 实例并设置 hqcp 特定属性
        var dataSource = new HqcpDataSourceBoot(basicProperties);
        dataSource.setUsername("hqcp_user");
        dataSource.setPassword("hqcp_password");
        dataSource.setUrl("jdbc:mysql://hqcp:3306/db");
        dataSource.setDriverClassName("org.h2.Driver");

        // 执行初始化
        dataSource.afterPropertiesSet();

        // 验证使用了 hqcp 特定属性
        assertThat(dataSource.getUsername()).isEqualTo("hqcp_user");
        assertThat(dataSource.getPassword()).isEqualTo("hqcp_password");
        assertThat(dataSource.getUrl()).isEqualTo("jdbc:mysql://hqcp:3306/db");
        assertThat(dataSource.getDriverClassName()).isEqualTo("org.h2.Driver");
    }

    @Test
    void testDestroy() throws Exception {
        // 准备基础属性
        var basicProperties = new DataSourceProperties();
        basicProperties.setUsername("test_user");
        basicProperties.setPassword("test_password");
        basicProperties.setUrl("jdbc:h2:mem:testdb");
        basicProperties.setDriverClassName("org.h2.Driver");

        // 创建 HqcpDataSourceBoot 实例
        var dataSource = new HqcpDataSourceBoot(basicProperties);
        dataSource.afterPropertiesSet();

        // 调用 destroy 方法
        assertDoesNotThrow(dataSource::destroy);
    }

    @Test
    void testConstructor() {
        // 准备基础属性
        var basicProperties = new DataSourceProperties();
        basicProperties.setUsername("test_user");

        // 创建实例
        var dataSource = new HqcpDataSourceBoot(basicProperties);

        // 验证实例不为空
        assertNotNull(dataSource);
    }

    @Test
    void testAfterPropertiesSet_WithPasswordDecryptor() throws Exception {
        var basicProperties = new DataSourceProperties();
        basicProperties.setPassword("vault:v1:basic");
        basicProperties.setUrl("jdbc:mysql://basic:3306/db");

        var dataSource = new HqcpDataSourceBoot(basicProperties, null, p -> "plain-of-" + p);
        dataSource.afterPropertiesSet();

        // 回退到基础属性的口令同样交给解密器
        assertThat(dataSource.getPassword()).isEqualTo("plain-of-vault:v1:basic");
    }
}

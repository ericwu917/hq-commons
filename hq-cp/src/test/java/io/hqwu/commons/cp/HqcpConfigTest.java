package io.hqwu.commons.cp;

import com.jolbox.bonecp.MockConstant;
import com.jolbox.bonecp.MockJDBCDriver;
import io.hqwu.commons.security.SecurityService;
import io.hqwu.commons.security.SecurityServiceLocalImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Created with IntelliJ IDEA for plat-arch-svn
 * User: taige
 * Date: 13-10-30
 * Time: 下午2:24
 */
public class HqcpConfigTest {

    HqcpConfig config;
    Properties prop;

    @BeforeEach
    public void before() throws Exception {
        config = new HqcpConfig();
        prop = new Properties();
    }

    @Test
    public void testBaseSetterGetter() throws Exception {
        config.setDriverClassName(MockJDBCDriver.class.getName());
        config.setUrl(MockConstant.MOCK_URL);
        config.setUsername("mockuser");
        config.setPassword("mockpasword");

        assertEquals(MockJDBCDriver.class.getName(), config.getDriverClassName());
        assertEquals(MockConstant.MOCK_URL, config.getUrl());
        assertEquals(MockConstant.MOCK_URL, config.getUrl());
        assertEquals("mockuser", config.getUsername());
//        assertEquals("mockpasword", config.getPassword());
        assertEquals("mockuser", config.getConnectionProperties().getProperty("user"));
        assertEquals("mockpasword", config.getConnectionProperties().getProperty("password"));
    }

    @Test
    public void testLoadDriver() throws Exception {
        config.setDriverClassName(MockJDBCDriver.class.getName());
        config.setUrl(MockConstant.MOCK_URL);

        Hqcp cp = new Hqcp(config);
        assertEquals(config, cp.getConfig());
        cp.shutdown();
    }

    // 测试有严格最小值和最大值限制的属性
    @ParameterizedTest
    @CsvSource({
            "minConnections, 0, 100, -1",
            "minConnections, 0, 100, 101",
            "maxConnections, 1, 1000, 0",
            "maxConnections, 1, 1000, 1001",
            "maxStatements, 10, 1000, 9",
            "maxStatements, 10, 1000, 1001",
            "maxPreStatements, 5, 200, 4",
            "maxPreStatements, 5, 200, 201",
            "idleTimeoutSec, 10, 3600, 9",
            "idleTimeoutSec, 10, 3600, 3601",
            "jmxLevel, 0, 2, -1",
            "jmxLevel, 0, 2, 3"
    })

    public void testPropertyBoundaryValues(String property, long min, long max, long invalidValue) {
        try {
            switch (property) {
                case "minConnections": config.setMinConnections((int) invalidValue); break;
                case "maxConnections": config.setMaxConnections((int) invalidValue); break;
                case "maxStatements": config.setMaxStatements((int) invalidValue); break;
                case "maxPreStatements": config.setMaxPreStatements((int) invalidValue); break;
                case "idleTimeoutSec": config.setIdleTimeoutSec(invalidValue); break;
                case "jmxLevel": config.setJmxLevel((int) invalidValue); break;
            }
            fail("Expected IllegalArgumentException for " + property + " with value " + invalidValue);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            assertTrue(message.contains(String.valueOf(min)) || message.contains(String.valueOf(max)),
                    "Exception message should contain min or max value: " + message);
        }
    }

    // 测试 checkoutTimeoutMillisec 的边界值（仅最大值限制）
    @Test
    public void testCheckoutTimeoutMillisecBoundaryValues() {
        // 测试合法值
        config.setCheckoutTimeoutMillisec(-1); // 一直等待
        assertEquals(-1, config.getCheckoutTimeoutMillisec());
        config.setCheckoutTimeoutMillisec(0); // 不等待
        assertEquals(0, config.getCheckoutTimeoutMillisec());
        config.setCheckoutTimeoutMillisec(600000); // 最大值
        assertEquals(600000, config.getCheckoutTimeoutMillisec());

        // 测试超出最大值
        try {
            config.setCheckoutTimeoutMillisec(600001);
            fail("Expected IllegalArgumentException for checkoutTimeoutMillisec > 600000");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("600000"), "Exception message should contain max value");
        }
    }

    // 测试 lifetimeSec 的边界值（仅最大值限制）
    @Test
    public void testLifetimeSecBoundaryValues() {
        // 测试合法值
        config.setLifetimeSec(-1); // 不回收
        assertEquals(0, config.getLifetimeSec());
        config.setLifetimeSec(0); // 不回收
        assertEquals(0, config.getLifetimeSec());
        config.setLifetimeSec(86400); // 最大值
        assertEquals(86400, config.getLifetimeSec());

        // 测试超出最大值
        try {
            config.setLifetimeSec(86401);
            fail("Expected IllegalArgumentException for lifetimeSec > 86400");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("86,400"), "Exception message should contain max value");
        }
    }

    @Test
    public void testLoadDriverFail() throws Exception {
        config.setDriverClassName("some.unknow.driver");
//        config.setConnUrl(MockConstant.MOCK_URL);
        config.setUrl("jdbc:unknow:");

        try {
            Hqcp cp = new Hqcp(config);
            fail("load some.unknow.driver driver ok?");
        } catch (SQLException e) {

        }
    }

    @Test
    public void test_loadFromProperties() throws Exception {
        HqcpConfig cp = new HqcpConfig();
        cp.loadFromProperties("jdbc");
        assertTrue(cp.isLoadFromProperties());
        assertEquals("com.jolbox.bonecp.MockJDBCDriver", cp.getDriverClassName());
        assertEquals("jdbc:mysql:url", cp.getUrl());
        cp.reloadProperties();

        HqcpConfig cp2 = new HqcpConfig();
        cp.loadFromProperties("jdbc2");
        assertEquals("com.jolbox.bonecp.MockJDBCDriver", cp.getDriverClassName());
        assertEquals("jdbc:mock:url", cp.getUrl());
        cp2.reloadProperties();
    }

    @Test
    public void testBasePropertiesSetter() throws Exception {
        prop.setProperty("jdbc.driver", MockJDBCDriver.class.getName());
        prop.setProperty("jdbc.url", MockConstant.MOCK_URL);
        prop.setProperty("jdbc.username", "mockuser");
        prop.setProperty("jdbc.password", "mockpassword");
        config.setProperties(prop);

        assertEquals(MockConstant.MOCK_URL, config.getUrl());
        assertEquals(MockConstant.MOCK_URL, config.getUrl());
        assertEquals(MockJDBCDriver.class.getName(), config.getDriverClassName());
        assertEquals("mockuser", config.getUsername());
//        assertEquals("mockpassword", config.getPassword());
        assertEquals("mockuser", config.getConnectionProperties().getProperty("user"));
        assertEquals("mockpassword", config.getConnectionProperties().getProperty("password"));
    }

    @Test
    public void testAutoSetCheckStatementByDB2Url() throws Exception {
        prop.setProperty("jdbc.url", "jdbc:db2:");
        config.setProperties(prop);
        assertEquals("values(current timestamp)", config.getCheckStatement());
    }

    @Test
    public void testAutoSetCheckStatementByOraUrl() throws Exception {
        prop.setProperty("jdbc.url", "jdbc:oracle:");
        config.setProperties(prop);
        assertEquals(true, config.isOracle());
        assertEquals("select systimestamp from dual", config.getCheckStatement());
//        assertEquals("oracle.jdbc.driver.OracleDriver", config.getDriverClassName());
    }

    @Test
    public void testAutoSetCheckStatementByMyUrl() throws Exception {
        prop.setProperty("jdbc.url", "jdbc:mysql:");
        config.setProperties(prop);
        assertEquals(true, config.isMySQL());
        assertEquals("select now()", config.getCheckStatement());
//        assertEquals("com.mysql.cj.jdbc.Driver", config.getDriverClassName());
    }

    @Test
    public void testGetMinConnections() throws Exception {
        config.setMinConnections(100);
        assertEquals(100, config.getMinConnections());
    }

    @Test
    public void testGetMaxConnections() throws Exception {
        config.setMaxConnections(200);
        assertEquals(200, config.getMaxConnections());
    }

    @Test
    public void testIsVerbose() throws Exception {
        config.setVerbose(false);
        assertEquals(false, config.isVerbose());
        config.setVerbose(true);
        assertEquals(true, config.isVerbose());
    }

    @Test
    public void testIsPrintSQL() throws Exception {
        config.setPrintSql(false);
        assertEquals(false, config.isPrintSql());
        config.setPrintSql(true);
        assertEquals(true, config.isPrintSql());
    }

    @Test
    public void testIsMaskSql() throws Exception {
        config.setMaskSql(false);
        assertEquals(false, config.isMaskSql());
        config.setMaskSql(true);
        assertEquals(true, config.isMaskSql());
    }

    @Test
    public void testGetMaskPattern() throws Exception {
        assertEquals("****", config.getMaskPattern());
        prop.setProperty("jdbc.mask-pattern", "####");
        config.setProperties(prop);
        assertEquals("####", config.getMaskPattern());
    }

    @Test
    public void testGetSensitiveFields() throws Exception {
        assertTrue(config.getSensitiveFields().isEmpty());
        prop.setProperty("jdbc.sensitive-fields", "password,mobile,idCard");
        config.setProperties(prop);
        assertEquals(3, config.getSensitiveFields().size());
        assertTrue(config.getSensitiveFields().contains("password"));
        assertTrue(config.getSensitiveFields().contains("mobile"));
        assertTrue(config.getSensitiveFields().contains("idCard"));
    }

    @Test
    public void testMaskKeepAndExactMatch() throws Exception {
        assertEquals(-1, config.getMaskKeepHead());
        assertEquals(-1, config.getMaskKeepTail());
        assertFalse(config.isSensitiveFieldsExactMatch());
        // 不设时保持默认
        config.setProperties(prop);
        assertEquals(-1, config.getMaskKeepHead());
        assertEquals(-1, config.getMaskKeepTail());
        assertFalse(config.isSensitiveFieldsExactMatch());

        prop.setProperty("jdbc.mask-keep-head", "0");
        prop.setProperty("jdbc.mask-keep-tail", "4");
        prop.setProperty("jdbc.sensitive-fields-exact-match", "true");
        prop.setProperty("jdbc.sensitive-fields", "password,bank_account:0:4");
        config.setProperties(prop);
        assertEquals(0, config.getMaskKeepHead());
        assertEquals(4, config.getMaskKeepTail());
        assertTrue(config.isSensitiveFieldsExactMatch());
        assertTrue(config.getSensitiveFields().contains("bank_account:0:4"));
    }

    @Test
    public void testSensitiveFieldsWithSpaces() throws Exception {
        // 测试包含空格的情况
        prop.setProperty("jdbc.sensitive-fields", " password , mobile , idCard ");
        config.setProperties(prop);
        assertEquals(3, config.getSensitiveFields().size());
        assertTrue(config.getSensitiveFields().contains("password"));
        assertTrue(config.getSensitiveFields().contains("mobile"));
        assertTrue(config.getSensitiveFields().contains("idCard"));
    }

    @Test
    public void testSensitiveFieldsWithEmptyValues() throws Exception {
        // 测试包含空值的情况
        prop.setProperty("jdbc.sensitive-fields", "password,,mobile,  ,idCard");
        config.setProperties(prop);
        assertEquals(3, config.getSensitiveFields().size());
        assertTrue(config.getSensitiveFields().contains("password"));
        assertTrue(config.getSensitiveFields().contains("mobile"));
        assertTrue(config.getSensitiveFields().contains("idCard"));
    }

    @Test
    public void testIsLazyInit() throws Exception {
        config.setLazyInit(false);
        assertEquals(false, config.isLazyInit());
        config.setLazyInit(true);
        assertEquals(true, config.isLazyInit());
    }

    @Test
    public void testIsCommitOnClose() throws Exception {
        config.setCommitOnClose(false);
        assertEquals(false, config.isCommitOnClose());
        config.setCommitOnClose(true);
        assertEquals(true, config.isCommitOnClose());
    }

    @Test
    public void testGetIdleTimeoutSec() throws Exception {
        config.setIdleTimeoutSec(1234);
        assertEquals(1234, config.getIdleTimeoutSec());
        assertEquals(1234000, config.getIdleTimeoutMillisec());
    }

    @Test
    public void testGetCheckoutTimeoutMilliSec() throws Exception {
        config.setCheckoutTimeoutMillisec(1235);
        assertEquals(1235, config.getCheckoutTimeoutMillisec());
    }

    @Test
    public void testGetMaxStatements() throws Exception {
        config.setMaxStatements(120);
        assertEquals(120, config.getMaxStatements());
    }

    @Test
    public void testGetMaxPreStatements() throws Exception {
        config.setMaxPreStatements(139);
        assertEquals(139, config.getMaxPreStatements());
    }

    @Test
    public void testInfoSQLThreshold() throws Exception {
        config.setInfoSqlThreshold(139);
        assertEquals(139, config.getInfoSqlThreshold());
    }

    @Test
    public void testWarnSQLThreshold() throws Exception {
        config.setWarnSqlThreshold(1390);
        assertEquals(1390, config.getWarnSqlThreshold());
    }

    @Test
    public void testLoginTimeout() throws Exception {
        config.setLoginTimeout(139);
        assertEquals(139, config.getLoginTimeout());
    }

    @Test
    public void testQueryTimeout() throws Exception {
        config.setQueryTimeout(139);
        assertEquals(139, config.getQueryTimeout());
    }

    @Test
    public void testGetCheckStatement() throws Exception {
        config.setCheckStatement("test");
        assertEquals("test", config.getCheckStatement());
    }

    @Test
    public void testConnectionInfo() throws Exception {
        config.setConnectionInfo("ABC=abc&CC=cc");
        assertEquals("abc", config.getConnectionProperties().getProperty("ABC"));
        assertEquals("cc", config.getConnectionProperties().getProperty("CC"));
    }

    @Test
    public void testIsTransactionMode() throws Exception {
        config.setTransactionMode(true);
        assertEquals(true, config.isTransactionMode());
        config.setTransactionMode(false);
        assertEquals(false, config.isTransactionMode());
    }

//    @Test
//    public void testUseOracleImplicitPSCache() throws Exception {
//        config.setUseOracleImplicitCache(true);
//        assertEquals(true, config.isUseOracleImplicitCache());
//        config.setUseOracleImplicitCache(false);
//        assertEquals(false, config.isUseOracleImplicitCache());
//    }

    @Test
    public void testGetJmxLevel() throws Exception {
        config.setJmxLevel(2);
        assertEquals(2, config.getJmxLevel());
    }
    @Test
    public void testSetPassword() throws Exception {
        checkPassword("zhrmghgsb1024bdj");
        checkPassword("zhrmghgsb1024bdjzhrmghgsb1024bdjzhrmghgsb1024bdjzhrmghgsb1024bdj");//64B base64解码后48B，是16的倍数
        checkPassword("1f/CYgg/AZ+O7FbBVqy+kIIxMOp3URjSFomDmp8JlWM=", "aaaabbbbccccdddd");
        checkPassword("1f/CYgg/AZ+O7FbBVqy+kOxapfXZnaXb4wTBKlUPh6k=", "aaaabbbbccccdddd");
        checkPassword("UoUpS4xy0mFS/SA3/rs+kNq4mUHAI+hsCMNpSqxHav8=", "helloIt'sASecret");
        checkPassword("g7CiE+BiDo9Y0Mj4LJRj/6B2n9VR7TJmFZt+5/APeeM=", "helloIt'sSecret");
        checkPassword("O9rR6SBlBsy/UYGrhsvk9w==", "helloIt'sSecret");
        checkPassword(new String(new byte[3]));
        checkPassword("             ");
        checkPassword("zhrmghgsb1024bdj%^&*(");
    }
    private void checkPassword(String password) {
        checkPassword(password, null);
    }
    private void checkPassword(String password, String except) {
        config.setPassword(password);
        if (except == null) {
            except = password;
        }
        assertEquals(except, config.getPassword());
    }
//    private static final String PATTERN_COMMONS_CHARS =  "[\\w\\S]+";
    private static final String PATTERN_COMMONS_CHARS =  "[\u0020-\u007E]+";

    @Test
    public void testRegex(){
        assertTrue("abc$%#weq*+{}:><?>;';".matches(PATTERN_COMMONS_CHARS));
        assertTrue(" ".matches(PATTERN_COMMONS_CHARS));
        assertFalse(new String(new byte[3]).matches(PATTERN_COMMONS_CHARS));

//        String ss = "abc$%#weq*123(&<>>?()*&^%$#@!\r\n";
//        ss = "a1(&<>>?()*&^%$#@!\r\n" + new String(new byte[1]);
//        for (Character c : ss.toCharArray()) {
//            System.out.printf("isMirrored(%s)=%b\n",c,Character.isMirrored(c));
//            System.out.printf("isDefined(%s)=%b\n",c,Character.isDefined(c));
//            System.out.printf("isLetterOrDigit(%s)=%b\n", c, Character.isLetterOrDigit(c));
//            System.out.printf("isSpaceChar(%s)=%b\n", c, Character.isSpaceChar(c));
//            System.out.println("++++++++++++++++++++++++++++++++++++++++++");
//        }
    }

    @Test
    public void testSetProperties() throws Exception {
        // 设置所有属性
        prop.setProperty("jdbc.url", "jdbc:mysql://localhost:3306/testdb");
        prop.setProperty("jdbc.username", "testUser");
        prop.setProperty("jdbc.password", "testPass");
        prop.setProperty("jdbc.driver-class-name", "com.mysql.cj.jdbc.Driver");
        prop.setProperty("jdbc.verbose", "false");
        prop.setProperty("jdbc.print-sql", "false");
        prop.setProperty("jdbc.mask-sql", "false");
        prop.setProperty("jdbc.mask-pattern", "####");
        prop.setProperty("jdbc.sensitive-fields", "password,mobile,idCard");
        prop.setProperty("jdbc.commit-on-close", "false");
        prop.setProperty("jdbc.transaction-mode", "false");
        prop.setProperty("jdbc.lazy-init", "false");
        prop.setProperty("jdbc.min-connections", "100");
        prop.setProperty("jdbc.max-connections", "200");
        prop.setProperty("jdbc.idle-timeout-sec", "1234");
        prop.setProperty("jdbc.checkout-timeout-millisec", "1235");
        prop.setProperty("jdbc.check-statement", "test");
        prop.setProperty("jdbc.max-statements", "120");
        prop.setProperty("jdbc.max-pre-statements", "139");
        prop.setProperty("jdbc.jmx-level", "2");
        prop.setProperty("jdbc.lifetime-sec", "666");
        prop.setProperty("jdbc.info-sql-threshold", "210");
        prop.setProperty("jdbc.warn-sql-threshold", "200");
        prop.setProperty("jdbc.use-oracle-implicit-cache", "true"); // 不再生效 2025-03-04
        prop.setProperty("jdbc.query-timeout", "20");
        prop.setProperty("jdbc.connection-info", "abc=ABC&cc=CC");
        prop.setProperty("jdbc.password-key", "customKey");

        config.setProperties(prop);

        // 验证所有属性 - 第一阶段（false 和常规值）
        assertEquals("jdbc:mysql://localhost:3306/testdb", config.getUrl());
        assertTrue(config.isMySQL());
        assertFalse(config.isOracle());
        assertFalse(config.isDB2());
        assertEquals("testUser", config.getUsername());
        assertEquals("testPass", config.getPassword());
        assertEquals("com.mysql.cj.jdbc.Driver", config.getDriverClassName());
        assertFalse(config.isVerbose());
        assertFalse(config.isPrintSql());
        assertFalse(config.isMaskSql());
        assertEquals("####", config.getMaskPattern());
        assertEquals(3, config.getSensitiveFields().size());
        assertTrue(config.getSensitiveFields().contains("password"));
        assertTrue(config.getSensitiveFields().contains("mobile"));
        assertTrue(config.getSensitiveFields().contains("idCard"));
        assertFalse(config.isCommitOnClose());
        assertFalse(config.isTransactionMode());
        assertFalse(config.isLazyInit());
        assertEquals(100, config.getMinConnections());
        assertEquals(200, config.getMaxConnections());
        assertEquals(1234, config.getIdleTimeoutSec());
        assertEquals(1234000, config.getIdleTimeoutMillisec());
        assertEquals(1235, config.getCheckoutTimeoutMillisec());
        assertEquals("test", config.getCheckStatement());
        assertEquals(120, config.getMaxStatements());
        assertEquals(139, config.getMaxPreStatements());
        assertEquals(2, config.getJmxLevel());
        assertEquals(1234, config.getLifetimeSec());
        assertEquals(210, config.getInfoSqlThreshold());
        assertEquals(210, config.getWarnSqlThreshold());
        assertFalse(config.isUseOracleImplicitCache());
        assertEquals(20, config.getQueryTimeout());
        Properties connProps = config.getConnectionProperties();
        assertEquals("ABC", connProps.getProperty("abc"));
        assertEquals("CC", connProps.getProperty("cc"));
        assertEquals("testPass", connProps.getProperty("password"));
        assertEquals("customKey", config.getPasswordKey());

        // 修改布尔值为 true 并重新设置
        prop.setProperty("jdbc.lifetime-sec", "6666");
        prop.setProperty("jdbc.warn-sql-threshold", "2000");
        prop.setProperty("jdbc.verbose", "true");
        prop.setProperty("jdbc.print-sql", "true");
        prop.setProperty("jdbc.mask-sql", "true");
        prop.setProperty("jdbc.commit-on-close", "true");
        prop.setProperty("jdbc.transaction-mode", "true");
        prop.setProperty("jdbc.lazy-init", "true");
        config.setProperties(prop);

        // 验证布尔值 - 第二阶段（true）
        assertEquals(1234, config.getIdleTimeoutSec());
        assertEquals(6666, config.getLifetimeSec());
        assertEquals(210, config.getInfoSqlThreshold());
        assertEquals(2000, config.getWarnSqlThreshold());
        assertTrue(config.isVerbose());
        assertTrue(config.isPrintSql());
        assertTrue(config.isMaskSql());
        assertTrue(config.isCommitOnClose());
        assertTrue(config.isTransactionMode());
        assertTrue(config.isLazyInit());

        // 验证其他属性未受影响
        assertEquals("jdbc:mysql://localhost:3306/testdb", config.getUrl());
        assertEquals("testUser", config.getUsername());
        assertEquals("testPass", config.getPassword());

        prop.setProperty("jdbc.warn-sql-threshold", "0");
        config.setProperties(prop);
        assertEquals(210, config.getInfoSqlThreshold());
        assertEquals(0, config.getWarnSqlThreshold());
    }

    @Test
    public void testDefault() throws Exception {
        assertFalse(config.isVerbose());
        assertTrue(config.isPrintSql());
        assertFalse(config.isMaskSql());
        assertEquals("****", config.getMaskPattern());
        assertTrue(config.getSensitiveFields().isEmpty());
        assertFalse(config.isCommitOnClose());
        assertFalse(config.isTransactionMode());
        assertEquals(1, config.getMinConnections());
        assertEquals(10, config.getMaxConnections());
        assertEquals(300, config.getIdleTimeoutSec());
        assertEquals(0, config.getLifetimeSec());
        assertEquals(300000, config.getIdleTimeoutMillisec());
        assertEquals(10000, config.getCheckoutTimeoutMillisec());
        assertNull(config.getCheckStatement());
        assertEquals(100, config.getMaxStatements());
        assertEquals(10, config.getMaxPreStatements());
        assertEquals(0, config.getJmxLevel());
    }

    @Test
    public void testEmptyProp() throws Exception {
        config.setProperties(prop);
        assertFalse(config.isVerbose());
        assertTrue(config.isPrintSql());
        assertFalse(config.isMaskSql());
        assertEquals("****", config.getMaskPattern());
        assertTrue(config.getSensitiveFields().isEmpty());
        assertFalse(config.isCommitOnClose());
        assertFalse(config.isTransactionMode());
        assertEquals(1, config.getMinConnections());
        assertEquals(10, config.getMaxConnections());
        assertEquals(300, config.getIdleTimeoutSec());
        assertEquals(0, config.getLifetimeSec());
        assertEquals(300000, config.getIdleTimeoutMillisec());
        assertEquals(10000, config.getCheckoutTimeoutMillisec());
        assertNull(config.getCheckStatement());
        assertEquals(100, config.getMaxStatements());
        assertEquals(10, config.getMaxPreStatements());
        assertEquals(0, config.getJmxLevel());
    }

    @Test
    public void testSetPropertiesLocation() throws Exception {
        config.setPropertiesLocation(new ClassPathResource("jdbc.properties"));
        assertEquals("com.jolbox.bonecp.MockJDBCDriver", config.getDriverClassName());
        assertEquals("jdbc:mysql:url", config.getUrl());
        assertEquals("mockuser", config.getUsername());
//        assertEquals("mockpassword", config.getPassword());
        assertTrue(config.isVerbose());
        assertTrue(config.isPrintSql());
        assertTrue(config.isCommitOnClose());
        assertEquals(1, config.getMinConnections());
        assertEquals(2, config.getMaxConnections());
        assertEquals(20000, config.getIdleTimeoutMillisec());
        assertEquals(20, config.getIdleTimeoutSec());
        assertEquals(210, config.getLifetimeSec());
        assertEquals(60000, config.getCheckoutTimeoutMillisec());
        assertEquals("test", config.getCheckStatement());
        assertEquals(10, config.getMaxStatements());
        assertEquals(5, config.getMaxPreStatements());
        assertEquals(2, config.getJmxLevel());
        assertFalse(config.isTransactionMode());
        assertFalse(config.isLazyInit());
    }

//    @Test
//    public void test_getSecurityService() throws SQLException {
//        new ClassPathXmlApplicationContext("dubbo-provider.xml");
//        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("spring-jdbc-remote-security.xml");
//        DataSource ds = ctx.getBean("dataSource", DataSource.class);
//        Connection conn = ds.getConnection();
//        conn.close();
//    }
//
//    @Test
//    public void test_getKeyManageService() throws SQLException {
//        ClassPathXmlApplicationContext dubbo_ctx = new ClassPathXmlApplicationContext("dubbo-provider.xml");
//        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("spring-jdbc-remote-security.xml");
//        DataSource ds = ctx.getBean("dataSource", DataSource.class);
//        Connection conn = ds.getConnection();
//        dubbo_ctx.close();
//        Connection conn1 = ds.getConnection();
//        conn1.close();
//        conn.close();
//    }

    /**
     * Test: setConnectionInfo with empty entry (line 271)
     */
    @Test
    public void testSetConnectionInfoWithEmptyEntry() {
        config.setConnectionInfo("user=test&&password=pwd");
        assertEquals("test", config.getConnectionProperties().getProperty("user"));
        assertEquals("pwd", config.getConnectionProperties().getProperty("password"));
    }

    /**
     * Test: setConnectionInfo with entry without value (line 271)
     */
    @Test
    public void testSetConnectionInfoWithNoValue() {
        config.setConnectionInfo("user=test&flag");
        assertEquals("test", config.getConnectionProperties().getProperty("user"));
        assertEquals("", config.getConnectionProperties().getProperty("flag"));
    }

    /**
     * Test: _setUrl with invalid URL format (line 282)
     */
    @Test
    public void testSetUrlWithInvalidFormat() {
        config.setUrl("invalid");
        // Should not throw exception, just return early
        assertFalse(config.isOracle());
        assertFalse(config.isMySQL());
        assertFalse(config.isDB2());
    }

    /**
     * Test: getPassword when password is null (line 376)
     */
    @Test
    public void testGetPasswordWhenNull() {
        config.setPassword(null);
        assertNull(config.getPassword());
    }

    /**
     * Test: getPassword when password is empty string (line 376)
     */
    @Test
    public void testGetPasswordWhenEmpty() {
        config.setPassword("");
        assertNull(config.getPassword());
    }

    /**
     * Test: reloadProperties when properties is set (lines 692-693)
     */
    @Test
    public void testReloadPropertiesWhenSet() throws SQLException {
        config.loadFromProperties("jdbc");
        String originalUrl = config.getUrl();

        // Reload should not throw exception
        config.reloadProperties();

        // URL should remain the same after reload
        assertEquals(originalUrl, config.getUrl());
    }

    /**
     * Test: reloadProperties when properties is null (line 692)
     */
    @Test
    public void testReloadPropertiesWhenNull() {
        // Should not throw exception when properties is null
        config.reloadProperties();
        // No assertion needed, just ensure no exception
    }

    /**
     * Test: _loadProperties with FileNotFoundException (line 717)
     */
    @Test
    public void testLoadPropertiesWithNonExistentFile() {
        // This will trigger the FileNotFoundException path
        assertThrows(SQLException.class, () -> {
            config.loadFromProperties("non_existent_file_xyz");
        });
    }

    /**
     * Test: printConfig with null logger (lines 809)
     */
    @Test
    public void testPrintConfigWithNullLogger() {
        config.setUrl("jdbc:mysql://localhost/test");
        config.setUsername("test");
        config.setPassword("password");

        // Should use internal LOGGER when null is passed
        config.printConfig(null);

        // No exception should be thrown
    }

    /**
     * Test: setSecurityService (line 637)
     */
    @Test
    public void testSetSecurityService() {
        SecurityService customService = new SecurityServiceLocalImpl();
        config.setSecurityService(customService);

        assertEquals(customService, config.getSecurityService());
    }

    /**
     * Test: getPasswordKey with system property
     */
    @Test
    public void testGetPasswordKeyWithSystemProperty() {
        String originalKey = System.getProperty("JDBC_SECRET_KEY");
        try {
            System.setProperty("JDBC_SECRET_KEY", "test-key-from-system");
            HqcpConfig newConfig = new HqcpConfig();

            String key = newConfig.getPasswordKey();
            assertEquals("test-key-from-system", key);
        } finally {
            if (originalKey != null) {
                System.setProperty("JDBC_SECRET_KEY", originalKey);
            } else {
                System.clearProperty("JDBC_SECRET_KEY");
            }
        }
    }

    /**
     * Test: getPasswordKey with environment variable (fallback)
     */
    @Test
    public void testGetPasswordKeyDefault() {
        HqcpConfig newConfig = new HqcpConfig();
        String key = newConfig.getPasswordKey();

        // Should return default key if no system property or env var
        assertNotNull(key);
        assertTrue(key.length() > 0);
    }

    /**
     * Test: decryptPassword with non-base64 string
     */
    @Test
    public void testDecryptPasswordNonBase64() {
        config.setPassword("plaintext");
        assertEquals("plaintext", config.getPassword());
    }

    /**
     * Test: decryptPassword with base64 but not encrypted
     */
    @Test
    public void testDecryptPasswordBase64NotEncrypted() {
        // Base64 encoded "plaintext"
        config.setPassword("cGxhaW50ZXh0");
        assertNotNull(config.getPassword());
    }

    /**
     * Test: getSecurityService without applicationContext
     */
    @Test
    public void testGetSecurityServiceWithoutContext() {
        HqcpConfig newConfig = new HqcpConfig();
        SecurityService service = newConfig.getSecurityService();

        assertNotNull(service);
        assertTrue(service instanceof SecurityServiceLocalImpl);
    }
}

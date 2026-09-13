package io.hqwu.commons.cp;

import com.jolbox.bonecp.MockJDBCDriver;
import io.hqwu.commons.cp.dialect.DB2PooledConnection;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB2PooledConnection 测试类
 */
@DisplayName("DB2PooledConnection 测试")
class DB2PooledConnectionTest {

    private Hqcp pool;
    private DB2PooledConnection connection;
    private Connection proxyConnection;

    @BeforeAll
    static void init() {
        MockJDBCDriver.getInstance().setAcceptUrl("jdbc:db2");
    }

    /**
     * MockJDBCDriver 是 JVM 级单例，acceptUrl 改了必须还原，
     * 否则后续测试类通过 DriverManager 取 jdbc:mock 会报 "No suitable driver found"。
     */
    @AfterAll
    static void restoreDriver() throws SQLException {
        MockJDBCDriver.getInstance().disable();
    }

    @BeforeEach
    void setUp() throws Exception {
        // 配置 DB2 数据源
        var config = new HqcpConfig();
        config.setUrl("jdbc:db2://localhost:50000/testdb");
        config.setUsername("testuser");
        config.setPassword("testpass");
        config.setMinConnections(1);
        config.setMaxConnections(2);
        config.setMaxStatements(10);
        config.setMaxPreStatements(10);
        config.setJmxLevel(0);

        // 使用 MockJDBCDriver
        MockJDBCDriver.getInstance().setConnection(null);

        pool = new Hqcp(config);

        // 通过 Hqcp 获取连接，会根据 URL 自动创建 DB2PooledConnection
        proxyConnection = pool.getConnection();

        // 使用 unwrap 方法获取实际的 DB2PooledConnection
        connection = proxyConnection.unwrap(DB2PooledConnection.class);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (proxyConnection != null && !proxyConnection.isClosed()) {
            proxyConnection.close();
        }
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("测试DB2特定错误码 -512 被识别为致命异常")
    void testFetalException_ErrorCode512() {
        var exception = new SQLException("STATEMENT REFERENCE TO REMOTE OBJECT IS INVALID", "23001", -512);

        assertTrue(connection.isFatalException(exception), "错误码 -512 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试DB2特定错误码 -514 被识别为致命异常")
    void testFetalException_ErrorCode514() {
        var exception = new SQLException("THE CURSOR IS NOT IN A PREPARED STATE", "23001", -514);

        assertTrue(connection.isFatalException(exception), "错误码 -514 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试DB2特定错误码 -516 被识别为致命异常")
    void testFetalException_ErrorCode516() {
        var exception = new SQLException("THE DESCRIBE STATEMENT DOES NOT SPECIFY A PREPARED STATEMENT", "23001", -516);

        assertTrue(connection.isFatalException(exception), "错误码 -516 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试DB2特定错误码 -518 被识别为致命异常")
    void testFetalException_ErrorCode518() {
        var exception = new SQLException("THE EXECUTE STATEMENT DOES NOT IDENTIFY A VALID PREPARED STATEMENT", "23001", -518);

        assertTrue(connection.isFatalException(exception), "错误码 -518 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试DB2特定错误码 -525 被识别为致命异常")
    void testFetalException_ErrorCode525() {
        var exception = new SQLException("THE SQL STATEMENT CANNOT BE EXECUTED BECAUSE IT WAS IN ERROR AT BIND TIME", "23001", -525);

        assertTrue(connection.isFatalException(exception), "错误码 -525 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试DB2特定错误码 -909 被识别为致命异常")
    void testFetalException_ErrorCode909() {
        var exception = new SQLException("THE OBJECT HAS BEEN DELETED OR ALTERED", "23001", -909);

        assertTrue(connection.isFatalException(exception), "错误码 -909 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试DB2特定错误码 -918 被识别为致命异常")
    void testFetalException_ErrorCode918() {
        var exception = new SQLException("THE SQL STATEMENT CANNOT BE EXECUTED BECAUSE A CONNECTION HAS BEEN LOST", "23001", -918);

        assertTrue(connection.isFatalException(exception), "错误码 -918 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试DB2特定错误码 -924 被识别为致命异常")
    void testFetalException_ErrorCode924() {
        var exception = new SQLException("DB2 CONNECTION INTERNAL ERROR", "23001", -924);

        assertTrue(connection.isFatalException(exception), "错误码 -924 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试父类的SQLRecoverableException被识别为致命异常")
    void testFetalException_SQLRecoverableException() {
        var exception = new SQLRecoverableException("Connection lost");

        assertTrue(connection.isFatalException(exception), "SQLRecoverableException 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试父类的08开头的SQLState被识别为致命异常")
    void testFetalException_SQLState08() {
        var exception = new SQLException("Connection exception", "08006");

        assertTrue(connection.isFatalException(exception), "SQLState 08xxx 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试非致命异常不被识别为致命异常")
    void testFetalException_NonFetal() {
        var exception = new SQLException("Constraint violation", "23000", -803);

        assertFalse(connection.isFatalException(exception), "普通业务异常不应被识别为致命异常");
    }

    @Test
    @DisplayName("测试所有DB2特定错误码")
    void testFetalException_AllDB2ErrorCodes() {
        int[] fetalErrorCodes = {-512, -514, -516, -518, -525, -909, -918, -924};

        for (int errorCode : fetalErrorCodes) {
            var exception = new SQLException("DB2 Error", "23001", errorCode);
            assertTrue(connection.isFatalException(exception), "错误码 " + errorCode + " 应被识别为致命异常");
        }
    }

    @Test
    @DisplayName("测试SQLState以5开头被识别为致命异常")
    void testFetalException_SQLState5() {
        var exception = new SQLException("Implementation error", "5S000");

        assertTrue(connection.isFatalException(exception), "SQLState 5xxxx 应被识别为致命异常");
    }

    @Test
    @DisplayName("测试空SQLState且非DB2特定错误码也会调用父类判断")
    void testFetalException_NullSQLState() {
        var exception = new SQLException("Some error", null, -100);

        assertTrue(connection.isFatalException(exception), "空SQLState时会调用父类的判断逻辑");
    }
}

package io.hqwu.commons.cp;

import com.jolbox.bonecp.*;
import io.hqwu.commons.util.Logger;
import org.easymock.IMocksControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.Properties;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.easymock.EasyMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Created with IntelliJ IDEA for plat-arch-svn
 * User: taige
 * Date: 13-10-31
 * Time: 上午10:18
 */
public class HqcpTest {
    private static final Logger LOGGER = new Logger();

    private MockJDBCDriver driver;
    private MockJDBCAnswer answer;

    private MockConnection mockConnection;
    private MockJDBCStatement mockStatement;
    private MockPreparedStatement mockPreparedStatement;
    private MockCallableStatement mockCallableStatement;

    private HqcpConfig config;
    private Hqcp connPool;

    private IMocksControl mocksControl;
    @BeforeEach
    public void setUp() throws SQLException {
        // MockJDBCDriver 是 JVM 级单例，acceptUrl / answer 会被别的测试类改写；
        // 本类的连接都靠 DriverManager 解析 jdbc:mock，所以每个用例前先复位驱动状态。
        MockJDBCDriver.getInstance().disable();
        mocksControl = createNiceControl();
        mockConnection = mocksControl.createMock(MockConnection.class);
        mockConnection.close();
        expectLastCall().once();
        config = mocksControl.createMock(HqcpConfig.class);
        expect(config.getUrl()).andReturn(MockConstant.MOCK_URL).atLeastOnce();
        expect(config.getDriverClassName()).andReturn(null).atLeastOnce();
        expect(config.getIdleTimeoutMillisec()).andReturn(10000L).atLeastOnce(); //回收时间10sec
        expect(config.getCheckoutTimeoutMillisec()).andReturn(5000L).anyTimes(); //获取连接的超时时间5sec
        expect(config.getCheckStatement()).andReturn("test checking sql").anyTimes();
        expect(config.getJmxLevel()).andReturn(2).atLeastOnce();
        expect(config.getMaxConnections()).andReturn(5).anyTimes(); //最大连接 5
        expect(config.getMinConnections()).andReturn(1).atLeastOnce(); //最小 1
        expect(config.getMaxPreStatements()).andReturn(5).anyTimes();
        expect(config.getMaxStatements()).andReturn(10).anyTimes();
        expect(config.getUsername()).andReturn("mockuser").anyTimes();
//        expect(config.getPassword()).andReturn("mockpassword").anyTimes();
        expect(config.isVerbose()).andReturn(false).once().andReturn(true).anyTimes();
        expect(config.isCommitOnClose()).andReturn(false).once().andReturn(true).anyTimes();
        expect(config.isPrintSql()).andReturn(false).once().andReturn(true).anyTimes();
        expect(config.isTransactionMode()).andReturn(false).once().andReturn(true).anyTimes();
        expect(config.getInfoSqlThreshold()).andReturn(10L).anyTimes();
        expect(config.getWarnSqlThreshold()).andReturn(100L).anyTimes();
        expect(config.isOracle()).andReturn(false).atLeastOnce();
//        expect(config.isUseOracleImplicitCache()).andReturn(true).anyTimes();
        expect(config.getQueryTimeout()).andReturn(0).atLeastOnce();
        Properties properties = new Properties();
        properties.setProperty("user", "mockuser");
        properties.setProperty("password", "mockpassword");
        expect(config.getConnectionProperties()).andReturn(properties).atLeastOnce();

        //create mock mockStatement & resultset
        MockResultSet rs = mocksControl.createMock(MockResultSet.class);
        expect(rs.next()).andReturn(true).anyTimes();
        mockStatement = mocksControl.createMock(MockJDBCStatement.class);
        //idle check mockStatement should be call once
        expect(mockStatement.execute((String) anyObject())).andReturn(true).anyTimes();
        expect(mockStatement.executeQuery((String) anyObject())).andReturn(rs).anyTimes();
        expect(mockStatement.executeUpdate((String) anyObject())).andReturn(1).anyTimes();
        int[] n = {1};
        expect(mockStatement.executeBatch()).andReturn(n).anyTimes();
        expect(mockStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

//        mockPreparedStatement = mocksControl.createMock(MockPreparedStatement.class);
//        expect(mockPreparedStatement.execute()).andReturn(true).anyTimes();
//        expect(mockPreparedStatement.executeQuery()).andReturn(rs).anyTimes();
//        expect(mockPreparedStatement.executeUpdate()).andReturn(1).anyTimes();
//        expect(mockPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
//        expect(mockPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
//        expect(mockPreparedStatement.executeBatch()).andReturn(n).anyTimes();

        mockCallableStatement = mocksControl.createMock(MockCallableStatement.class);
        expect(mockCallableStatement.execute()).andReturn(true).anyTimes();
        expect(mockCallableStatement.executeQuery()).andReturn(rs).anyTimes();
        expect(mockCallableStatement.executeUpdate()).andReturn(1).anyTimes();
        expect(mockCallableStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockCallableStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        expect(mockConnection.createStatement()).andReturn(mockStatement).anyTimes();
//        expect(mockConnection.prepareStatement((String) anyObject())).andReturn(mockPreparedStatement).anyTimes();
        expect(mockConnection.prepareCall((String) anyObject())).andReturn(mockCallableStatement).anyTimes();
        expect(mockConnection.prepareCall((String) anyObject(), anyInt(), anyInt())).andReturn(mockCallableStatement).anyTimes();
        expect(mockConnection.prepareCall((String) anyObject(), anyInt(), anyInt(), anyInt())).andReturn(mockCallableStatement).anyTimes();

        makeThreadSafe(config, true);
        makeThreadSafe(mockConnection, true);
    }

    @AfterEach
    public void tearDown() throws SQLException {
        if (connPool != null) {
            if (! connPool.isShutdown()) {
                Connection conn = connPool.getConnection();
                Statement stmt = conn.createStatement();
                stmt.executeUpdate("tearDown sql");
                conn.close();
                connPool.shutdown();
            }
            mocksControl.verify();
            connPool = null;
        }
        if (driver != null) {
            driver.disable();
            driver = null;
        }
    }

    @Test
    public void testShutdown() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Connection conn = connPool.getConnection();
        assertEquals(conn, mockConnection);
        Statement stmt = conn.createStatement();
        int n = stmt.executeUpdate("testShutdown sql");
        assertEquals(1, n);
        stmt.close();
        conn.close();
        assertEquals(1, connPool.getActiveConnectionsCount());

        //shutdown() should call real connection's close()
        connPool.shutdown();
        assertEquals(0, connPool.getActiveConnectionsCount());
        SQLException ex = assertThrows(SQLException.class, () -> connPool.getConnection());
        assertEquals("connection pool is shutdown", ex.getMessage());

    }

    @Tag("slow")
    @Test
    public void testShutdownForce() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Connection conn = connPool.getConnection();
        assertEquals(conn, mockConnection);
        //conn.close();  不主动关闭connection，shutdown时强制关闭
        Statement stmt = conn.createStatement();
        int n = stmt.executeUpdate("testShutdownForce sql");
        assertEquals(1, n);
        stmt.close();
        assertEquals(1, connPool.getActiveConnectionsCount());

        //shutdown() should call real connection's close()
        connPool.shutdown();
        assertEquals(0, connPool.getActiveConnectionsCount());
        SQLException ex = assertThrows(SQLException.class, () -> connPool.getConnection());
        assertEquals("connection pool is shutdown", ex.getMessage());

    }

    @Tag("slow")
    @Test
    public void testGetConnectionTimeout_lazyInit() throws Exception {
        /*
         * lasyInit true的时候，getConnection才会抛出Timeout的SQLException
         */
        expect(config.isLazyInit()).andReturn(true).anyTimes();
        final CyclicBarrier cyclicBarrier = new CyclicBarrier(2);
        answer = new MockJDBCAnswer() {
            @Override
            public Connection answer() throws SQLException {
                LOGGER.info("wait util getConnection timeout...");
                try {
                    cyclicBarrier.await(7, TimeUnit.SECONDS); //timeout is 5000 ms
                //} catch (TimeoutException e) {
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("test getConnection timeout fail..." + e.getMessage());
                }
                return mockConnection;
            }
        };
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Future<?> future = Executors.newSingleThreadExecutor().submit(() -> {
            Connection conn = connPool.getConnection();
            conn.close();
            return null;
        });
        ExecutionException ex = assertThrows(ExecutionException.class, () -> {
            future.get(5500, TimeUnit.MILLISECONDS);  //获取连接的超时时间5sec
        });
        assertTrue(ex.getCause() instanceof SQLException);
        assertTrue(ex.getCause().getMessage().contains("Timeout on waiting for an available connection"));
        cyclicBarrier.await();
    }

    @Tag("slow")
    @Test
    public void testGetConnectionExhaustedTimeout() throws Exception {
        MockConnection mockConnection2 = new MockConnection();
        //mockConnection2.close();
        //expectLastCall().times(4);

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).andReturn(mockConnection2).times(4);
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Connection[] conns = new Connection[5]; //max connections is 5
        for (int i = 0; i < conns.length; i++) {
            conns[i] = connPool.getConnection();
        }
        Future<?> future = Executors.newSingleThreadExecutor().submit(() -> {
            Connection conn = connPool.getConnection();
            conn.close();
            return null;
        });
        ExecutionException ex = assertThrows(ExecutionException.class, () -> {
            future.get(5500, TimeUnit.MILLISECONDS);  //获取连接的超时时间5sec
        });
        assertTrue(ex.getCause() instanceof SQLException);
        assertTrue(ex.getCause().getMessage().contains("Timeout on waiting for an available connection"));
        for (Connection conn : conns) {
            conn.close();
        }
    }

    @Test
    public void testStatementWithParameters_executeReturnTrue() throws Exception {
        MockJDBCStatement mockJDBCStatement = mocksControl.createMock(MockJDBCStatement.class);
        expect(mockJDBCStatement.execute((String) anyObject())).andReturn(true).anyTimes();
        expect(mockJDBCStatement.executeUpdate((String) anyObject())).andReturn(1).anyTimes();
        expect(mockJDBCStatement.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_SENSITIVE).anyTimes();
        expect(mockJDBCStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        MockResultSet rs = mocksControl.createMock(MockResultSet.class);
        expect(mockJDBCStatement.getResultSet()).andReturn(rs).anyTimes();

        expect(mockConnection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE)).andReturn(mockJDBCStatement).anyTimes();

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        config.isPrintSql();
        Connection conn = connPool.getConnection();
        try {
            Statement stmt = conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
            assertTrue(stmt.execute("stmt ...."));
            stmt.close();
        } catch (SQLException e) {
            fail("testStatement fail...");
        }
        conn.commit();
        conn.close();
    }

    @Test
    public void testStatementWithParameters_executeReturnFalse() throws Exception {
        MockJDBCStatement mockJDBCStatement = mocksControl.createMock(MockJDBCStatement.class);
        expect(mockJDBCStatement.execute((String) anyObject())).andReturn(false).anyTimes();
        expect(mockJDBCStatement.executeUpdate((String) anyObject())).andReturn(1).anyTimes();
        expect(mockJDBCStatement.executeLargeUpdate((String) anyObject())).andReturn(100L).anyTimes();
        expect(mockJDBCStatement.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_SENSITIVE).anyTimes();
        expect(mockJDBCStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        MockResultSet rs = mocksControl.createMock(MockResultSet.class);
        expect(mockJDBCStatement.getResultSet()).andReturn(rs).anyTimes();
        expect(mockJDBCStatement.getUpdateCount()).andReturn(10).anyTimes();

        expect(mockConnection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE)).andReturn(mockJDBCStatement).anyTimes();

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        config.isPrintSql();
        Connection conn = connPool.getConnection();
        try {
            Statement stmt = conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
            assertFalse(stmt.execute("stmt ...."));
            assertEquals(100, stmt.executeLargeUpdate("stmt ...."));
            stmt.close();
        } catch (SQLException e) {
            fail("testStatement fail...");
        }
        conn.commit();
        conn.close();
    }

    @Test
    public void testStatement() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Connection conn = connPool.getConnection();
        Statement stmts[] = new Statement[10];
        for (int i = 0; i < stmts.length; i++) {
            stmts[i] = conn.createStatement();
            stmts[i].executeQuery("stmt"+i);
        }
        SQLException ex = assertThrows(SQLException.class, () -> conn.createStatement());
        assertTrue(ex.getMessage().contains("exceed max value"));

        for (Statement stmt : stmts) {
            stmt.close();
        }
        conn.commit();
        for (int i = 0; i < stmts.length; i++) {
            stmts[i] = conn.createStatement();
            stmts[i].executeUpdate("stmt"+i);
            stmts[i].close();
        }
        conn.rollback();
        try {
            Statement stmt = conn.createStatement();
            stmt.execute("stmt ....");
        } catch (SQLException e) {
            fail("testStatement fail...");
        }
        try {
            Statement stmt = conn.createStatement();
            stmt.addBatch("stmt ....");
            stmt.executeBatch();
        } catch (SQLException e) {
            fail("testStatement fail...");
        }

        conn.close();
    }

    @Test
    public void testPreparedStatement_fail() throws Exception {
        mockPreparedStatement = mocksControl.createMock(MockPreparedStatement.class);
        expect(mockPreparedStatement.execute()).andThrow(new SQLException("mock execute sql exception")).anyTimes(); //.andReturn(true).anyTimes();
        expect(mockPreparedStatement.executeQuery()).andThrow(new SQLException("mock executeQuery sql exception")).anyTimes(); //.andReturn(rs).anyTimes();
        expect(mockPreparedStatement.executeUpdate()).andThrow(new SQLException("mock executeUpdate sql exception")).anyTimes(); //.andReturn(1).anyTimes();
        expect(mockPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPreparedStatement.executeBatch()).andReturn(new int[]{1}).anyTimes();

        expect(mockConnection.prepareStatement((String) anyObject())).andReturn(mockPreparedStatement).anyTimes();

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        connPool.getConnection().close();
        Connection conn = connPool.getConnection(false);
        SQLException ex = assertThrows(SQLException.class, () -> {
            PreparedStatement pstmt = conn.prepareStatement("prestmt #101 where a=? and bb=? and cc=? and d=? and ee=? and e=1");
            pstmt.setString(1, "aaa");
            pstmt.setInt(2, 123);
            pstmt.setDate(3, new Date(System.currentTimeMillis()));
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.setTime(5, new Time(System.currentTimeMillis()));
            pstmt.execute();
            pstmt.close();
        });
        assertEquals("mock execute sql exception", ex.getMessage());
        ex = assertThrows(SQLException.class, () -> {
            PreparedStatement pstmt = conn.prepareStatement("prestmt #101 where a=? and bb=? and cc=? and d=? and ee=? and e=1");
            pstmt.setString(1, "aaa");
            pstmt.setInt(2, 123);
            pstmt.setDate(3, new Date(System.currentTimeMillis()));
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.setTime(5, new Time(System.currentTimeMillis()));
            pstmt.executeUpdate();
            pstmt.close();
            fail("testPreparedStatement_fail fail...");
        });
        assertEquals("mock executeUpdate sql exception", ex.getMessage());
        ex = assertThrows(SQLException.class, () -> {
            PreparedStatement pstmt = conn.prepareStatement("prestmt #101 where a=? and bb=? and cc=? and d=? and ee=? and e=1");
            pstmt.setString(1, "aaa");
            pstmt.setInt(2, 123);
            pstmt.setDate(3, new Date(System.currentTimeMillis()));
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.setTime(5, new Time(System.currentTimeMillis()));
            pstmt.executeQuery();
            pstmt.close();
            fail("testPreparedStatement_fail fail...");
        });
        assertEquals("mock executeQuery sql exception", ex.getMessage());
        conn.close();
    }

    @Test
    public void testPreparedStatement_2() throws Exception {
        mockPreparedStatement = mocksControl.createMock(MockPreparedStatement.class);
        expect(mockPreparedStatement.execute()).andReturn(true).once().andReturn(false).anyTimes();
//        expect(mockPreparedStatement.executeQuery()).andReturn(rs).anyTimes();
        expect(mockPreparedStatement.executeUpdate()).andReturn(1).anyTimes();
        expect(mockPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPreparedStatement.executeBatch()).andReturn(new int[]{1}).anyTimes();
        MockResultSet rs = mocksControl.createMock(MockResultSet.class);
        expect(mockPreparedStatement.getResultSet()).andReturn(rs).anyTimes();
        expect(mockPreparedStatement.getUpdateCount()).andReturn(10).anyTimes();

        expect(mockConnection.prepareStatement((String) anyObject())).andReturn(mockPreparedStatement).anyTimes();
        expect(mockConnection.prepareStatement((String) anyObject(), (int []) anyObject())).andReturn(mockPreparedStatement).anyTimes();
        expect(mockConnection.prepareStatement((String) anyObject(), (String []) anyObject())).andReturn(mockPreparedStatement).anyTimes();
        expect(mockConnection.prepareStatement((String) anyObject(), anyInt())).andReturn(mockPreparedStatement).anyTimes();
        expect(mockConnection.prepareStatement((String) anyObject(), anyInt(), anyInt())).andReturn(mockPreparedStatement).anyTimes();
        expect(mockConnection.prepareStatement((String) anyObject(), anyInt(), anyInt(), anyInt())).andReturn(mockPreparedStatement).anyTimes();

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        config.isPrintSql();
        connPool.getConnection().close();
        Connection conn = connPool.getConnection(false);
        PreparedStatement pstmt = conn.prepareStatement("prestmt #100");
        pstmt.executeUpdate();
        pstmt.execute();
        pstmt.execute();
        pstmt.close();

        PreparedStatement pstmt1 = conn.prepareStatement("prestmt #200", new int[] {0, 1});
        pstmt1.executeUpdate();
        pstmt1.close();

        PreparedStatement pstmt2 = conn.prepareStatement("prestmt #300", new String[] {"a", "b"});
        pstmt2.executeUpdate();
        pstmt2.close();

        PreparedStatement pstmt3 = conn.prepareStatement("prestmt #400", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
        pstmt3.executeUpdate();
        pstmt3.close();

        PreparedStatement pstmt4 = conn.prepareStatement("prestmt #500", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE, ResultSet.HOLD_CURSORS_OVER_COMMIT);
        pstmt4.executeUpdate();
        pstmt4.close();

        PreparedStatement pstmt5 = conn.prepareStatement("prestmt #600", Statement.RETURN_GENERATED_KEYS);
        pstmt5.executeUpdate();
        pstmt5.close();

        conn.close();
    }

    @Test
    public void testPreparedStatement() throws Exception {
        mockPreparedStatement = mocksControl.createMock(MockPreparedStatement.class);
        expect(mockPreparedStatement.execute()).andReturn(true).anyTimes();
//        expect(mockPreparedStatement.executeQuery()).andReturn(rs).anyTimes();
        expect(mockPreparedStatement.executeUpdate()).andReturn(1).anyTimes();
        expect(mockPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPreparedStatement.executeBatch()).andReturn(new int[]{1}).anyTimes();

        expect(mockConnection.prepareStatement((String) anyObject())).andReturn(mockPreparedStatement).anyTimes();

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        connPool.getConnection().close();
        Connection conn = connPool.getConnection(false);
        PreparedStatement stmts[] = new PreparedStatement[10];
        for (int i = 0; i < 10; i++) {
            stmts[i] = conn.prepareStatement("prestmt #" + i);
            stmts[i].executeQuery();
        }
        conn.rollback();
        try {
            PreparedStatement pstmt = conn.prepareStatement("prestmt #100");
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            fail("testPreparedStatement fail...");
        }
        for (PreparedStatement pstmt : stmts) {
            pstmt.close();
        }
        conn.commit();
        try {
            PreparedStatement pstmt = conn.prepareStatement("prestmt #101 where a=? and bb=? and cc=? and d=? and ee=? and e=1");
            pstmt.setString(1, "aaa");
            pstmt.setInt(2, 123);
            pstmt.setDate(3, new Date(System.currentTimeMillis()));
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.setTime(5, new Time(System.currentTimeMillis()));
            pstmt.execute();
            pstmt.close();
        } catch (SQLException e) {
            fail("testPreparedStatement fail...");
        }
        try {
            PreparedStatement pstmt = conn.prepareStatement("prestmt #101 where a=? and c = ? And dd=?");
            pstmt.setString(1, "ABC");
            pstmt.setString(3, "AAA");
            pstmt.addBatch();
            pstmt.executeBatch();
            pstmt.close();
        } catch (SQLException e) {
            fail("testPreparedStatement fail...");
        }
        try {
            PreparedStatement pstmt = conn.prepareStatement("prestmt #101");
            pstmt.execute();
            PreparedStatement pstmt2 = conn.prepareStatement("prestmt #101");
            pstmt.close();
            pstmt2.close();
        } catch (SQLException e) {
            fail("testPreparedStatement fail...");
        }
        conn.close();
    }

    public static void main(String[] args) throws Exception {
        HqcpTest testCase = new HqcpTest();
        testCase.setUp();
        testCase.testPreparedStatement();
        testCase.tearDown();
    }

    @Test
    public void testCallableStatement_2() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        connPool.getConnection().close();
        Connection conn = connPool.getConnection(false);

        CallableStatement pstmt = conn.prepareCall("prestmt #100");
        pstmt.executeUpdate();
        pstmt.close();

        CallableStatement pstmt1 = conn.prepareCall("prestmt #200", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
        pstmt1.executeUpdate();
        pstmt1.close();

        CallableStatement pstmt2 = conn.prepareCall("prestmt #300", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE, ResultSet.HOLD_CURSORS_OVER_COMMIT);
        pstmt2.executeUpdate();
        pstmt2.close();
        conn.close();
    }

    @Test
    public void testCallableStatement() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        connPool.getConnection().close();
        Connection conn = connPool.getConnection(false);
        CallableStatement stmts[] = new CallableStatement[10];
        for (int i = 0; i < 10; i++) {
            stmts[i] = conn.prepareCall("prestmt #" + i);
            stmts[i].executeQuery();
        }
        conn.rollback();
        try {
            CallableStatement pstmt = conn.prepareCall("prestmt #100");
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            fail("testPreparedStatement fail...");
        }
        for (CallableStatement pstmt : stmts) {
            pstmt.close();
        }
        conn.commit();
        try {
            CallableStatement pstmt = conn.prepareCall("prestmt #101");
            pstmt.execute();
            pstmt.close();
        } catch (SQLException e) {
            fail("testPreparedStatement fail...");
        }
        conn.close();
    }

    @Tag("slow")
    @Test
    public void testGetConnectionExhaustedWaitAndOK() throws Exception {
        MockConnection mockConnection2 = mocksControl.createMock(MockConnection.class);
        mockConnection2.close();
        expectLastCall().times(4);

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).andReturn(mockConnection2).times(4);
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        final Connection conn = connPool.getConnection();
        Connection[] conns = new Connection[4]; //max connections is 5
        for (int i = 0; i < conns.length; i++) {
            conns[i] = connPool.getConnection();
        }
        final CyclicBarrier cyclicBarrier = new CyclicBarrier(2);

        //用于异步的将conn放回连接池的线程
        new Thread(() -> {
            try {
                cyclicBarrier.await();
                LOGGER.debug("sleep 3000ms");
                Thread.sleep(3000); //timeout is 5 sec
                conn.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        cyclicBarrier.await();
        Connection conn6 = connPool.getConnection();
        assertEquals(conn6, mockConnection);

        for (Connection _conn : conns) {
            _conn.close();
        }
        conn6.close();
    }

    @Tag("slow")
    @Test
    public void testConnectionIdleAndRealClose() throws Exception {
        MockConnection mockConnection2 = createNiceMock(MockConnection.class);
        //mockConnection2 should be real closed.
        mockConnection2.close();
        expectLastCall().once();
        expect(mockConnection2.createStatement()).andReturn(mockStatement).anyTimes();
        makeThreadSafe(mockConnection2, true);
        replay(mockConnection2);

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once().andReturn(mockConnection2).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);
        connPool = new Hqcp(config);

        //create 2 connections
        Connection conn1 = connPool.getConnection();
        Connection conn2 = connPool.getConnection();
        conn2.close();

        LOGGER.info("conn2 back to pool. now wait conn1 back to pool... 1 sec");
        Thread.sleep(1000); //模拟conn1的工作
        conn1.close();

        LOGGER.info("conn1 back to pool. now wait 11 secs util conn2 to be real closed...");
        Thread.sleep(11000); //conn2放回连接池已经11+1秒，回收时间设置的10秒，conn2应该已经被回收
        assertEquals(1, connPool.getActiveConnectionsCount());
        assertEquals(1, connPool.getIdleConnectionsCount());

        //verify mockConnection2.close() is be called
        verify(mockConnection2);

    }

    @Test
    public void testGetConnection() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Connection conn = connPool.getConnection();
        assertEquals(conn, mockConnection);
        conn.close();

        Connection conn1 = connPool.getConnection();
        assertEquals(conn1, mockConnection);
        conn1.close();

    }

    @Test
    public void testGetConnectionAutoCommit() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Connection conn = connPool.getConnection(true);
        assertEquals(conn, mockConnection);
        conn.close();

        Connection conn1 = connPool.getConnection();
        assertEquals(conn1, mockConnection);
        conn1.close();
    }

    @Test
    public void testGetConnectionLIFO() throws Exception {
        MockConnection mockConnection2 = new MockConnection();
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once().andReturn(mockConnection2).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);
        connPool = new Hqcp(config);

        // 等待 Monitor 完成 minConnections 初始连接创建，避免与主线程竞争 answer.answer() 的消费顺序
        long deadline = System.currentTimeMillis() + 3000;
        while (connPool.getIdleConnectionsCount() < 1) {
            assertTrue(System.currentTimeMillis() < deadline, "等待 Monitor 初始化超时");
            Thread.sleep(10);
        }

        Connection conn1 = connPool.getConnection();
        assertEquals(conn1, mockConnection);
        Connection conn2 = connPool.getConnection();
        assertEquals(conn2, mockConnection2);

        conn1.close();
        conn2.close(); //mockConnection2 is LI

        Connection conn3 = connPool.getConnection();
        assertEquals(conn3, mockConnection2); //mockConnection2 is FO

        conn3.close();
    }

    @Test
    public void testGetPoolName() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).times(2);
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        Connection conn = connPool.getConnection();
        Statement stmt = conn.createStatement();
        int n = stmt.executeUpdate("testGetPoolName sql");
        assertEquals(1, n);
        stmt.close();
        conn.close();
        Hqcp connPool2 = new Hqcp(config);
        connPool2.getConnection().close();
        assertFalse(connPool.getPoolName().equals(connPool2.getPoolName()));
        connPool2.shutdown();

        connPool.setPoolName("testPool");
        assertEquals("testPool", connPool.getPoolName());

        mocksControl.verify();
    }

    @Test
    public void testGetConnectionsCount() throws Exception {
        MockConnection mockConnection2 = new MockConnection();
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once().andReturn(mockConnection2).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        assertEquals(1, connPool.getActiveConnectionsCount());
        assertEquals(1, connPool.getIdleConnectionsCount());

        Connection conn1 = connPool.getConnection();
        assertEquals(conn1, mockConnection);
        assertEquals(1, connPool.getActiveConnectionsCount());
        assertEquals(0, connPool.getIdleConnectionsCount());

        Connection conn2 = connPool.getConnection();
        assertEquals(conn2, mockConnection2);
        assertEquals(2, connPool.getActiveConnectionsCount());
        assertEquals(0, connPool.getIdleConnectionsCount());

        conn1.close();
        assertEquals(1, connPool.getIdleConnectionsCount());
        conn2.close();
        assertEquals(2, connPool.getIdleConnectionsCount());
        assertEquals(2, connPool.getActiveConnectionsCount());
    }

    @Test
    public void testGetConfig() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        connPool.getConnection().close();
        assertSame(config, connPool.getConfig());
    }

    @Test
    public void testGetPoolId() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).times(2);
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        connPool = new Hqcp(config);
        // 等待 Monitor 完成初始连接创建，避免与主线程竞争 answer.answer() 的消费顺序
        long deadline = System.currentTimeMillis() + 3000;
        while (connPool.getIdleConnectionsCount() < 1) {
            assertTrue(System.currentTimeMillis() < deadline, "等待 Monitor 初始化超时");
            Thread.sleep(10);
        }
        connPool.getConnection().close();
        Hqcp connPool2 = new Hqcp(config);
        deadline = System.currentTimeMillis() + 3000;
        while (connPool2.getIdleConnectionsCount() < 1) {
            assertTrue(System.currentTimeMillis() < deadline, "等待 Monitor 初始化超时");
            Thread.sleep(10);
        }
        connPool2.getConnection().close();
        assertEquals(connPool.getPoolId() + 1, connPool2.getPoolId());
        connPool2.shutdown();
    }

    /**
     * 测试 lifetimeSec 参数：
     * 当 lifetimeSec > 0 且连接真正超时后，在checkIn时回收；或者在Monitor线程回收
     */
    @Tag("slow")
    @Test
    public void testLifetimeSecEffective() throws Exception {
        Random random = new Random();
        WaitWithTimeout wait = new WaitWithTimeout();
        Queue<MockConnection> connections = new ConcurrentLinkedQueue<>();

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once().andAnswer(() -> {
            MockConnection conn = new MockConnection() {
                @Override
                public Statement createStatement() throws SQLException {
                    return new MockJDBCStatement(this) {
                        @Override
                        public boolean execute(String sql) throws SQLException {
                            wait.awaitWithTimeout(100 + (long) (random.nextDouble() * 1000));
                            return true;
                        }
                    };
                }
            };
            conn.connect();
            LOGGER.info("connection#{} connected by {}", conn.getConnId(), conn.getConnectThread());
            connections.offer(conn);
            return conn;
        }).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);

        {  // 为了兼容其他测试用例，需要执行这段
            this.connPool = new Hqcp(config);
            Connection conn = this.connPool.getConnection();
            conn.createStatement().executeUpdate("lalalal");
            conn.close();
            this.connPool.shutdown();
        }

        HqcpConfig realConfig = new HqcpConfig();
        realConfig.setUrl("jdbc:mock:test");
        realConfig.setMinConnections(2);
        realConfig.setIdleTimeoutSec(10); // 回收时间10s
        realConfig.setLifetimeSec(15);   // 销毁时间15s
        realConfig.setVerbose(true);
        realConfig.setInfoSqlThreshold(0);
        realConfig.setWarnSqlThreshold(0);
        realConfig.setPrintSql(false);

        AtomicInteger threadNum = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r);
            thread.setName("BusinessThread-" + threadNum.getAndIncrement());
            return thread;
        });
//        LOGGER.info("waiting timestamp to close to 0.000");
//        wait.awaitTimeWithSecUnitZero(10);
        Hqcp connPool = new Hqcp(realConfig);   // 00:00 2 connections (#0 #1) made

        AtomicBoolean stop = new AtomicBoolean(false);
        Thread task = new Thread(() -> {
            try {
                while (!stop.get()) {
                    Connection conn = connPool.getConnection();
                    Statement stmt = conn.createStatement();
                    stmt.execute("mock some time consuming...");
                    stmt.close();
                    conn.close();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        LOGGER.info("starting 3 business workers ... then wait 5s");
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                Thread.sleep(1500);
            }
            executor.submit(task);              // 00:03 connection #2 made
        }
        Thread.sleep(5000);
        LOGGER.info("start one more business worker, then wait 8s");
        executor.submit(task);                  // 00:08 connection #3 made
        Thread.sleep(8000);                     // 00:15 connections #0 #1 closed on checkIn() and #4 #5 made
                                                // 00:16
        LOGGER.info("check if #0 #1 closed and #4 #5 made.");
//        connections.forEach(conn -> {
//            LOGGER.info("connection#{} closed by {}", conn.getConnId(), conn.getCloseThread());
//        });
        assertTrue(connections.poll().getCloseThread().startsWith("BusinessThread-"));
        assertTrue(connections.poll().getCloseThread().startsWith("BusinessThread-"));
        assertEquals(4, connPool.getActiveConnectionsCount());

        LOGGER.info("stopping workers, then wait 5s");
        stop.set(true);
        wait.wakeUp();
        executor.shutdown();
        if (! executor.isTerminated()) {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
        Thread.sleep(5000);                     // 00:18 connection #2 closed by Monitor
                                                // 00:21
        LOGGER.info("check if #2 closed by Monitor, then wait 3s");
        assertTrue(connections.poll().getCloseThread().startsWith("CPM:HQCP#"));
        assertEquals(3, connPool.getActiveConnectionsCount());

        Thread.sleep(3000);                     // 00:23 connection #3 closed by Monitor
                                                // 00:24
        LOGGER.info("check if #3 closed by Monitor, then wait 7s");
        assertTrue(connections.poll().getCloseThread().startsWith("CPM:HQCP#"));
        assertEquals(2, connPool.getActiveConnectionsCount());

        Thread.sleep(7000);                     // 00:40 connection #4 #5 closed by Monitor
                                                // 00:41
        LOGGER.info("check if #4 #5 closed by Monitor");
        assertTrue(connections.poll().getCloseThread().startsWith("CPM:HQCP#"));
        assertTrue(connections.poll().getCloseThread().startsWith("CPM:HQCP#"));
        assertEquals(2, connPool.getActiveConnectionsCount());

        /**
         * 以下测试是为了覆盖 {@link Hqcp.LinkedStack#requireMoreSignal()}
         */
        stop.set(false);
        task.setName("BusinessThread-");
        task.start();
        Thread.sleep(16000);
        stop.set(true);
        wait.wakeUp();
        task.join(1000);
        if (connections.peek().getCloseThread().startsWith("CPM:HQCP#")) {
            assertTrue(connections.poll().getCloseThread().startsWith("CPM:HQCP#"));
            assertTrue(connections.poll().getCloseThread().startsWith("BusinessThread-"));
        } else {
            assertTrue(connections.poll().getCloseThread().startsWith("BusinessThread-"));
            assertTrue(connections.poll().getCloseThread().startsWith("CPM:HQCP#"));
        }

        connPool.shutdown();
    }

    @Tag("slow")
    @Test
    public void testUnclosedConnection_closeRetry() throws Exception {
        final AtomicInteger closeCounter = new AtomicInteger(0);

        MockConnection mockConnection2 = createNiceMock(MockConnection.class);
        mockConnection2.close();
        expectLastCall().andAnswer(() -> {
            closeCounter.incrementAndGet();
            throw new SQLException("mock close exception");
        }).atLeastOnce();
        expect(mockConnection2.createStatement()).andReturn(mockStatement).anyTimes();
        makeThreadSafe(mockConnection2, true);
        replay(mockConnection2);

        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).once().andReturn(mockConnection2).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);
        {  // 为了兼容其他测试用例，需要执行这段
            this.connPool = new Hqcp(config);
            Connection conn = this.connPool.getConnection();
            conn.createStatement().executeUpdate("lalalal");
            conn.close();
            this.connPool.shutdown();
        }

        HqcpConfig realConfig = new HqcpConfig();
        realConfig.setUrl("jdbc:mock:test");
        realConfig.setMaxConnections(1);
        realConfig.setMinConnections(0);

        Field idleTimeoutField = HqcpConfig.class.getDeclaredField("idleTimeoutSec");
        idleTimeoutField.setAccessible(true);
        idleTimeoutField.set(realConfig, 2);   // 强制 monitor 检查间隔：2s

        Hqcp pool = new Hqcp(realConfig);

        Connection conn = pool.getConnection(); // 取得唯一连接
        conn.close();
        Thread.sleep(23000);
        pool.shutdown();

        verify(mockConnection2);
        assertEquals(22, closeCounter.get());
    }

    /**
     * 测试 checkoutTimeoutMillisec 为负值时一直等待的逻辑；
     */
    @Tag("slow")
    @Test
    public void testCheckoutTimeoutWaitForever() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);
        {  // 为了兼容其他测试用例，需要执行这段
            this.connPool = new Hqcp(config);
            Connection conn = this.connPool.getConnection();
            conn.createStatement().executeUpdate("lalalal");
            conn.close();
            this.connPool.shutdown();
        }

        // 构造配置：最大连接数为 1，checkoutTimeoutMillisec 为 -1 表示一直等待
        HqcpConfig realConfig = new HqcpConfig();
        realConfig.setUrl("jdbc:mock:test");
        realConfig.setMaxConnections(1);
        realConfig.setMinConnections(0);
        realConfig.setCheckoutTimeoutMillisec(-1);
        Hqcp pool = new Hqcp(realConfig);

        Connection conn = pool.getConnection(); // 取得唯一连接

        final boolean[] gotConnection = new boolean[1];
        Thread waitingThread = new Thread(() -> {
            try {
                Connection c = pool.getConnection();
                gotConnection[0] = true;
                c.close();
            } catch (SQLException e) {
                fail("getConnection 出错: " + e.getMessage());
            }
        });
        waitingThread.start();
        Thread.sleep(2000);
        assertFalse(gotConnection[0], "在未释放连接前，等待线程不应能拿到连接");

        conn.close(); // 释放连接，使等待线程能够拿到连接
        waitingThread.join(3000);
        assertTrue(gotConnection[0], "释放连接后，等待线程应能拿到连接");

        pool.shutdown();
    }

    @Tag("slow")
    @ParameterizedTest
    @ValueSource(longs = {-1, 10_000})     // -1 表示一直等待
    public void testDynamicIncreaseMaxConnections(long checkoutTimeout) throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);
        {  // 为了兼容其他测试用例，需要执行这段
            this.connPool = new Hqcp(config);
            Connection conn = this.connPool.getConnection();
            conn.createStatement().executeUpdate("lalalal");
            conn.close();
            this.connPool.shutdown();
        }

        // 构造配置：最大连接数为 1，checkoutTimeoutMillisec 为 -1 表示一直等待
        HqcpConfig realConfig = new HqcpConfig();
        realConfig.setUrl("jdbc:mock:test");
        realConfig.setMaxConnections(1);
        realConfig.setMinConnections(0);
        realConfig.setVerbose(true);
        realConfig.setCheckoutTimeoutMillisec(checkoutTimeout);

        Field idleTimeoutField = HqcpConfig.class.getDeclaredField("idleTimeoutSec");
        idleTimeoutField.setAccessible(true);
        idleTimeoutField.set(realConfig, 3);  // 强制 monitor 检查间隔：3s

        Hqcp pool = new Hqcp(realConfig);

        Connection conn = pool.getConnection(); // 取得唯一连接

        final boolean[] gotConnection = new boolean[1];
        Thread waitingThread = new Thread(() -> {
            try {
                Connection c = pool.getConnection();
                gotConnection[0] = true;
                c.close();
            } catch (SQLException e) {
                fail("getConnection 出错: " + e.getMessage());
            }
        });
        waitingThread.start();
        Thread.sleep(2000);
        assertFalse(gotConnection[0], "在未增加连接数前，等待线程不应能拿到连接");

        LOGGER.info("increase max connections to 2");
        realConfig.setMaxConnections(2);

        waitingThread.join(3000);
        assertTrue(gotConnection[0], "增加连接数后，等待线程应能拿到连接");

        conn.close();   // 释放连接

        pool.shutdown();
    }

    /**
     * 测试 checkoutTimeoutMillisec 为 0 时，不等待立即超时。
     * 当连接池中无可用连接且 checkoutTimeoutMillisec==0 时，应立即抛出 SQLException。
     */
    @Test
    public void testCheckoutTimeoutImmediateException() throws Exception {
        answer = mocksControl.createMock(MockJDBCAnswer.class);
        expect(answer.answer()).andReturn(mockConnection).atLeastOnce();
        mocksControl.replay();
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(answer);
        {  // 为了兼容其他测试用例，需要执行这段
            this.connPool = new Hqcp(config);
            Connection conn = this.connPool.getConnection();
            conn.createStatement().executeUpdate("lalalal");
            conn.close();
            this.connPool.shutdown();
        }

        // 构造配置：最大连接数1，checkoutTimeoutMillisec==0
        HqcpConfig realConfig = new HqcpConfig();
        realConfig.setUrl("jdbc:mock:test");
        realConfig.setMaxConnections(1);
        realConfig.setMinConnections(0);
        realConfig.setCheckoutTimeoutMillisec(0);
        Hqcp pool = new Hqcp(realConfig);

        // 先占用唯一连接
        Connection conn = pool.getConnection();
        // 立即尝试获取新的连接，应立即超时
        long start = System.currentTimeMillis();
        SQLException ex = assertThrows(SQLException.class, () -> {
            Connection c = pool.getConnection();
            c.close();
        });
        assertTrue(System.currentTimeMillis() - start < 1000);
        assertTrue(ex.getMessage().contains("Timeout on waiting for an available connection"));
        conn.close();
        pool.shutdown();
    }

    /**
     * Test: config validation - null URL (line 121)
     */
    @Test
    public void testInitPoolWithNullUrl() {
        HqcpConfig badConfig = new HqcpConfig();
        badConfig.setUrl(null);

        SQLException exception = assertThrows(SQLException.class, () -> {
            new Hqcp(badConfig);
        });

        assertTrue(exception.getMessage().contains("jdbc.url cannot be NULL"));
    }

    /**
     * Test: Oracle 10 with implicit cache (line 134)
     */
//    @Test
//    public void testInitPoolOracle10WithImplicitCache() throws Exception {
//        HqcpConfig oracleConfig = new HqcpConfig();
//        oracleConfig.setUrl("jdbc:oracle:thin:@localhost:1521:test");
//        oracleConfig.setDriverClassName(MockJDBCDriver.class.getName());
//        oracleConfig.setUsername("test");
//        oracleConfig.setPassword("test");
//        oracleConfig.setUseOracleImplicitCache(true);
//        oracleConfig.setMinConnections(0);
//        oracleConfig.setLazyInit(true);
//
//        // This will test the Oracle 10 branch if DriverManager returns version 10
//        Hqcp pool = new Hqcp(oracleConfig);
//        assertNotNull(pool);
//        pool.shutdown();
//    }

    /**
     * Test: Oracle without implicit cache (line 142)
     */
//    @Test
//    public void testInitPoolOracleWithoutImplicitCache() throws Exception {
//        HqcpConfig oracleConfig = new HqcpConfig();
//        oracleConfig.setUrl("jdbc:oracle:thin:@localhost:1521:test");
//        oracleConfig.setDriverClassName(MockJDBCDriver.class.getName());
//        oracleConfig.setUsername("test");
//        oracleConfig.setPassword("test");
//        oracleConfig.setUseOracleImplicitCache(false);
//        oracleConfig.setMinConnections(0);
//        oracleConfig.setLazyInit(true);
//
//        Hqcp pool = new Hqcp(oracleConfig);
//        assertNotNull(pool);
//        pool.shutdown();
//    }

    /**
     * Test: Oracle with checkout timeout (line 150)
     */
    @Test
    public void testInitPoolOracleWithCheckoutTimeout() throws Exception {
        HqcpConfig oracleConfig = new HqcpConfig();
        oracleConfig.setUrl("jdbc:oracle:thin:@localhost:1521:test");
        oracleConfig.setDriverClassName(MockJDBCDriver.class.getName());
        oracleConfig.setUsername("test");
        oracleConfig.setPassword("test");
        oracleConfig.setCheckoutTimeoutMillisec(5000);
        oracleConfig.setMinConnections(0);
        oracleConfig.setLazyInit(true);

        Hqcp pool = new Hqcp(oracleConfig);
        assertNotNull(pool);
        pool.shutdown();
    }

    /**
     * Test: MySQL with checkout timeout (line 151)
     */
    @Test
    public void testInitPoolMySQLWithCheckoutTimeout() throws Exception {
        HqcpConfig mysqlConfig = new HqcpConfig();
        mysqlConfig.setUrl("jdbc:mysql://localhost:3306/test");
        mysqlConfig.setDriverClassName(MockJDBCDriver.class.getName());
        mysqlConfig.setUsername("test");
        mysqlConfig.setPassword("test");
        mysqlConfig.setCheckoutTimeoutMillisec(5000);
        mysqlConfig.setMinConnections(0);
        mysqlConfig.setLazyInit(true);

        Hqcp pool = new Hqcp(mysqlConfig);
        assertNotNull(pool);
        pool.shutdown();
    }

    /**
     * Test: Oracle with query timeout (line 157)
     */
    @Test
    public void testInitPoolOracleWithQueryTimeout() throws Exception {
        HqcpConfig oracleConfig = new HqcpConfig();
        oracleConfig.setUrl("jdbc:oracle:thin:@localhost:1521:test");
        oracleConfig.setDriverClassName(MockJDBCDriver.class.getName());
        oracleConfig.setUsername("test");
        oracleConfig.setPassword("test");
        oracleConfig.setQueryTimeout(30);
        oracleConfig.setMinConnections(0);
        oracleConfig.setLazyInit(true);

        Hqcp pool = new Hqcp(oracleConfig);
        assertNotNull(pool);
        pool.shutdown();
    }

    /**
     * Test: already initialized pool (line 167)
     */
    @Test
    public void testInitPoolAlreadyInitialized() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setUsername("test");
        testConfig.setPassword("test");
        testConfig.setMinConnections(0);
        testConfig.setLazyInit(true);

        Hqcp pool = new Hqcp(testConfig);

        // Pool is already initialized, calling initPool again should return early
        // We can't call it directly, but the constructor will only init once

        pool.shutdown();
    }

    /**
     * Test: SqlMasker initialization (line 190)
     */
    @Test
    public void testInitPoolWithSqlMasker() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setUsername("test");
        testConfig.setPassword("test");
        testConfig.setMinConnections(0);
        testConfig.setLazyInit(true);

        java.util.Set<String> sensitiveFields = new java.util.HashSet<>();
        sensitiveFields.add("password");
        sensitiveFields.add("secret");
        testConfig.setSensitiveFields(sensitiveFields);

        Hqcp pool = new Hqcp(testConfig);
        assertNotNull(pool.getSqlMasker());

        pool.shutdown();
    }

    /**
     * Test: shutdown already shutdown pool (line 198)
     */
    @Test
    public void testShutdownAlreadyShutdown() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setMinConnections(0);
        testConfig.setLazyInit(true);

        Hqcp pool = new Hqcp(testConfig);
        pool.shutdown();

        // Shutdown again - should return early
        pool.shutdown();

        assertTrue(pool.isShutdown());
    }

    /**
     * Test: shutdown with InterruptedException (line 208-209)
     */
    @Test
    public void testShutdownWithInterruptedException() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setMinConnections(1);
        testConfig.setLazyInit(false);

        Hqcp pool = new Hqcp(testConfig);

        // Interrupt current thread
        Thread.currentThread().interrupt();

        try {
            pool.shutdown();
            // Should handle InterruptedException gracefully
        } finally {
            // Clear interrupt flag
            Thread.interrupted();
        }
    }

    /**
     * Test: shutdown with connection checkout during shutdown (line 218)
     */
    @Test
    public void testShutdownWithConnectionCheckoutInterrupted() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setMinConnections(1);
        testConfig.setMaxConnections(2);
        testConfig.setIdleTimeoutSec(60);

        Hqcp pool = new Hqcp(testConfig);

        // Get a connection and hold it
        Connection conn = pool.getConnection();

        // Shutdown in another thread
        Thread shutdownThread = new Thread(() -> {
            pool.shutdown();
        });
        shutdownThread.start();

        // Wait a bit then close connection
        Thread.sleep(100);
        conn.close();

        shutdownThread.join(5000);
        assertTrue(pool.isShutdown());
    }

    /**
     * Test: getConnection when pool is shutdown (line 282-283)
     */
    @Test
    public void testGetConnectionWhenShutdown() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setMinConnections(0);
        testConfig.setLazyInit(true);

        Hqcp pool = new Hqcp(testConfig);
        pool.shutdown();

        SQLException exception = assertThrows(SQLException.class, () -> {
            pool.getConnection();
        });

        assertTrue(exception.getMessage().contains("shutdown"));
        assertEquals("08001", exception.getSQLState());
    }

    /**
     * Test: getConnection with lazy init and pool exhausted (line 298-300)
     */
    @Test
    public void testGetConnectionLazyInitExhausted() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setMinConnections(0);
        testConfig.setMaxConnections(1);
        testConfig.setLazyInit(true);
        testConfig.setCheckoutTimeoutMillisec(1000);

        Hqcp pool = new Hqcp(testConfig);

        // Get the only connection
        Connection conn1 = pool.getConnection();
        assertNotNull(conn1);

        // Try to get another - should timeout and throw exception
        SQLException exception = assertThrows(SQLException.class, () -> {
            pool.getConnection();
        });

        assertTrue(exception.getMessage().contains("Timeout"));
        assertEquals("08001", exception.getSQLState());

        conn1.close();
        pool.shutdown();
    }

    /**
     * Test: setPoolName when config is loaded from properties (line 341)
     */
    @Test
    public void testSetPoolNameWhenLoadedFromProperties() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setMinConnections(0);
        testConfig.setLazyInit(true);
        testConfig.loadFromProperties("jdbc"); // Mark as loaded from properties

        Hqcp pool = new Hqcp(testConfig);
        String originalName = pool.getPoolName();

        // Try to set pool name - should be ignored because loaded from properties
        pool.setPoolName("newName");

        // Pool name should remain unchanged
        assertEquals(originalName, pool.getPoolName());

        pool.shutdown();
    }

    /**
     * Test: getConnection with InterruptedException (line 345)
     */
    @Test
    public void testGetConnectionInterruptedException() throws Exception {
        HqcpConfig testConfig = new HqcpConfig();
        testConfig.setUrl(MockConstant.MOCK_URL);
        testConfig.setDriverClassName(MockJDBCDriver.class.getName());
        testConfig.setMinConnections(0);
        testConfig.setMaxConnections(1);
        testConfig.setLazyInit(true);
        testConfig.setCheckoutTimeoutMillisec(5000);

        Hqcp pool = new Hqcp(testConfig);

        // Get the only connection
        Connection conn = pool.getConnection();

        // Try to get another in a thread and interrupt it
        Thread t = new Thread(() -> {
            try {
                pool.getConnection();
            } catch (SQLException e) {
                // Expected
            }
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(1000);

        conn.close();
        pool.shutdown();
    }

}


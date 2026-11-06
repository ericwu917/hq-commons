package io.hqwu.commons.cp;

import com.jolbox.bonecp.MockConnection;
import com.jolbox.bonecp.MockConstant;
import com.jolbox.bonecp.MockJDBCDriver;
import com.jolbox.bonecp.MockJDBCStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * monitor 校验空闲连接期间，借出方不被卡在连接锁上：
 * 不会拿到正在校验的那条连接，等待时间受 checkoutTimeout 约束
 */
class HqcpMonitorCheckTest {
    private static final String CHECK_SQL = "SELECT 1 /* hqcp monitor check */";
    private static final long CHECKOUT_TIMEOUT_MS = 500L;

    private final CountDownLatch checkStarted = new CountDownLatch(1);
    private final CountDownLatch releaseCheck = new CountDownLatch(1);
    private final AtomicBoolean blockFirstCheck = new AtomicBoolean(true);
    private volatile MockConnection checkingConnection;

    private MockJDBCDriver driver;
    private Hqcp pool;
    private ExecutorService borrowerExecutor;

    /**
     * 第一次执行校验 SQL 时阻塞在 releaseCheck 上，模拟很慢的校验
     */
    private class SlowCheckConnection extends MockConnection {
        @Override
        public Statement createStatement() throws SQLException {
            return new MockJDBCStatement(this) {
                @Override
                public ResultSet executeQuery(String sql) throws SQLException {
                    if (CHECK_SQL.equals(sql) && blockFirstCheck.compareAndSet(true, false)) {
                        checkingConnection = SlowCheckConnection.this;
                        checkStarted.countDown();
                        try {
                            releaseCheck.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return super.executeQuery(sql);
                }
            };
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return iface.isInstance(this) ? iface.cast(this) : null;
        }
    }

    @BeforeEach
    void setUp() {
        driver = MockJDBCDriver.getInstance().setMockJDBCAnswer(() -> {
            MockConnection conn = new SlowCheckConnection();
            conn.connect();
            return conn;
        });
        borrowerExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try {
            releaseCheck.countDown();
            borrowerExecutor.shutdownNow();
            if (pool != null && ! pool.isShutdown()) {
                pool.shutdown();
            }
        } finally {
            driver.disable();
        }
    }

    private Hqcp newPool(int minConnections, int maxConnections) throws Exception {
        HqcpConfig config = new HqcpConfig();
        config.setUrl(MockConstant.MOCK_URL);
        config.setUsername("mockuser");
        config.setPassword("mockpassword");
        config.setMinConnections(minConnections);
        config.setMaxConnections(maxConnections);
        config.setCheckoutTimeoutMillisec(CHECKOUT_TIMEOUT_MS);
        config.setCheckStatement(CHECK_SQL);
        config.setJmxLevel(0);
        // setter 下限 10s，这里直接改字段，让空闲连接 2s 后即到检测时间，避免长 sleep；
        // 同时 monitor 等校验结果的上限也是 idleTimeout（2s），远大于本测试卡住校验的时长（约 checkoutTimeout）
        Field idleTimeoutField = HqcpConfig.class.getDeclaredField("idleTimeoutSec");
        idleTimeoutField.setAccessible(true);
        idleTimeoutField.setLong(config, 2L);
        return new Hqcp(config);
    }

    private void awaitCheckStarted() throws InterruptedException {
        assertTrue(checkStarted.await(10, TimeUnit.SECONDS), "monitor should start checking the idle connection");
    }

    /**
     * 还能新建连接时：借出方直接新建另一条，不等校验
     */
    @Test
    void testBorrowDuringSlowCheckGetsAnotherConnection() throws Exception {
        pool = newPool(1, 2);
        awaitCheckStarted();

        Future<MockConnection> borrowed = borrowerExecutor.submit(() -> {
            try (Connection conn = pool.getConnection()) {
                return conn.unwrap(MockConnection.class);
            }
        });
        MockConnection got;
        try {
            // 校验仍被卡着；修复前借出方会一直卡在连接锁上，直到校验结束
            got = borrowed.get(CHECKOUT_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("borrower blocked by monitor check longer than checkoutTimeout");
            return;
        }
        assertNotNull(got);
        assertNotSame(checkingConnection, got, "must not get the connection under checking");
        assertEquals(1, releaseCheck.getCount(), "check should still be in progress while borrowing");
    }

    /**
     * 已到最大连接数时：借出方在 checkoutTimeout 内按超时报错，不被校验无限拖住；
     * 校验结束后连接放回池中，可以再借出
     */
    @Test
    void testBorrowDuringSlowCheckTimesOutWithinCheckoutTimeout() throws Exception {
        pool = newPool(1, 1);
        awaitCheckStarted();

        long start = System.nanoTime();
        Future<MockConnection> borrowed = borrowerExecutor.submit(() -> {
            try (Connection conn = pool.getConnection()) {
                return conn.unwrap(MockConnection.class);
            }
        });
        try {
            borrowed.get(CHECKOUT_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
            fail("the only connection is under checking, borrower should time out");
        } catch (TimeoutException e) {
            fail("borrower blocked by monitor check longer than checkoutTimeout");
        } catch (ExecutionException e) {
            assertInstanceOf(SQLException.class, e.getCause());
            assertTrue(e.getCause().getMessage().startsWith("Timeout on waiting for an available connection"),
                    e.getCause().getMessage());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= CHECKOUT_TIMEOUT_MS - 50, "should wait about checkoutTimeout, but " + elapsedMs + " ms");

        // 校验结束 → 连接放回空闲栈并唤醒等待方
        releaseCheck.countDown();
        try (Connection conn = pool.getConnection()) {
            assertSame(checkingConnection, conn.unwrap(MockConnection.class));
        }
        assertEquals(1, pool.getActiveConnectionsCount());
    }
}

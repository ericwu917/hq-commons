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
    private static final long CHECKOUT_TIMEOUT_MS = 200L;
    /**
     * 测试 monitor 等校验结果的上限（idleTimeout，同时也是空闲连接到检测时间的间隔）
     */
    private static final long IDLE_TIMEOUT_SEC = 2L;
    /**
     * 等借出方返回的上限：修复前借出方会被校验卡住（直到 releaseCheck，最长 10s），修复后远小于这个值
     */
    private static final long BORROW_WAIT_MS = 1000L;

    private final CountDownLatch checkStarted = new CountDownLatch(1);
    private final CountDownLatch releaseCheck = new CountDownLatch(1);
    private final AtomicBoolean blockFirstCheck = new AtomicBoolean(true);
    private volatile MockConnection checkingConnection;
    /**
     * 校验 SQL 已进入、尚未放行
     */
    private volatile boolean checkInProgress;
    private volatile long checkStartNanos;
    private volatile long checkEndNanos;

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
                        checkStartNanos = System.nanoTime();
                        checkInProgress = true;
                        checkStarted.countDown();
                        try {
                            releaseCheck.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            checkInProgress = false;
                            checkEndNanos = System.nanoTime();
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
        return newPool(minConnections, maxConnections, false);
    }

    private Hqcp newPool(int minConnections, int maxConnections, boolean lazyInit) throws Exception {
        HqcpConfig config = new HqcpConfig();
        config.setLazyInit(lazyInit);
        config.setUrl(MockConstant.MOCK_URL);
        config.setUsername("mockuser");
        config.setPassword("mockpassword");
        config.setMinConnections(minConnections);
        config.setMaxConnections(maxConnections);
        config.setCheckoutTimeoutMillisec(CHECKOUT_TIMEOUT_MS);
        config.setCheckStatement(CHECK_SQL);
        config.setJmxLevel(0);
        // setter 下限 10s，这里直接改字段，让空闲连接 2s 后即到检测时间，避免长 sleep。
        // monitor 等校验结果的上限也是 idleTimeout（2s）：超过它校验会被判失败、连接被关闭。
        // 按超时报错的两个用例卡住校验的窗口约为 checkoutTimeout（200ms）
        // 加调度开销，本机实测约 0.2s，余量约 1.8s（窗口的 8 倍以上）
        Field idleTimeoutField = HqcpConfig.class.getDeclaredField("idleTimeoutSec");
        idleTimeoutField.setAccessible(true);
        idleTimeoutField.setLong(config, IDLE_TIMEOUT_SEC);
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
            got = borrowed.get(BORROW_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("borrower blocked by monitor check longer than " + BORROW_WAIT_MS + " ms");
            return;
        }
        assertNotNull(got);
        assertNotSame(checkingConnection, got, "must not get the connection under checking");
        // 由校验 SQL 侧记录：借出方返回时，校验 SQL 仍在执行、未被放行
        assertTrue(checkInProgress, "check should still be in progress while borrowing");
    }

    /**
     * 已到最大连接数时：借出方在 checkoutTimeout 内按超时报错，不被校验无限拖住；
     * 校验结束后连接放回池中，可以再借出
     */
    @Test
    void testBorrowDuringSlowCheckTimesOutWithinCheckoutTimeout() throws Exception {
        pool = newPool(1, 1);
        awaitCheckStarted();

        assertBorrowTimesOutWithinCheckoutTimeout();
        releaseCheckAndBorrowCheckedConnection();
        assertEquals(1, pool.getActiveConnectionsCount());
    }

    /**
     * lazyInit=true 时 getConnection 自己不建连、要靠 monitor 建，而 monitor 正等校验结果：
     * 池未满也按 checkoutTimeout 超时报错（不再像修复前那样卡到校验结束）
     */
    @Test
    void testLazyInitBorrowDuringSlowCheckTimesOutEvenIfPoolNotFull() throws Exception {
        pool = newPool(1, 2, true);
        awaitCheckStarted();
        assertEquals(1, pool.getActiveConnectionsCount());

        assertBorrowTimesOutWithinCheckoutTimeout();
        // 池未满（1/2），超时是因为借出方不自己建连、monitor 又在等校验
        assertEquals(1, pool.getActiveConnectionsCount());
        releaseCheckAndBorrowCheckedConnection();
    }

    /**
     * 借出方在约 checkoutTimeout 内按超时报错，且报错时校验仍在进行
     */
    private void assertBorrowTimesOutWithinCheckoutTimeout() throws Exception {
        long start = System.nanoTime();
        Future<MockConnection> borrowed = borrowerExecutor.submit(() -> {
            try (Connection conn = pool.getConnection()) {
                return conn.unwrap(MockConnection.class);
            }
        });
        try {
            borrowed.get(BORROW_WAIT_MS, TimeUnit.MILLISECONDS);
            fail("the only idle connection is under checking, borrower should time out");
        } catch (TimeoutException e) {
            fail("borrower blocked by monitor check longer than " + BORROW_WAIT_MS + " ms");
        } catch (ExecutionException e) {
            assertInstanceOf(SQLException.class, e.getCause());
            assertTrue(e.getCause().getMessage().startsWith("Timeout on waiting for an available connection"),
                    e.getCause().getMessage());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= CHECKOUT_TIMEOUT_MS - 50, "should wait about checkoutTimeout, but " + elapsedMs + " ms");
        assertTrue(checkInProgress, "check should still be in progress when borrower times out");
    }

    /**
     * 放行校验 → 连接放回空闲栈并唤醒等待方，再借出拿到的就是刚校验完的那条
     */
    private void releaseCheckAndBorrowCheckedConnection() throws SQLException {
        releaseCheck.countDown();
        try (Connection conn = pool.getConnection()) {
            // 校验被卡住的窗口须短于 idleTimeout，否则 monitor 判校验失败并关闭连接，这里会拿到重建的物理连接
            long windowMs = TimeUnit.NANOSECONDS.toMillis(checkEndNanos - checkStartNanos);
            assertSame(checkingConnection, conn.unwrap(MockConnection.class),
                    "check window " + windowMs + " ms, limit " + IDLE_TIMEOUT_SEC * 1000 + " ms");
        }
    }
}

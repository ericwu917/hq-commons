package io.hqwu.commons.cp;


import io.hqwu.commons.cp.dialect.DB2PooledConnection;
import io.hqwu.commons.cp.dialect.MySQLPooledConnection;
import io.hqwu.commons.cp.dialect.OceanBasePooledConnection;
import io.hqwu.commons.cp.dialect.OraclePooledConnection;
import io.hqwu.commons.cp.util.DynamicSemaphore;
import io.hqwu.commons.cp.util.LogUtil;
import io.hqwu.commons.cp.util.OracleUtil;
import io.hqwu.commons.cp.util.SqlMasker;
import io.hqwu.commons.util.Formatter;
import io.hqwu.commons.util.JMXUtil;
import io.hqwu.commons.util.Logger;
import lombok.Getter;

import java.lang.reflect.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hqcp (HQ Connection Pool) 是一个高性能的数据库连接池实现。
 *
 * <p>主要功能包括：</p>
 * <ul>
 *     <li>支持多种数据库方言，包括 Oracle、MySQL、DB2 和 OceanBase。</li>
 *     <li>基于 {@link HqcpConfig} 进行灵活的连接参数与池化策略配置。</li>
 *     <li>提供完善的连接生命周期管理，包括空闲连接检测、存活检查及自动回收机制。</li>
 *     <li>支持通过 JMX ({@link HqcpMBean}) 进行实时监控与动态管理。</li>
 *     <li>集成 {@link SqlMasker} 提供 SQL 敏感字段脱敏功能。</li>
 * </ul>
 *
 * @author wuhongqiang
 * @since 2011-09-02
 */
public class Hqcp implements HqcpMBean {
    private static final Logger LOGGER = new Logger();

    private static final AtomicInteger POOL_ID = new AtomicInteger(0);

    /**
     * pool id
     */
    @Getter
    private final int poolId;
    
    /**
     * 连接池名称
     * (跟配置文件名一致，即jdbc.properties的名称是jdbc)
     */
    @Getter
    private String poolName;

    /**
     * 配置
     */
    @Getter
    private final HqcpConfig config;
    
    /**
     * 连接编号
     */
    private final AtomicInteger connectionNo = new AtomicInteger(0);
    
    /**
     * 池中的总连接数（占用+空闲）
     */
    private final AtomicInteger validConnectionNum = new AtomicInteger(0);
    /**
     * 池中存活的全部连接（占用+空闲）
     */
    private final Map<Integer, PooledConnection> validConnectionsPool = new ConcurrentHashMap<Integer, PooledConnection>();
    
    /**
     * 空闲连接Id号
     * 数据结构：堆栈
     *      最近使用的连接在堆栈的顶部，而最久未使用的连接在堆栈的底部
     */
    private final LinkedStack<Integer> idleConnectionsId = new LinkedStack<Integer>();
    
    /**
     * 关闭标志
     */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * 初始化标志
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * monitor thread
     */
    private Thread monitor;

    /**
     * 保存未实际关闭的raw连接（游离在池外的连接），用于定期尝试close
     */
    private final BlockingQueue<UnclosedConnection> unclosedConnections = new LinkedBlockingQueue<UnclosedConnection>();

    /**
     * 使用 Semaphore 控制最大连接数
     */
    private final DynamicSemaphore maxConnectionSemaphore;

    @Getter
    private volatile SqlMasker sqlMasker;

    Hqcp(String poolName) throws SQLException {
        this.config = new HqcpConfig();
        this.config.loadFromProperties(poolName);
        this.poolId = POOL_ID.getAndIncrement();
        this.poolName = poolName;
        this.maxConnectionSemaphore = new DynamicSemaphore(config.getMaxConnections());
        initPool();
    }
    
    public Hqcp(HqcpConfig config) throws SQLException {
        this.config = config;
        this.poolId = POOL_ID.getAndIncrement();
        this.poolName = "HQCP#" + this.poolId;
        this.maxConnectionSemaphore = new DynamicSemaphore(config.getMaxConnections());
        initPool();
    }

    /**
     * 初始化连接池
     * @author wuhongqiang 2014.2.25
     * change the method name from startMonitor to initPool
     * @throws SQLException SQLException
     */
    private void initPool() throws SQLException {
        if (this.config == null || this.config.getUrl() == null) {
            throw new SQLException("jdbc.url cannot be NULL");
        }
        // 先于建连接等任何副作用构造：敏感字段后缀不合法时在这里抛 IllegalArgumentException，不会泄漏已建的连接
        if (config.getSensitiveFields() != null && !config.getSensitiveFields().isEmpty()) {
            this.sqlMasker = new SqlMasker(config.getMaskPattern(), config.getSensitiveFields(),
                    config.getMaskKeepHead(), config.getMaskKeepTail(), config.isSensitiveFieldsExactMatch());
        }
        if (config.getDriverClassName() != null) {
            try {
                Class.forName(config.getDriverClassName());
                LOGGER.info("load ", config.getDriverClassName(), " ok");
            } catch (ClassNotFoundException e) {
                throw new SQLException("Failed to load driver class: " + config.getDriverClassName(), e);
            }
        } else {
            try {
                DriverManager.getDriver(config.getUrl());
            } catch (SQLException e) {
                throw new SQLException("No suitable driver found for " + config.getUrl() +
                        ". Please configure 'driverClassName' or add driver jar to classpath.", e);
            }
        }
        config.printConfig(LOGGER);
        // 在建连接、起 monitor 线程之前触发口令解密：PasswordDecryptor 的异常从这里抛给初始化调用方，
        // 不会落到 monitor 线程里被 catch 吞掉
        config.getPassword();
        // 2025-03-04: 升级到 ojdbc17+ 后，不再需要针对 Oracle 10 的特殊缓存配置。
        // boolean isOracle10 = config.isOracle() && DriverManager.getDriver(config.getUrl()).getMajorVersion() == 10;
        // if (isOracle10 && config.isUseOracleImplicitCache()) {
        //     config.getConnectionProperties().setProperty(OracleUtil.ORACLE_FREECACHE_PROPERTY_NAME, OracleUtil.ORACLE_FREECACHE_PROPERTY_VALUE_TRUE);
        // } else {
        //     config.getConnectionProperties().remove(OracleUtil.ORACLE_FREECACHE_PROPERTY_NAME);
        // }
        //设置native驱动的超时设置
        //add by wuhongqiang. 2014.07.04
        if (config.getCheckoutTimeoutMillisec() > 0) {
            if (config.isOracle()) {
                config.getConnectionProperties().setProperty(OracleUtil.CONNECT_TIMEOUT, String.valueOf(config.getCheckoutTimeoutMillisec()));
            } else if (config.isMySQL() || config.isOceanBase()) {
                config.getConnectionProperties().setProperty(MySQLPooledConnection.CONNECT_TIMEOUT, String.valueOf(config.getCheckoutTimeoutMillisec()));
            }

        }
        if (config.getQueryTimeout() > 0) {
            if (config.isOracle()) {
                config.getConnectionProperties().setProperty(OracleUtil.SOCKET_TIMEOUT, String.valueOf(config.getQueryTimeout()*1000));
                config.getConnectionProperties().setProperty(OracleUtil.SOCKET_TIMEOUT_LOW_VER, String.valueOf(config.getQueryTimeout()*1000));
            } else if (config.isMySQL() || config.isOceanBase()) {
                config.getConnectionProperties().setProperty(MySQLPooledConnection.SOCKET_TIMEOUT, String.valueOf(config.getQueryTimeout()*1000));
            }
        }
        if (initialized.getAndSet(true)) {
            return;
        }
        if (! config.isLazyInit()) {
            //尝试建立一条连接
            if (validConnectionNum.get() < config.getMinConnections()) {
                newConnection(false);
            }
        }

        monitor = new CPMonitor();
        monitor.setName("CPM:" + poolName);
        monitor.setDaemon(true);
        monitor.start();
        try {
            /*
             * 等待 monitor 线程启动并尝试建立初始连接，确保 initPool 返回时连接池已准备就绪。
             * FIX lazyInit=true & minConn=0 时，主线程超前于Monitor线程时无法获取连接的BUG
             */
            long nanos = idleConnectionsId.awaitRequireMore(1, TimeUnit.SECONDS);
            if (nanos <= 0) {
                LOGGER.info("wait Monitor initialized TIMEOUT!!");
            }
        } catch (InterruptedException ignored) {
        }

        if (config.getJmxLevel() > 0) {
            try {
                JMXUtil.register(this.getClass().getPackage().getName() + ":type=pool-" + poolName, this);
                JMXUtil.register(this.getClass().getPackage().getName() + ":type=pool-" + poolName + ",name=config", config);
            } catch (Exception e) {
                LOGGER.warn("Failed to register JMX MBeans for pool " + poolName + ": " + e.getMessage());
            }
        }
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * 关闭连接池
     */
    public void shutdown() {
        if (shutdown.getAndSet(true)) {
            return;
        }
        ConnectionFactory.remove(poolName);
        // 停止维护/监控线程
        if (monitor != null) {
            monitor.interrupt();
            try {
                monitor.join();
            } catch (InterruptedException ignored) {
            }
        }
        // 释放所有连接
        for (int i = 0; i < 10; i++) { //最多尝试10次，超过之后强制关闭
            Integer[] connIds = idleConnectionsId.toArray(Integer.class);
            for (Integer connId: connIds) {
                PooledConnection pc = validConnectionsPool.get(connId);
                try {
                    pc.lock(); //锁住连接，不允许checkout
                } catch (InterruptedException e) {
                    continue;
                }
                try {
                    if (pc.isCheckOut()) {
                        //已经checkout，则停止本次释放
                        break;
                    }
                    if (! removeConnection(pc, true)) {
                        //连接栈底部不是当前连接，说明连接正等待被检出，停止本次释放
                        LOGGER.debug("connection ", pc.getConnectionName(), " is checking out, skip removing for next round");
                        break;
                    }
                } finally {
                    pc.unlock();
                }
            }
            if (validConnectionNum.get() <= 0) {
                break;
            }
            // 如果还有连接，打印信息，并等待1秒
            logVerboseInfo(true);
            idleConnectionsId.awaitNotEmpty(1, TimeUnit.SECONDS);
        }
        // ！！强制！！关闭所有连接
        for (Map.Entry<Integer, PooledConnection> e: validConnectionsPool.entrySet()) {
            PooledConnection pc = e.getValue();
            if (pc.isCheckOut()) {
                LOGGER.info("force closing ... ", pc.getConnectionName(), " checkout by ", pc.getThreadCheckOut().getName(),
                        " for " + pc.getCheckOutTime() + " ms[", (pc.isBusying() ? "BUSYING" : "IDLE"), "]");
            }
            pc.close();
            validConnectionNum.decrementAndGet();
            maxConnectionSemaphore.release();
            pc.unregisterJMX();
        }
        validConnectionsPool.clear();
        try {
            JMXUtil.unregister(this.getClass().getPackage().getName() + ":type=pool-" + poolName);
            JMXUtil.unregister(this.getClass().getPackage().getName() + ":type=pool-" + poolName + ",name=config");
        } catch (Exception ignored) {}
    }

    /**
     * 从池中poll连接 <br/>
     * <b>注意：autoCommit的值决定于transaction-mode的配置(transaction-mode默认false，即autoCommit默认true)</b>
     * @return 包装的Connection
     * @throws java.sql.SQLException SQLException
     */
    public Connection getConnection() throws SQLException {
        return getConnection(! config.isTransactionMode());
    }

    /**
     * 从池中poll连接
     * @param autoCommit autoCommit
     * @return 包装的Connection
     * @throws java.sql.SQLException SQLException
     */
    public Connection getConnection(boolean autoCommit) throws SQLException {
        if (shutdown.get()) {
            throw new SQLException("connection pool is shutdown", "08001");
        }
        long start = System.nanoTime();
        Integer connId = idleConnectionsId.pop();
        if (connId == null && ! config.isLazyInit()) {  // 不是lazy模式，则尝试创建连接（否则由monitor线程创建连接）
            connId = newConnection(true);
        }
        if (connId == null) {
            if (validConnectionNum.get() >= config.getMaxConnections()) {
                // 在连接池耗尽时，日志告警 todo 可配置日志级别
                LOGGER.info("connections of {} to {} exhausted, wait {} ms for idle connection", poolName, config.getUrl(),
                        config.getCheckoutTimeoutMillisec() < 0 ? "INFINITE" : config.getCheckoutTimeoutMillisec());
            }
            try {
                connId = idleConnectionsId.pop(config.getCheckoutTimeoutMillisec(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOGGER.info(e);
            }
        }
        if (connId == null) {
            // 在连接池耗尽 & 等待超时，打印连接池的状态
            logVerboseInfo(true);
            throw new SQLException("Timeout on waiting for an available connection of " + poolName + " to " + config.getUrl(), "08001");
        }
        PooledConnection pconn = validConnectionsPool.get(connId);
        try {
            Connection conn = pconn.checkOut(autoCommit);
            if (config.isVerbose() || config.isPrintSql()) {
                LOGGER.debug(pconn.getConnectionName(), ".getConnection(", autoCommit, "), use ", Formatter.formatNS(System.nanoTime() - start), " ns");
            }
            return conn;
        } catch (SQLException e) {
            checkIn(pconn);
            throw e;
        }
    }
    
    void checkIn(PooledConnection pconn) {
        int connId = pconn.getConnectionId();
        if (config.getLifetimeSec() > 0 && pconn.millisToDestroy() <= 0) {
            // destroy the connection
            removeConnection(pconn, false);
            if (validConnectionNum.get() < config.getMinConnections()) {
                idleConnectionsId.requireMoreSignal();
            }
        } else {
            idleConnectionsId.push(connId);
        }
    }

    /**
     * new one pooled connection
     * @param directReturn true - 直接返回ConnectionId，false - 入池，返回null
     * @return ConnectionId
     * @throws SQLException SQLException
     */
    private Integer newConnection(boolean directReturn) throws SQLException {
        // 尝试获取Semaphore许可，如果失败表示已达最大连接数，直接返回null
        if (! maxConnectionSemaphore.tryAcquire()) {
            return null;
        }
        //当前连接数 < 最大连接数，则创建连接
        int connId = connectionNo.getAndIncrement();
        try {
            PooledConnection pconn;
            if (config.isOracle()) {
                pconn = new OraclePooledConnection(Hqcp.this, connId);
            } else if (config.isMySQL()) {
                pconn = new MySQLPooledConnection(Hqcp.this, connId);
            } else if (config.isDB2()) {
                pconn = new DB2PooledConnection(Hqcp.this, connId);
            } else if (config.isOceanBase()) {
                pconn = new OceanBasePooledConnection(Hqcp.this, connId);
            } else {
                pconn = new PooledConnection(Hqcp.this, connId);
            }
            validConnectionNum.incrementAndGet();
            if (config.isVerbose()) {
                LOGGER.info(poolName, " +)", validConnectionNum.get(), " connections to ", config.getUrl());
            }
            validConnectionsPool.put(connId, pconn);
            if (directReturn) {
                return connId;
            } else {
                idleConnectionsId.push(connId);
            }
        } catch (Throwable e) {
            // 创建连接失败时，必须释放之前占用的Semaphore许可，保证计数一致
            maxConnectionSemaphore.release();
            throw e;
        }
        return null;
    }

    /**
     * destroy the pooled connection
     * @param pc PooledConnection
     * @param isIdleConn <br/>
     *      true  - the connection is idle connection, which must be at the bottom of the pool stack, else stop removing <br/>
     *      false - the connection is waiting for checkin, which ignoring if it is at the bottom of the pool stack
     * @return if remove success
     */
    private boolean removeConnection(PooledConnection pc, boolean isIdleConn) {
        // 移除连接栈底部的<b>空闲</b>连接
        if (isIdleConn && ! idleConnectionsId.popFromBottom(pc.getConnectionId())) {
            //连接栈底部不是当前连接
            //说明连接正等待被检出
            //则停止销毁
            return false;
        }
        validConnectionNum.decrementAndGet();
        validConnectionsPool.remove(pc.getConnectionId());
        // 销毁连接后释放Semaphore许可
        maxConnectionSemaphore.release();
        pc.close();
        pc.unregisterJMX();
        if (config.isVerbose()) {
            LOGGER.info(poolName, " -)", validConnectionNum.get() ," connections to ", config.getUrl());
        }
        return true;
    }

    public long getInfoSQLThreshold() {
        return config.getInfoSqlThreshold() <= 0 ? Long.MAX_VALUE : config.getInfoSqlThreshold();
    }

    public long getWarnSQLThreshold() {
        return config.getWarnSqlThreshold() <= 0 ? Long.MAX_VALUE : config.getWarnSqlThreshold();
    }

    /**
     * log连接的状态信息
     */
    private void logVerboseInfo(boolean verbose) {
        //显示当前活动连接的状态
        int i = 0;
        for (Map.Entry<Integer, PooledConnection> e: validConnectionsPool.entrySet()) {
            PooledConnection pc = e.getValue();
            if (pc.isCheckOut()) {
                i++;
                long usedMS = pc.getCheckOutTime();
                LogUtil.logBasedOnThreshold(
                        LOGGER, usedMS, getInfoSQLThreshold(), getWarnSQLThreshold(),
                        pc.getConnectionName(), " checkout by ", pc.getThreadCheckOut().getName(), " for " + usedMS + " ms[", (pc.isBusying() ? "BUSYING" : "IDLE"), "]"
                );
            }
        }
        if (verbose) {
            LOGGER.info(poolName, ": checkout:", i, "/connected:", validConnectionNum.get(), "/max:", config.getMaxConnections());
        } else {
            LOGGER.debug(poolName, ": checkout:", i, "/connected:", validConnectionNum.get(), "/max:", config.getMaxConnections());
        }
    }

    void offerUnclosedConnection(Connection connection, String connectionName) {
        unclosedConnections.offer(new UnclosedConnection(connection, connectionName));
    }

    void closeUnclosedConnection() {
        final int c = unclosedConnections.size();
        for (int i = 0; i < c; i++) {
            UnclosedConnection unclosedConnection = unclosedConnections.poll();
            if (unclosedConnection == null) {
                break;
            }
            try {
                unclosedConnection.retryCloseCount++;
                try {
                    unclosedConnection.connection.close();
                } catch (SQLException e) {
                    try {
                        unclosedConnection.connection.rollback();
                    } catch (SQLException ignored) {}
                    unclosedConnection.connection.close();
                }
                LOGGER.info(unclosedConnection.connectionName, " finally be closed!");
            } catch (SQLException e) {
                if (unclosedConnection.retryCloseCount >= 10) {
                    LOGGER.error("closeUnclosedConnection(", unclosedConnection.connectionName, ")[", unclosedConnection.retryCloseCount, "], stop retrying. error: ", e.toString());
                } else {
                    LOGGER.warn("closeUnclosedConnection(", unclosedConnection.connectionName, ")[", unclosedConnection.retryCloseCount, "] error: ", e.toString());
                    unclosedConnections.offer(unclosedConnection);
                }
            }
        }
    }
    // to static class to  improve Performance
    private static class UnclosedConnection {
        final Connection connection;
        final String connectionName;
        int retryCloseCount = 0;

        private UnclosedConnection(Connection connection, String connectionName) {
            this.connection = connection;
            this.connectionName = connectionName;
        }
    }

    private class CPMonitor extends Thread {
        private final ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(Thread.currentThread().getThreadGroup(), r,
                        "CPM:" + poolName + "-helper",
                        0);
            }
        });

        /**
         * （从堆栈底部的连接开始检查）
         * 空闲连接的检查：
         *  0、关闭超过lifetime的空闲连接
         *  1、关闭超过minConnections设置的空闲连接
         *  2、对空闲的连接进行存活检测
         * @return 下次检查的时间间隔(ms)（根据保留的堆栈底部的连接的最后check时间计算得出）
         * @throws InterruptedException InterruptedException
         */
        private long idleConnectionCheckOrClose() throws InterruptedException {
            long timeToNextCheck = config.getIdleTimeoutMillisec();
            Integer[] connIds = idleConnectionsId.toArray(Integer.class);
            for (Integer connId: connIds) {
                PooledConnection pc = validConnectionsPool.get(connId);
                pc.lock(); //锁住连接，不允许checkout
                try {
                    if (pc.isCheckOut()) {
                        // 已经checkout，则停止检测（池中连接按使用时间排序，后续连接肯定也是checkout状态，故停止检测）
                        break;
                    }
                    long _timeToDestroy = pc.millisToDestroy();      // 是否该销毁
                    long _timeToNextCheck = pc.millisToCheckIt(); // 是否该检测
                    LOGGER.trace("idleConnectionCheckOrClose is checking out connection {}, _timeToDestroy={}, _timeToNextCheck={}",
                            pc.getConnectionName(), config.getLifetimeMillisec() <= 0 ? "INFINITE" : _timeToDestroy, _timeToNextCheck);
                    if (_timeToDestroy <= 0) {
                        //到销毁时间，则销毁该连接
                        if (idleConnectionsId.remove(connId)) { // 从空闲池中拿出（池中连接按使用时间排序，非创建时间，所以不能保证是在池底）
                            removeConnection(pc, false);        // 从池中删除（false - 非idle/池底模式）
                            continue;                           // 继续检查下一个连接
                        }
                    } else {
                        //否则，更新下次检测间隔时间（取其中的最小值）
                        timeToNextCheck = Math.min(_timeToDestroy, timeToNextCheck);
                    }
                    if (_timeToNextCheck <= 0) {
                        if (validConnectionNum.get() > config.getMinConnections())  {
                            //当前连接数>最少连接数，则销毁该连接
                            if (! removeConnection(pc, true)) {
                                //连接栈底部不是当前连接
                                //说明连接正等待被检出
                                //（池中连接按使用时间排序，后续连接肯定也是checkout状态，故）停止检测
                                LOGGER.debug("connection {} is checking out, stop removing {}, {} ",
                                        pc.getConnectionName(), _timeToNextCheck, _timeToDestroy);
                                break;
                            }
                        } else {
                            //否则，（异步）检测该连接
                            asyncCheckConnection(pc);
                        }
                    } else {
                        //否则，更新下次检测间隔时间（取其中的最小值）
                        timeToNextCheck = Math.min(timeToNextCheck, _timeToNextCheck);
                        // DON'T break if `lifetime` is set. 2025.03.04 by Hongqiang W. 为了检查下一个连接是否到生命周期该销毁
                        if (config.getLifetimeMillisec() <= 0) {
                            break;
                        }
                    }
                } finally {
                    pc.unlock();
                }
            }
            return timeToNextCheck;
        }

        private void asyncCheckConnection(final PooledConnection pooledConnection) {
            Future<?> future = executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        pooledConnection.doCheck();
                    } catch (Exception e) {
                        LOGGER.warn("exception occurs when doCheck: " + e);
                        try {
                            pooledConnection.doCheck();
                        } catch (Exception ignored) {
                            LOGGER.warn("exception occurs again when doCheck: " + e);
                        }
                    }
                }
            });
            try {
                future.get(config.getIdleTimeoutMillisec(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOGGER.warn("get connection: ", pooledConnection.getConnectionName(), " check result error: ", e);
                pooledConnection.close();
            }
        }

        /**
         * 保持最小连接数，并 wait 直到 waitTimeMillis，期间如果有更多连接需求则创建新连接
         */
        private void newMoreConnections(long waitTimeMillis) throws InterruptedException {
            final long timeout = System.currentTimeMillis() + waitTimeMillis;
            try {
                while (validConnectionNum.get() < config.getMinConnections()) {
                    newConnection(false);
                }
            } catch (SQLException e) {
                LOGGER.warn("exception occurred when maintaining min connections for {} of {}/{} to {}", poolName,
                        validConnectionNum.get(), config.getMinConnections(), config.getUrl(), e);
            }
            while (timeout - System.currentTimeMillis() > 0) {
                long nanos = idleConnectionsId.awaitRequireMore(timeout - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (nanos > 0) {  // nanos > 0 表示有更多连接的需求，否则表示已到超时时间
                    try {
                        newConnection(false);
                    } catch (SQLException e) {
                        LOGGER.warn("exception occurred when creating more connections for {} to {}", poolName, config.getUrl(), e);
                    }
                } else {
                    break;
                }
            }
        }

        public void run() {
            LOGGER.info(getName(), " start!");
            long idleTimeout = config.getIdleTimeoutMillisec();
            while (! shutdown.get()) {
                try {
                    //保持最小连接数（并 wait 直到 idleTimeout）
                    newMoreConnections(idleTimeout);
                    //尝试关闭游离池外的raw connection
                    closeUnclosedConnection();
                    //检查连接可用性，并关闭额外的连接（超过lifetime和超过minConnections的idle连接）
                    idleTimeout = idleConnectionCheckOrClose();
                    //更新最大连接数控制
                    maxConnectionSemaphore.updatePermits(config.getMaxConnections());
                    //log连接池的信息
                    logVerboseInfo(config.isVerbose());
                } catch (InterruptedException e) {
                    if (shutdown.get()) {
                        break;
                    }
                } catch (Exception e) {
                    idleTimeout = config.getIdleTimeoutMillisec();
                    LOGGER.warn(e);
                } catch (Throwable t) {
                    idleTimeout = config.getIdleTimeoutMillisec();
                    LOGGER.error(t);
                }
            }
            executorService.shutdown();
            try {
                if (! executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                    executorService.awaitTermination(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignore) {
                executorService.shutdownNow();
            }
            LOGGER.info(getName(), " quit!");
        }
    }

    private static class LinkedStack<E> {
        // 使用 ConcurrentLinkedDeque 替换 LinkedList，支持无锁并发访问。 suggested by Grok3
        private final ConcurrentLinkedDeque<E> stack;
        private final ReentrantLock operLock = new ReentrantLock();
        private final Condition notEmpty = operLock.newCondition();
        private final Condition requireMore = operLock.newCondition();

        public LinkedStack() {
            stack = new ConcurrentLinkedDeque<>();
        }
        
        public int size() {
            return stack.size();
        }
        
        public boolean popFromBottom(E e) {
            operLock.lock();
            try {
                if (stack.isEmpty()) {
                    return false;
                }
                E last = stack.peekLast();
                if (last != null && last.equals(e)) {
                    return stack.pollLast() != null;
                }
                return false;
            } finally {
                operLock.unlock();
            }
        }

        public void push(E e) {
            stack.offerFirst(e); // 无锁操作，提高并发性
            operLock.lock();
            try {
                notEmpty.signal(); // 通知有新元素可用
            } finally {
                operLock.unlock();
            }
        }


        public E pop() {
            return stack.pollFirst(); // 无锁操作，直接返回栈顶元素
        }

        /**
         * signal for more connection
         */
        public void requireMoreSignal() {
            try {
                operLock.lockInterruptibly();
            } catch (InterruptedException e) {
                return;
            }
            try {
                requireMore.signal();
            } finally {
                operLock.unlock();
            }
        }

        /**
         * 等待请求更多(连接)
         * @param timeout 最长的等待时间
         * @param unit    时间单位
         * @return 剩余的等待时间(ns)
         * @throws InterruptedException InterruptedException
         */
        public long awaitRequireMore(long timeout, TimeUnit unit) throws InterruptedException {
            operLock.lockInterruptibly();
            try {
                try {
                    requireMore.signal();  // 唤醒主线程
                    return requireMore.awaitNanos(unit.toNanos(timeout));
                } catch (InterruptedException e) {
                    requireMore.signal();
                    throw e;
                }
            } finally {
                operLock.unlock();
            }
        }

        /**
         * 等待直到非空
         * @param timeout 最长的等待时间
         * @param unit    时间单位
         * @return        {@code false} if the waiting time detectably elapsed
         *                before return from the method, else {@code true}
         */
        public boolean awaitNotEmpty(long timeout, TimeUnit unit) {
            try {
                operLock.lockInterruptibly();
            } catch (InterruptedException e) {
                return true;
            }
            try {
                return notEmpty.await(timeout, unit);
            } catch (InterruptedException e) {
                return true;
            } finally {
                operLock.unlock();
            }
        }

        public E pop(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            operLock.lockInterruptibly();
            try {
                while (true) {
                    E x = stack.pollFirst();
                    if (x != null) {
                        if (! stack.isEmpty()) {
                            notEmpty.signal();
                        }
                        return x;
                    } else {
                        //stack is empty, signal for more connection
                        requireMore.signal();
                    }
                    // timeout >= 0 表示有超时时间 & nanos <= 0 表示已经超时
                    if (timeout >= 0 && nanos <= 0) {
                        return null;
                    }
                    try {
                        if (timeout < 0) {
                            // no timeout
                            if (! notEmpty.await(1, TimeUnit.SECONDS)) {
                                LOGGER.trace("wait for connection INFINITELY, wakeup to see if there is a connection available");
                            }
                        } else {
                            // 超时场景下改为按1秒周期等待
                            long waitTime = Math.min(nanos, TimeUnit.SECONDS.toNanos(1));
                            long before = System.nanoTime();
                            if (notEmpty.awaitNanos(waitTime) <= 0) {
                                LOGGER.trace("wait connection for {}ms, wakeup to see if there is a connection available", timeout);
                            }
                            nanos -= System.nanoTime() - before;
                        }
                    } catch (InterruptedException ie) {
                        notEmpty.signal(); // propagate to a non-interrupted thread
                        throw ie;
                    }
                }
            } finally {
                operLock.unlock();
            }
        }

        public E[] toArray(Class<E> clazz) {
            operLock.lock();
            try {
                @SuppressWarnings("unchecked")
                E[] array = (E[]) Array.newInstance(clazz, stack.size());
                Iterator<E> descendingIterator = stack.descendingIterator();
                int index = 0;
                while (descendingIterator.hasNext()) {
                    array[index++] = descendingIterator.next();
                }
                return array;
            } finally {
                operLock.unlock();
            }
        }

        public boolean remove(E connId) {
            return stack.remove(connId);
        }
    }
    
    public int getActiveConnectionsCount() {
        return validConnectionNum.get();
    }

    public int getIdleConnectionsCount() {
        return idleConnectionsId.size();
    }

    public void setPoolName(String poolName) {
        if (! this.config.isLoadFromProperties()) {
            this.poolName = poolName;
            if (this.monitor != null) {
                this.monitor.setName("CPM:" + poolName);
            }
        }
    }

}

package io.hqwu.commons.cp;


import io.hqwu.commons.cp.util.JdbcUtil;
import io.hqwu.commons.cp.util.LogUtil;
import io.hqwu.commons.cp.util.SqlMasker;
import io.hqwu.commons.util.ExceptionUtil;
import io.hqwu.commons.util.Formatter;
import io.hqwu.commons.util.Logger;
import lombok.Getter;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link Statement} 的代理实现类，用于在连接池中管理 SQL 语句的执行与资源生命周期。
 *
 * <p>主要功能包括：
 * <ul>
 *   <li>配合 {@link PooledConnection} 实现语句的检入（Check-in）与检出（Check-out）管理。</li>
 *   <li>监控 SQL 执行性能，记录执行耗时并根据阈值配置打印相关日志。</li>
 *   <li>集成 {@link io.hqwu.commons.cp.util.SqlMasker} 对执行的 SQL 进行敏感字段脱敏。</li>
 *   <li>自动包装 {@link ResultSet} 为 {@code LoggableResultSet} 以支持结果集数据的追踪记录。</li>
 *   <li>异常处理与连接状态维护，确保在发生致命错误时能触发物理连接的回收。</li>
 * </ul>
 *
 * @author taige (Wu, Hongqiang)
 * @since 2011-09-02
 */
public class PooledStatement implements InvocationHandler {
    private static final Logger LOGGER = new Logger();
    
    /**
     * 归属连接
     */
    private final PooledConnection pooledConnection;

    /**
     * 语句Id
     */
    @Getter
    private final int statementId;
    /**
     * 语句名称
     */
    @Getter
    private final String statementName;
    
    /**
     * 封装过的语句
     */
    private final Statement statement;
    /**
     * 真实的语句
     */
    private final Statement real_statement;
    
    /**
     * 是否检出（使用中）
     */
    protected AtomicBoolean checkOut = new AtomicBoolean(false);
    /**
     * 检入时间
     */
    @Getter
    private long timeCheckIn = System.currentTimeMillis();
    /**
     * 检出时间
     */
    private long timeCheckOut;
    /**
     * 检出线程
     */
    private Thread threadCheckOut;
    
    /**
     * 是否默认的结果集类型
     */
    @Getter
    protected boolean isDefaultResultSetType;
    /**
     * 语句打开的结果集
     */
    protected ResultSet resultSet;

    /**
     * result set counter
     */
    private int resultSetCounter = 0;

    /**
     * 连接是否关闭
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * 语句是否正在执行
     */
    private long busying = 0;

    /**
     * 更新语句影响的记录数
     */
    private Long updateCount;

    private String methodBusying;

    private String sqlDoing;

    PooledStatement(PooledConnection conn, Statement stmt, int stmtId) throws SQLException {
        pooledConnection = conn;
        statementId = stmtId;
        statementName = conn.getConnectionName() + ".STMT#" + stmtId;
        real_statement = stmt;
        isDefaultResultSetType = stmt.getResultSetType() == ResultSet.TYPE_FORWARD_ONLY
                && stmt.getResultSetConcurrency() == ResultSet.CONCUR_READ_ONLY;
        statement = buildProxy();
    }
    
    public Statement checkOut() throws SQLException {
        if (checkOut.getAndSet(true)) {
            if (threadCheckOut.equals(Thread.currentThread())) {
                return statement;
            } else {
                throw new SQLException(statementName + "已经被"+threadCheckOut.getName()+"检出", "60003");
            }
        }
        resultSet = null;
        updateCount = null;
        timeCheckOut = System.currentTimeMillis();
        threadCheckOut = Thread.currentThread();
        return statement;
    }
    
    protected Statement buildProxy() {
        return (Statement) Proxy.newProxyInstance(real_statement.getClass().getClassLoader(), new Class[] {Statement.class}, this);
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        sqlDoing = null;
        String methodDoing = method.getName();
        if ("toString".equals(methodDoing) && args == null) {
            return toString();//不代理
        }

        // Ensure statement is open for business logic operations (excluding close/isClosed etc.)
        if (!checkOut.get() && !"close".equals(methodDoing) && !"isClosed".equals(methodDoing)) {
            throw new SQLException("Statement has been closed", "HY010"); // HY010: Function sequence error
        }

        try {
            busying = System.nanoTime();
            methodBusying = methodDoing;
            Object obj = _invoke(proxy, method, args);
            if (methodDoing.startsWith("execute") && ! methodDoing.startsWith("executeQuery")) {
                if (! methodDoing.equals("execute") || ! (Boolean) obj) {
                    pooledConnection.setDirty();
                }
            }
            return obj;
        } catch (Exception e) {
            if (methodDoing.startsWith("execute")) {
                if (e instanceof SQLException) {
                    LOGGER.info(e.toString(), "[ErrorCode=", ((SQLException) e).getErrorCode(), ";SQLState=", ((SQLException) e).getSQLState(), "] on ", methodDoing, "(", getMaskedSql(), ")");
                } else {
                    // MySQL unexpected exception with mysql-connector-java:8.0.19:
                    // java.lang.NullPointerException: null
                    //	at com.mysql.cj.AbstractQuery.stopQueryTimer(AbstractQuery.java:206)
                    //	at com.mysql.cj.jdbc.StatementImpl.stopQueryTimer(StatementImpl.java:643)
                    //	at com.mysql.cj.jdbc.StatementImpl.executeQuery(StatementImpl.java:1182)
                    LOGGER.error("unexpected exception occurs on ", methodDoing, "(", getMaskedSql(), ")", e);
                }
            }
            if (! (e instanceof SQLException) || pooledConnection.isFatalException((SQLException) e)) {
                close();
                //sql执行失败不新建连接,直接关闭物理链接,等待Connection的close调用,modify by shenjl at 2015-03-09
                pooledConnection.setFatalExceptionHappened(true);
                //connection.recover(e);
            }
            throw e;
        } finally {
            busying = 0;
            methodBusying = null;
        }
    }
    
    protected Object _invoke(Object proxy, Method method, Object[] args) throws Throwable {
        long start = System.nanoTime();
        Object ret = null;
        try {
            String methodDoing = method.getName();
            if (methodDoing.equals("close")) {
                if (! checkOut.getAndSet(false)) {
                    return null;
                }
                timeCheckIn = System.currentTimeMillis();
                if (resultSet != null) {
                    try {
                        resultSet.close();
                    } catch (SQLException ignored) {
                    } finally {
                        resultSet = null;
                    }
                }
                if (updateCount != null) {
                    updateCount = null;
                }
                if (this instanceof PooledPreparedStatement) {
                    pooledConnection.checkIn((PooledPreparedStatement) this);
                } else {
                    pooledConnection.checkIn(this);
                }
                if (isVerbose()) {
                    LOGGER.trace(statementName, ".close() use ", Formatter.formatNS(System.nanoTime() - start), " ns");
                }
            } else if (methodDoing.equals("addBatch") && args != null) {
                sqlDoing = (String) args[0];
                real_statement.addBatch((String) args[0]);
                printSQL(LOGGER, methodDoing, (System.nanoTime() - start));
            } else if (methodDoing.equals("executeBatch") || methodDoing.equals("executeLargeBatch")) {
                ret = real_statement.executeBatch();
                if (isPrintSQL()) {
                    printSQL(LOGGER, methodDoing, this::getPreparedSql, (System.nanoTime() - start), "[", Array.getLength(ret), "]");
                }
            } else if (methodDoing.equals("executeQuery") && args != null) {
                sqlDoing = (String) args[0];
                ResultSet rs = real_statement.executeQuery((String) args[0]);
                if (isVerbose() || isPrintSQL()) {
                    LoggableResultSet lrs = LoggableResultSet.newInstance(this, rs);
                    ret = resultSet = lrs == null ? null : lrs.getResultSet();
                    printSQL(LOGGER, methodDoing, (System.nanoTime() - start), "[", (lrs == null ? "rs=null" : "rs=#" + lrs.getRsId()), "]");
                } else {
                    ret = resultSet = rs;
                }
            } else if (methodDoing.startsWith("execute") && args != null) {
                sqlDoing = (String) args[0];
                ret = method.invoke(real_statement, args);
                Object ret4log = onExecuteMethodDone(methodDoing, ret);
                printSQL(LOGGER, methodDoing, (System.nanoTime() - start), "[", ret4log, "]");
            } else if (methodDoing.equals("getMoreResults")) {
                ret = method.invoke(real_statement, args);
                Object ret4log = onExecuteMethodDone(methodDoing, ret);
                if (isVerbose() || isPrintSQL()) {
                    LOGGER.debug(statementName, ".", methodDoing, "(...)", "[", ret4log, "] use ", Formatter.formatNS(System.nanoTime() - start), " ns");
                }
            } else if (methodDoing.equals("isWrapperFor") && args != null) {
                ret = JdbcUtil.isWrapperFor((Class<?>) args[0], this, real_statement, proxy);
                if (isVerbose()) {
                    LOGGER.debug(statementName, ".", methodDoing, "(", args[0], ") use ", Formatter.formatNS(System.nanoTime() - start), " ns");
                }
            } else if (methodDoing.equals("unwrap") && args != null) {
                ret = JdbcUtil.unwrap((Class<?>) args[0], this, real_statement, proxy);
                if (isVerbose()) {
                    LOGGER.debug(statementName, ".", methodDoing, "(", args[0], ") use ", Formatter.formatNS(System.nanoTime() - start), " ns");
                }
            } else {
                if (methodDoing.equals("getResultSet") && resultSet != null) {
                    ret = resultSet;
                } else if (methodDoing.equals("getUpdateCount") && updateCount != null) {
                    ret = updateCount.intValue();
                } else if (methodDoing.equals("getLargeUpdateCount") && updateCount != null) {
                    ret = updateCount;
                } else if (methodDoing.equals("isClosed")) {
                    ret = ! checkOut.get();
                } else if (methodDoing.equals("getConnection")) {
                    ret = pooledConnection.getProxy();
                } else {
                    ret = method.invoke(real_statement, args);
                }
                if (isVerbose()) {
                    LOGGER.trace(statementName, ".", methodDoing, "(...) use ", Formatter.formatNS(System.nanoTime() - start), " ns");
                }
            }
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
        return ret;
    }

    protected Object onExecuteMethodDone(String methodDoing, Object ret) throws SQLException {
//        boolean b = real_statement.execute("");
//        int n = real_statement.executeUpdate("");
//        long l = real_statement.executeLargeUpdate("");
        if (methodDoing.equals("execute") || methodDoing.equals("getMoreResults")) {
            // true if the first result is a ResultSet object;
            // false if it is an update count or there are no results
            if ((Boolean) ret) {
                updateCount = -1L;
                ResultSet rs = real_statement.getResultSet();
                if (isVerbose() || isPrintSQL()) {
                    LoggableResultSet lrs = LoggableResultSet.newInstance(this, rs);
                    resultSet = lrs == null ? null : lrs.getResultSet();
                    return lrs == null ? "rs=null" : "rs=#" + lrs.getRsId();
                } else {
                    resultSet = rs;
                    return "rs";
                }
            } else {
                resultSet = null;
                updateCount = (long) real_statement.getUpdateCount();
            }
        } else {
            resultSet = null;
            if (int.class.isAssignableFrom(ret.getClass())
                    || Integer.class.isAssignableFrom(ret.getClass())) {
                updateCount = (long) (int) ret;
            } else if (long.class.isAssignableFrom(ret.getClass())
                    || Long.class.isAssignableFrom(ret.getClass())) {
                updateCount = (long) ret;
            } else {
                return ret;
            }
        }
        return updateCount;
    }

    private long getInfoSQLThreshold() {
        return pooledConnection.getConnectionPool().getInfoSQLThreshold();
    }

    private long getWarnSQLThreshold() {
        return pooledConnection.getConnectionPool().getWarnSQLThreshold();
    }

    /**
     * 根据执行时间，打印不同级别的SQL日志
     * @param logger 日志记录器
     * @param methodDoing 执行的方法名
     * @param usedNS 执行耗时（纳秒）
     * @param infos 日志信息
     */
    protected void printSQL(Logger logger, String methodDoing, long usedNS, Object... infos) {
        if (! isPrintSQL()) {
            return;
        }
        printSQL(logger, methodDoing, this::getMaskedSql, usedNS, infos);
    }

    protected void printSQL(Logger logger, String methodDoing, Supplier<String> sqlSupplier, long usedNS, Object... infos) {
        if (! LogUtil.isEnabled(logger, usedNS/1000000, getInfoSQLThreshold(), getWarnSQLThreshold())) {
            return;
        }
        LogUtil.logBasedOnThreshold(
                logger, usedNS/1000000, getInfoSQLThreshold(), getWarnSQLThreshold(),
                getStatementName(), ".", methodDoing, "(", sqlSupplier.get(), ")", infos, " use ", Formatter.formatNS(usedNS), " ns"
        );
    }

    protected String getPreparedSql() {
        return "";
    }

    String getMaskedSql() {
        HqcpConfig config = pooledConnection.getConnectionPool().getConfig();
        SqlMasker sqlMasker = pooledConnection.getConnectionPool().getSqlMasker();
        if (config.isMaskSql() && sqlMasker != null) {
            return sqlMasker.maskSensitiveFields(getSqlDoing());
        }
        return getSqlDoing();
    }

    protected String getSqlDoing() {
        sqlDoing = sqlDoing == null ? "" : JdbcUtil.removeBreakingWhitespace(sqlDoing);
        return sqlDoing;
    }

    @Override
    public String toString() {
        return statementName + "{" + real_statement + "}";
    }

    public Statement getProxy() {
        return statement;
    }
    
    public Statement getStatement() {
        return real_statement;
    }

    /**
     * Return checkOut.
     * @return checkOut
     */
    public boolean isCheckOut() {
        return checkOut.get();
    }

    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        try {
            real_statement.close();
        } catch (SQLException ignored) {
        }
        if (isVerbose()) {
            LOGGER.debug(statementName, " real closed.");
        }
    }

    public boolean isVerbose() {
        return pooledConnection.isVerbose();
    }

    public boolean isPrintSQL() {
        return pooledConnection.isPrintSQL();
    }

    public long getCheckOutTime() {
        if (checkOut.get()) {
            return System.currentTimeMillis() - timeCheckOut;
        }
        return 0L;
    }
    
    public boolean isBusying() {
        if (checkOut.get() && busying > 0) {
            long usedNS = System.nanoTime() - busying;
            if (LogUtil.isEnabled(LOGGER, usedNS/1000000, getInfoSQLThreshold(), getWarnSQLThreshold())) {
                LogUtil.logBasedOnThreshold(
                        LOGGER, usedNS/1000000, getInfoSQLThreshold(), getWarnSQLThreshold(),
                        getStatementName(), " invoking ", methodBusying, "(", getMaskedSql(), ")", " use ", Formatter.formatNS(usedNS), " ns"
                );
            }
            return true;
        }
        return false;
    }
    
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 真实语句是否已被驱动关闭（如调用方 closeOnCompletion 后结果集关闭），探测出错也按已关闭处理
     */
    public boolean isRealClosed() {
        try {
            return real_statement.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * {@link ResultSet} 的代理包装类，用于增强结果集的可观测性。
     *
     * <p>主要功能包括：
     * <ul>
     *   <li>自动统计并记录查询返回的总行数。</li>
     *   <li>在日志级别允许时，格式化输出结果集的列名及各行数据。</li>
     *   <li>识别并特殊处理 BLOB/CLOB 等大字段类型，避免在日志中输出二进制内容。</li>
     *   <li>配合 {@link PooledStatement} 实现对 SQL 执行结果的完整追踪。</li>
     * </ul>
     *
     * @author taige (Wu, Hongqiang)
     * @since 2021-03-30
     */
    static class LoggableResultSet implements InvocationHandler {
        private static final Set<Integer> BLOB_TYPES = new HashSet<>();
        private boolean first = true;
        private int rows;
        private final Set<Integer> blobColumns = new HashSet<>();

        private final PooledStatement pooledStatement;
        private final ResultSet resultSet;
        private final ResultSet rsProxy;
        @Getter
        private final int rsId;

        static {
            BLOB_TYPES.add(Types.BINARY);
            BLOB_TYPES.add(Types.BLOB);
            BLOB_TYPES.add(Types.CLOB);
            BLOB_TYPES.add(Types.LONGNVARCHAR);
            BLOB_TYPES.add(Types.LONGVARBINARY);
            BLOB_TYPES.add(Types.LONGVARCHAR);
            BLOB_TYPES.add(Types.NCLOB);
            BLOB_TYPES.add(Types.VARBINARY);
        }

        private LoggableResultSet(PooledStatement pooledStatement, ResultSet resultSet) {
            this.pooledStatement = pooledStatement;
            this.resultSet = resultSet;
            this.rsId = pooledStatement.resultSetCounter++;
            this.rsProxy =  (ResultSet) Proxy.newProxyInstance(resultSet.getClass().getClassLoader(), new Class[]{ResultSet.class}, this);
        }

        public static LoggableResultSet newInstance(PooledStatement pooledStatement, ResultSet resultSet) {
            if (resultSet == null) {
                return null;
            }
            return new LoggableResultSet(pooledStatement, resultSet);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                String methodDoing = method.getName();
                if (methodDoing.equals("isWrapperFor") && args != null) {
                    return JdbcUtil.isWrapperFor((Class<?>) args[0], this, resultSet, proxy);
                } else if (methodDoing.equals("unwrap") && args != null) {
                    return JdbcUtil.unwrap((Class<?>) args[0], this, resultSet, proxy);
                }
                Object o = method.invoke(resultSet, args);
                if ("next".equals(methodDoing)) {
                    if ((Boolean) o) {
                        rows++;
                        if (pooledStatement.isVerbose() && LOGGER.isTraceEnabled()) {
                            ResultSetMetaData rsmd = resultSet.getMetaData();
                            final int columnCount = rsmd.getColumnCount();
                            if (first) {
                                first = false;
                                printColumnHeaders(rsmd, columnCount);
                            }
                            printColumnValues(columnCount);
                        }
                    } else if (pooledStatement.isVerbose() || pooledStatement.isPrintSQL()) {
                        LOGGER.debugf("%s.rs#%d.Total: %d", pooledStatement.getStatementName(), this.rsId, rows);
                    }
                }
                return o;
            } catch (Throwable t) {
                throw ExceptionUtil.unwrapThrowable(t);
            }
        }

        private void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) throws SQLException {
            StringJoiner row = new StringJoiner(", ");
            for (int i = 1; i <= columnCount; i++) {
                if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
                    blobColumns.add(i);
                }
                row.add(rsmd.getColumnLabel(i));
            }
            LOGGER.tracef("%s.rs#%d.Columns: %s", pooledStatement.getStatementName(), this.rsId, row);
        }

        private void printColumnValues(int columnCount) {
            StringJoiner row = new StringJoiner(", ");
            for (int i = 1; i <= columnCount; i++) {
                try {
                    if (blobColumns.contains(i)) {
                        row.add("<<BLOB>>");
                    } else {
                        row.add(resultSet.getString(i));
                    }
                } catch (SQLException e) {
                    // generally can't call getString() on a BLOB column
                    row.add("<<Cannot Display>>");
                }
            }
            LOGGER.tracef("%s.rs#%d.Row: %s", pooledStatement.getStatementName(), this.rsId, row);
        }

        /**
         * Get the wrapped result set.
         *
         * @return the resultSet
         */
        public ResultSet getResultSet() {
            return rsProxy;
        }

    }
}

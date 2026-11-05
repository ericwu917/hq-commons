package io.hqwu.commons.cp;

import io.hqwu.commons.cp.util.JdbcUtil;
import io.hqwu.commons.util.ClassUtil;
import io.hqwu.commons.util.ExceptionUtil;
import io.hqwu.commons.util.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@link PreparedStatement} 的池化包装实现。
 *
 * <p>该类扩展了 {@link PooledStatement}，专门用于处理预编译 SQL 语句。它通过动态代理拦截
 * JDBC 方法调用，实现了对 SQL 执行耗时的监控以及参数化的 SQL 日志打印。
 *
 * <p>主要职责包括：
 * <ul>
 *   <li>维护预编译 SQL 及其对应的参数列表。</li>
 *   <li>在执行时还原完整的 SQL 语句用于日志输出。</li>
 *   <li>配合 {@link PooledConnection} 实现语句的生命周期管理。</li>
 * </ul>
 *
 * @see PooledConnection
 * @see PooledStatement
 * @see java.sql.PreparedStatement
 */
public class PooledPreparedStatement extends PooledStatement {
    private static final Logger LOGGER = new Logger();

    private final PreparedStatement real_pstmt;
    
    private final String preparedSql;
    private final Object[] paras;

    private String sqlDoing;

    protected PooledPreparedStatement(PooledConnection conn, PreparedStatement stmt, int stmtId, Object[] args) throws SQLException {
        super(conn, stmt, stmtId);
        if (args.length != 1 && args.length != 3) {
            super.isDefaultResultSetType = false;
        }
        String sql = (String) args[0];
        real_pstmt = (PreparedStatement) getStatement();
        this.preparedSql = sql == null ? "" : JdbcUtil.removeBreakingWhitespace(sql);
        this.paras = new Object[getQMCount()];
    }

    protected String getPreparedSql() {
        return preparedSql;
    }

    /*
    * Do nothing for generic PooledPreparedStatement. For subclass implementation.
    */
    public void cleanCache() {}

    protected PreparedStatement buildProxy() {
        Statement stmt = getStatement();
        return (PreparedStatement) Proxy.newProxyInstance(stmt.getClass().getClassLoader(), new Class[]{PreparedStatement.class}, this);
    }

    protected Object _invoke(Object proxy, Method method, Object[] args) throws Throwable {
        long start = System.nanoTime();
        Object ret = null;
        sqlDoing = null;
        try {
            String methodDoing = method.getName();
            if (methodDoing.equals("addBatch") && args == null) {
                real_pstmt.addBatch();
                printSQL(LOGGER, methodDoing, (System.nanoTime() - start));
            } else if (methodDoing.equals("execute") && args == null) {
                ret = real_pstmt.execute();
                Object ret4log = onExecuteMethodDone(methodDoing, ret);
                printSQL(LOGGER, methodDoing, (System.nanoTime() - start), "[", ret4log, "]");
            } else if (methodDoing.equals("executeQuery") && args == null) {
                LoggableResultSet lrs = LoggableResultSet.newInstance(this, real_pstmt.executeQuery());
                ret = resultSet = lrs == null ? null : lrs.getResultSet();
                printSQL(LOGGER, methodDoing, (System.nanoTime() - start), "[", (lrs == null ? "rs=null" : "rs=#" + lrs.getRsId()), "]");
            } else if (methodDoing.equals("executeUpdate") && args == null) {
                ret = real_pstmt.executeUpdate();
                printSQL(LOGGER, methodDoing, (System.nanoTime() - start), "[", ret, "]");
            } else {
                if (methodDoing.startsWith("set") && args.length >= 2 && args[0] instanceof Integer) {
                    int idx = (Integer) args[0];
                    if (paras.length >= idx) {
                        // myBatis会先调用setBNull 2017.1.18
                        if (methodDoing.equals("setNull")) {
                            paras[idx - 1] = null;
                        } else {
                            paras[idx - 1] = args[1];
                        }
                    }
                }
                ret = super._invoke(proxy, method, args);
            }
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
        return ret;
    }

    private int getQMCount() {
        int c = 0;
        int idx = 0;
        while (true) {
            idx = preparedSql.indexOf("?", idx + 1);
            if (idx == -1) {
                break;
            }
            c++;
        }
        return c;
    }

    protected String getSqlDoing() {
        if (sqlDoing != null) {
            return sqlDoing;
        }
        if (paras.length == 0) {
            return preparedSql;
        }
        StringBuilder sb = new StringBuilder(preparedSql.length() + paras.length * 16);
        int idx = 0;
        for (Object p : paras) {
            int idxNext = indexOfPlaceholder(preparedSql, idx);
//            if (p == null) {
//                idx = idxNext+1;
//                continue;
//            }
            if (idxNext < 0) {
                break;
            }
            sb.append(preparedSql, idx, idxNext);
            if (p == null) {
                sb.append("NULL");
            } else if (ClassUtil.isPrimitiveOrWrapper(p.getClass()) && p.getClass() != Character.class) {
                sb.append(p);
            } else {
                // 先转义 \ 再转义 '，保证内联后的 SQL 能被 SqlMasker 正确解析（该串只用于日志）
                String s = String.valueOf(p).replace("\\", "\\\\").replace("'", "''");
                sb.append('\'').append(s).append('\'');
            }
            idx = idxNext + 1;
        }
        sb.append(preparedSql.substring(idx));
        sqlDoing = sb.toString();
        return sqlDoing;
    }

    /**
     * 从 from 起找下一个占位符 {@code ?} 的下标，跳过以下位置里的 {@code ?}：<br/>
     *  - 单引号字面值 {@code '...'}（含 {@code ''} 与 {@code \} 转义）<br/>
     *  - 双引号 {@code "..."}（含 {@code ""} 与 {@code \} 转义）、反引号 {@code `...`}（含 {@code ``} 转义）<br/>
     *  - 块注释 {@code /* ... *}{@code /}、行注释 {@code -- } 到行尾 <br/>
     * 未闭合的引号 / 注释视为延伸到结尾
     * @param sql  预编译 SQL
     * @param from 起始下标
     * @return 占位符下标，找不到返回 -1
     */
    static int indexOfPlaceholder(String sql, int from) {
        int len = sql.length();
        int i = from;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '?') {
                return i;
            }
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
            } else if (c == '-' && sql.startsWith("-- ", i)) {
                int end = sql.indexOf('\n', i + 3);
                i = end < 0 ? len : end + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * 跳过从 start（开引号）起的引号段，返回闭引号之后的下标；成对引号视为转义，
     * 单 / 双引号内 {@code \} 加下一个字符也视为转义（反引号内不算）
     */
    private static int skipQuoted(String sql, int start, char quote) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
            } else if (c == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return len;
    }
}

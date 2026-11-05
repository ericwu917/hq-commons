package io.hqwu.commons.cp;

import io.hqwu.commons.cp.util.JdbcUtil;
import io.hqwu.commons.cp.util.SqlMasker;
import io.hqwu.commons.security.SecurityService;
import io.hqwu.commons.security.SecurityServiceLocalImpl;
import io.hqwu.commons.util.Logger;
import io.hqwu.commons.util.StringUtil;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.codec.binary.Base64;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

/**
 * Hqcp 数据库连接池配置类。
 * <p>
 * 该类负责管理数据库连接池的核心配置参数，包括 JDBC 连接信息（URL、驱动、凭据）、
 * 连接池容量控制（最小/最大连接数）、超时机制、Statement 缓存以及 SQL 执行监控等。
 * 支持通过 Properties 文件或 Spring 资源进行加载，并集成了 {@link SecurityService}
 * 用于数据库密码的加密存储与自动解密。
 * </p>
 *
 * @author zhangyao
 * @since 1.4
 * @see HqcpConfigMBean
 */
public class HqcpConfig implements HqcpConfigMBean {
    private static final Logger LOGGER = new Logger();

    private static final String PATTERN_COMMONS_CHARS =  "[\u0020-\u007E]+";

    private static final String[] classPaths = System.getProperty("java.class.path", "classes").split(System.getProperty("path.separator", ";"));

    /**
     * 连接URL
     */
    @Getter
    private String url;
    /**
     * jdbc驱动类
     */
    @Getter
    @Setter
    private String driverClassName;

    /**
     * 数据库用户名
     */
    @Getter
    private String username;
    /**
     * 数据库用户口令
     */
    private String password;
    /**
     * 解密的口令
     */
    private String decPassword;

    /**
     * 池中最小连接数<br/>
     * 取值范围 0 - 100<br/>
     * 默认 1<br/>
     */
    @Getter
    private int minConnections = 1;
    /**
     * 池中最大连接数<br/>
     * 取值范围 1 - 1000<br/>
     * 默认 10<br/>
     */
    @Getter
    private int maxConnections = 10;
    /**
     * 池中最多缓存的Statement数<br/>
     * 超过该值的话会抛SQLException，说明程序存在极不合理的实现，即一个过程中同时创建了太多Statement且没有及时close<br/>
     * 取值范围 10 - 1000<br/>
     * 默认 100<br/>
     */
    @Getter
    private int maxStatements = 100;
    /**
     * 池中最多缓存的PreparedStatement数 <br/>
     * 超过该值的话会close最早未使用的PreparedStatement <br/>
     * 取值范围 5 - 200<br/>
     * 默认 10<br/>
     */
    @Getter
    private int maxPreStatements = 10;
    /**
     * 连接最大空闲时间(单位：秒seconds)，空闲超过该时间的连接将被检测或回收<br/>
     * 取值范围 10s - 3600s
     * 默认 5 * 60s<br/>
     */
    @Getter
    private long idleTimeoutSec = 5 * 60;
    /**
     * 等待空闲连接时的超时时间(单位：milliseconds)<br/>
     * <0 一直等待，直到有空闲连接 <b>危险！如果程序有bug可能导致一直等待</b> available v1.4 <br/>
     * =0 不等待，立刻抛SQLException <br/>
     * 最大值 600 * 1000ms<br/>
     * 默认 10 * 1000ms<br/>
     */
    @Getter
    private long checkoutTimeoutMillisec = 10000;
    /**
     * 连接存活时间(单位：秒seconds)，存活超过这个时间的连接将被回收 available v1.4 <br/>
     * <=0 不回收<br/>
     * 最大值 24 * 3600s<br/>
     * 默认：0 不回收<br/>
     */
    private long lifetimeSec = 0;

    /**
     * 关闭连接时自动提交事务<br/>
     * 默认 false
     */
    @Getter
    @Setter
    private boolean commitOnClose = false;

    /**
     * 记录除SQL语句及执行时间外的其他信息<br/>
     * 默认 false
     */
    @Getter
    @Setter
    private boolean verbose = false;

    /**
     * 记录SQL语句及执行时间<br/>
     * 默认 true
     */
    @Getter
    @Setter
    private boolean printSql = true;

    /**
     * 打印log的时候是否脱敏敏感字段<br/>
     * 默认 false
     */
    @Getter
    @Setter
    private boolean maskSql = false;

    /**
     * 脱敏模式，如"***", "####", "????", "*#?●○"<br/>
     * 默认 ****
     */
    @Getter
    private String maskPattern = "****";

    /**
     * 敏感字段集<br/>
     * 条目可带后缀 {@code 列名:首:尾}（两个非负整数）指定该列的值保留首尾多少位，中间放一份 maskPattern，
     * 如 {@code bank_account:0:4}；{@code pin:0:0} 表示整值全掩。无后缀的条目沿用全局规则（见 {@link #maskKeepHead}）。
     * 后缀格式不合法时写入即抛 {@link IllegalArgumentException}（见 {@link #setSensitiveFields(Set)}）
     */
    @Getter
    private Set<String> sensitiveFields = new HashSet<>();

    /**
     * 脱敏时全局保留的首部位数<br/>
     * 默认 -1（未设）。mask-keep-head / mask-keep-tail 任一 >= 0 即启用全局首尾保留规则，另一个未设按 0：
     * 值长度 <= 首+尾 时输出 maskPattern，否则输出 首部 + maskPattern + 尾部；
     * 都未设时沿用默认脱敏算法。按列后缀优先于全局规则
     */
    @Getter
    @Setter
    private int maskKeepHead = -1;

    /**
     * 脱敏时全局保留的尾部位数<br/>
     * 默认 -1（未设），规则见 {@link #maskKeepHead}
     */
    @Getter
    @Setter
    private int maskKeepTail = -1;

    /**
     * 敏感字段是否精确匹配列名<br/>
     * 默认 false：列名包含敏感字段即命中（子串匹配）；true：列名去掉两侧引号后与敏感字段相等才命中
     */
    @Getter
    @Setter
    private boolean sensitiveFieldsExactMatch = false;

    /**
     * 检测连接是否可用的查询语句<br/>
     * 会根据数据库类型自动配置：<br/>
     * oracle - select systimestamp from dual <br/>
     * mysql - select now() <br/>
     * db2 - values(current timestamp) <br/>
     */
    @Getter
    private String checkStatement;

    /**
     * 0 - [default] no jmx <br/>
     * 1 - manage ConnectionFactory instance <br/>
     * 2 - manage PooledConnection instance
     */
    @Getter
    private int jmxLevel = 0;

    /**
     * 默认获取的连接的事务模式 <br/>
     * true: 事务模式，即autocommit=false <br/>
     * false: <b>[默认值]</b> 非事务模式，即autocommit=true <br/>
     */
    @Getter
    @Setter
    private boolean transactionMode = false;

    /**
     * lazy init pool <br/>
     * true: init min connections in Monitor thread, else init in new Hqcp/getConnection() thread, i.e. client thread. <br/>
     * 默认 false
     */
    @Getter
    @Setter
    private boolean lazyInit = false;

    /**
     * printSQL == true时，打印INFO级别的SQL的耗时阈值(ms) <br/>
     * <=0 - 不打印INFO级别SQL <br/>
     * 默认 10ms
     */
    @Getter
    @Setter
    private long infoSqlThreshold = 10;

    /**
     * printSQL == true时，打印WARN级别的SQL的耗时阈值(ms) <br/>
     * <=0 - 不打印 WARN 级别SQL <br/>
     * 默认 100ms
     */
    @Setter
    private long warnSqlThreshold = 100;

    /**
     * Indicates if this is for Oracle.
     */
    @Getter
    private boolean isOracle = false;

    /**
     * Indicates if this is MySQL cp
     */
    @Getter
    private boolean isMySQL = false;

    /**
     * Indicates if this is DB2 cp
     */
    @Getter
    private boolean isDB2 = false;

    /**
     * Indicates if this is OceanBase cp
     */
    @Getter
    private boolean isOceanBase = false;

    /*
     * Indicates if oracle implicit preparedstatement cache needed.
     */
    // private boolean useOracleImplicitCache = true;

    /**
     * connection properties on DriverManager.getConnection(url,info)<br/>
     * 用 & 分割属性
     */
    private final Properties connectionProperties = new Properties();

    /**
     * query timeout (seconds) <br/>
     * <= 0 - no timeout <br/>
     * 默认 60s
     */
    @Getter
    @Setter
    private int queryTimeout = 60;

    /**
     *  config是从properties文件读入的文件名
     */
    private String properties = null;

    /**
     * 解密password的密钥
     */
    private String passwordKey = null;

    @Setter
    private volatile SecurityService securityService;

    /*
     * default login timeout 10 seconds
     */
    static {
        DriverManager.setLoginTimeout(10);
    }

    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

    public void setLoginTimeout(int loginTimeout) {
        DriverManager.setLoginTimeout(loginTimeout);
    }

    public Properties getConnectionProperties() {
        if (! connectionProperties.containsKey("password")) {
            String password = this.getPassword();
            if (password != null) {
                this.connectionProperties.setProperty("password", password);
            }
        }
        return connectionProperties;
    }

    public void setConnectionInfo(String connectionInfo) {
        if (StringUtil.isBlank(connectionInfo)) {
            return;
        }

        String[] entries = connectionInfo.split("&");
        for (String entry : entries) {
            if (!entry.isEmpty()) {
                int index = entry.indexOf('=');
                if (index > 0) {
                    String name = entry.substring(0, index);
                    String value = entry.substring(index + 1);
                    connectionProperties.setProperty(name, value);
                } else {
                    // no value is empty string which is how java.util.Properties works
                    connectionProperties.setProperty(entry, "");
                }
            }
        }
    }

    private void _setUrl(String url) {
        this.url = url;
        if (StringUtil.isNotBlank(this.url)) {
            String[] buf = this.url.split(":");
            if (buf.length < 2) {
                return;
            }
            String dbf = buf[1];
            if (dbf.compareToIgnoreCase("oracle") == 0) {
                isOracle = true;
            } else if (dbf.compareToIgnoreCase("mysql") == 0) {
                isMySQL = true;
            } else if (dbf.compareToIgnoreCase("db2") == 0) {
                isDB2 = true;
            } else if (dbf.compareToIgnoreCase("oceanbase") == 0) {
                isOceanBase = true;
            }
            if (StringUtil.isBlank(this.checkStatement)) {
                //if checkStatement NOT be set, auto-set by url
                if (isDB2) {
                    checkStatement = "values(current timestamp)";
                } else if (isOracle) {
                    checkStatement = "select systimestamp from dual";
                } else if (isMySQL || isOceanBase) {
                    checkStatement = "select now()";
                }
            }
            if (StringUtil.isBlank(this.driverClassName)) {
                try {
                    java.sql.Driver driver = DriverManager.getDriver(url);
                    if (driver != null) {
                        this.driverClassName = driver.getClass().getName();
                        LOGGER.info("SPI: Auto-detected driver class: " + this.driverClassName);
                    }
                } catch (SQLException e) {
                    LOGGER.warn("SPI: No suitable driver found for " + url);
                }
            }
        }
    }

    /*
     * 2012-11-12 zhangyao 支持url参数的注入，保持一其它数据库连接池一致
     */
    public void setUrl(String url) {
        _setUrl(url);
    }

    public long getWarnSqlThreshold() {
        return warnSqlThreshold <= 0 ? warnSqlThreshold : Math.max(warnSqlThreshold, infoSqlThreshold);
    }

    public void setUsername(String username) {
        this.username = username;
        if (StringUtil.isNotBlank(username)) {
            this.connectionProperties.setProperty("user", username);
        }
    }

    /**
     * 获取密码，若已解密则返回解密后的密码，否则返回初始密码。
     * 若密码为空，则返回 null。
     * @return 解密后的密码或初始密码
     */
    String getPassword() {
        if (StringUtil.isEmpty(this.password)) {
            return null;
        }
        if (this.decPassword != null) {
            return this.decPassword;
        }
        return this.password;
    }

    /**
     * 解密 Base64 编码的密码，若非加密数据或解密失败，返回初始密码。
     * @param password 输入的密码字符串
     * @return 解密后的密码或初始密码
     */
    private String decryptPassword(String password) {
        if (!Base64.isBase64(password)) {
            return password;
        }
        try {
            byte[] base64DecodedPwd = Base64.decodeBase64(password);
            boolean isBlock16 = (base64DecodedPwd.length % 16 == 0);  //对称加密数据一定是16的倍数(AES128)
            if (isBlock16 && !new String(base64DecodedPwd, StandardCharsets.UTF_8).matches(PATTERN_COMMONS_CHARS)) { //不可见字符，是加密密码
                byte[] decodePwd = this.getSecurityService().decryptByAES(base64DecodedPwd, this.getPasswordKey(), "CBC");
                String plainPwd = StringUtil.trimToEmpty(new String(decodePwd, StandardCharsets.UTF_8));
                // 解密结果需为可见字符，否则视为无效解密
                if (plainPwd.matches(PATTERN_COMMONS_CHARS)) {
                    return plainPwd;
                }
            }
            // Base64 解码后非加密数据，返回初始 password
        } catch (Exception e) {
            LOGGER.debug(e);
            LOGGER.info("使用明文密码: {}", e.getMessage());
        }
        return password;
    }

    /**
     * 设置加密或未加密的密码，并在初始化时进行解密处理。
     * 如果密码为空，则直接返回，不进行后续处理。
     * @param password 用户提供的密码，可能为加密或明文形式
     */
    public void setPassword(String password) {
        if (StringUtil.isEmpty(password)) {
            return;
        }
        this.password = password;
        // 调用解密方法，初始化时完成解密，确保后续调用直接返回结果
        this.decPassword = decryptPassword(password);
    }

    public void setMinConnections(int minConnections) {
        if (minConnections < 0 || minConnections > 100)  {
            throw new IllegalArgumentException("minConnections must be between 0 and 100");
        }
        this.minConnections = minConnections;
    }

    public void setMaxConnections(int maxConnections) {
        if (maxConnections > 1000 || maxConnections < 1)  {
            throw new IllegalArgumentException("maxConnections must be between 1 and 1000");
        }
        this.maxConnections = maxConnections;
    }

    public void setMaskPattern(String maskPattern) {
        if (StringUtil.isNotBlank(maskPattern)) {
            this.maskPattern = maskPattern;
        }
    }

    /**
     * 设置敏感字段集，写入前按 {@link SqlMasker#validateSensitiveFields(Set)} 校验条目后缀
     * @param sensitiveFields 敏感字段集
     * @throws IllegalArgumentException 条目后缀格式不合法，或同一列给了不同的后缀
     */
    public void setSensitiveFields(Set<String> sensitiveFields) {
        SqlMasker.validateSensitiveFields(sensitiveFields);
        this.sensitiveFields = sensitiveFields;
    }

    public void setIdleTimeoutSec(long idleTimeoutSec) {
        if (idleTimeoutSec < 10 || idleTimeoutSec > 3600)  {
            throw new IllegalArgumentException("idleTimeoutSec must be between 10 and 3600");
        }
        this.idleTimeoutSec = idleTimeoutSec;
    }

    long getIdleTimeoutMillisec() {
        return idleTimeoutSec * 1000;
    }

    public void setCheckoutTimeoutMillisec(long checkoutTimeoutMilliSec) {
        if (checkoutTimeoutMilliSec > 600 * 1000)  {
            throw new IllegalArgumentException("checkoutTimeoutMilliSec must be <= 600000 or 0");
        }
        this.checkoutTimeoutMillisec = checkoutTimeoutMilliSec;
    }

    public void setMaxStatements(int maxStatements) {
        if (maxStatements > 1000 || maxStatements < 10)   {
            throw new IllegalArgumentException("maxStatements must be between 10 and 1000");
        }
        this.maxStatements = maxStatements;
    }

    public void setMaxPreStatements(int maxPreStatements) {
        if (maxPreStatements > 200 || maxPreStatements < 5)  {
            throw new IllegalArgumentException("maxPreStatements must be between 5 and 200");
        }
        this.maxPreStatements = maxPreStatements;
    }

    public void setCheckStatement(String checkStatement) {
        if (StringUtil.isNotBlank(checkStatement)) {
            this.checkStatement = checkStatement;
        }
    }

    public void setJmxLevel(int jmxLevel) {
        if (jmxLevel < 0 || jmxLevel > 2)  {
            throw new IllegalArgumentException("jmxLevel must be between 0 and 2");
        }
        this.jmxLevel = jmxLevel;
    }

    public long getLifetimeMillisec() {
        return getLifetimeSec() * 1000;
    }

    @Override
    public long getLifetimeSec() {
        return lifetimeSec > 0 ? Math.max(lifetimeSec, idleTimeoutSec) : 0;
    }

    @Override
    public void setLifetimeSec(long lifetimeSec) {
        if (lifetimeSec > 24 * 3600)  {
            throw new IllegalArgumentException("lifetimeSec must be <= 86,400 or 0");
        }
        this.lifetimeSec = lifetimeSec;
    }

    public boolean isUseOracleImplicitCache() {
        // return useOracleImplicitCache;
        return false;
    }

    // public void setUseOracleImplicitCache(boolean useOracleImplicitPSCache) {
    //    this.useOracleImplicitCache = useOracleImplicitPSCache;
    // }

    public void setPasswordKey(String passwordAesKey) {
        if (StringUtil.isNotBlank(passwordAesKey)) {
            this.passwordKey = passwordAesKey;
        }
    }

    String getPasswordKey() {
        if (this.passwordKey != null) {
            return this.passwordKey;
        }
        String masteryKey = System.getProperty("JDBC_SECRET_KEY");
        if (masteryKey == null) {
            masteryKey = System.getenv("JDBC_SECRET_KEY");
        }
        if (masteryKey == null) {
            masteryKey = "eyGDTxVGdmJW7YMYclvWzPMGct4j0syHAdWEB3luYCk=";
        }
        this.passwordKey = masteryKey;
        return this.passwordKey;
    }

    SecurityService getSecurityService() {
        if (this.securityService == null) {
            this.securityService = new SecurityServiceLocalImpl();
        }
        return this.securityService;
    }
    
    public HqcpConfig() {
//        this.poolName = poolName;
    }

    public boolean isLoadFromProperties() {
        return properties != null;
    }

    /**
     * 从properties文件读取配置
     */
    public void loadFromProperties(String prop) throws SQLException {
        try {
            this.setProperties(_loadProperties(prop));
            this.properties = prop;
        } catch (MissingResourceException e) {
            throw new SQLException("Invalid jdbc properties: " + prop, e);
        }
    }

    @Override
    public void reloadProperties() {
        if (this.properties != null) {
            try {
                this.loadFromProperties(this.properties);
            } catch (SQLException e) {
                LOGGER.warn("loadProperties {} error: ", this.properties, e);
            }
        }
    }

    private Properties _loadProperties(String prop) {
        Properties _prop = new Properties();
        File pfile = null;
        for (int i = 0; i <= classPaths.length; i++) {
            if (i == classPaths.length) {
                pfile = new File("./" + prop + ".properties");
            } else {
                pfile = new File(classPaths[i] + "/" + prop + ".properties");
            }
            if (pfile.exists()) {
                break;
            }
        }
        if (pfile.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(pfile);
                _prop.load(fis);
            } catch (FileNotFoundException ignored) {
            } catch (IOException e) {
                LOGGER.warn(e);
            } finally {
                // modify by shenjl 修改资源泄露问题
                JdbcUtil.closeQuietly(fis);
            }
        } else {
            ResourceBundle rb = ResourceBundle.getBundle(prop);
            for (String property : HqcpConfig.PROPERTIES) {
                if (rb.containsKey(property)) {
                    _prop.setProperty(property, rb.getString(property));
                }
            }
        }
        return _prop;
    }

    /**
     * Set the location of the jdbc properties file.
     */
    public void setPropertiesLocation(org.springframework.core.io.Resource configLocation) throws IOException {
        InputStream is = configLocation.getInputStream();
        Properties prop = new Properties();
        prop.load(is);
        this.setProperties(prop);
        is.close();
    }

    private boolean getBoolean(Properties prop, String key, boolean defaultValue) {
        return Boolean.parseBoolean(prop.getProperty(key, Boolean.toString(defaultValue)));
    }

    private int getInt(Properties prop, String key, int defaultValue) {
        return Integer.parseInt(prop.getProperty(key, String.valueOf(defaultValue)));
    }

    private long getLong(Properties prop, String key, long defaultValue) {
        return Long.parseLong(prop.getProperty(key, String.valueOf(defaultValue)));
    }

    public void setProperties(Properties prop) {
        setUrl(prop.getProperty("jdbc.url"));
        setUsername(prop.getProperty("jdbc.username"));
        setPassword(prop.getProperty("jdbc.password"));
        driverClassName = prop.getProperty("jdbc.driver", driverClassName);
        driverClassName = prop.getProperty("jdbc.driver-class-name", driverClassName);
        verbose = getBoolean(prop, "jdbc.verbose", verbose);
        printSql = getBoolean(prop, "jdbc.print-sql", printSql);
        maskSql = getBoolean(prop, "jdbc.mask-sql", maskSql);
        setMaskPattern(prop.getProperty("jdbc.mask-pattern", maskPattern));
        String sensitiveFieldsStr = prop.getProperty("jdbc.sensitive-fields");
        if (StringUtil.isNotBlank(sensitiveFieldsStr)) {
            Set<String> newSensitiveFields = new HashSet<>();
            String[] fields = sensitiveFieldsStr.split(",");
            for (String field : fields) {
                if (StringUtil.isNotBlank(field)) {
                    newSensitiveFields.add(field.trim());
                }
            }
            setSensitiveFields(newSensitiveFields);
        }
        maskKeepHead = getInt(prop, "jdbc.mask-keep-head", maskKeepHead);
        maskKeepTail = getInt(prop, "jdbc.mask-keep-tail", maskKeepTail);
        sensitiveFieldsExactMatch = getBoolean(prop, "jdbc.sensitive-fields-exact-match", sensitiveFieldsExactMatch);
        commitOnClose = getBoolean(prop, "jdbc.commit-on-close", commitOnClose);
        setMinConnections(getInt(prop, "jdbc.min-connections", minConnections));
        setMaxConnections(getInt(prop, "jdbc.max-connections", maxConnections));
        setIdleTimeoutSec(getLong(prop, "jdbc.idle-timeout-sec", idleTimeoutSec));
        setCheckoutTimeoutMillisec(getLong(prop, "jdbc.checkout-timeout-millisec", checkoutTimeoutMillisec));
        setLifetimeSec(getLong(prop, "jdbc.lifetime-sec", lifetimeSec));
        setCheckStatement(prop.getProperty("jdbc.check-statement", checkStatement));
        setMaxStatements(getInt(prop, "jdbc.max-statements", maxStatements));
        setMaxPreStatements(getInt(prop, "jdbc.max-pre-statements", maxPreStatements));
        setJmxLevel(getInt(prop, "jdbc.jmx-level", jmxLevel));
        transactionMode = getBoolean(prop, "jdbc.transaction-mode", transactionMode);
        lazyInit = getBoolean(prop, "jdbc.lazy-init", lazyInit);
        setInfoSqlThreshold(getLong(prop, "jdbc.info-sql-threshold", infoSqlThreshold));
        setWarnSqlThreshold(getLong(prop, "jdbc.warn-sql-threshold", warnSqlThreshold));
        // useOracleImplicitCache = getBoolean(prop, "jdbc.use-oracle-implicit-cache", useOracleImplicitCache);
        setQueryTimeout(getInt(prop, "jdbc.query-timeout", queryTimeout));
        setConnectionInfo(prop.getProperty("jdbc.connection-info"));
        setPasswordKey(prop.getProperty("jdbc.password-key"));
    }

    private static final String[] PROPERTIES = new String[] {
            "jdbc.url", "jdbc.username", "jdbc.password", "jdbc.driver", "jdbc.driver-class-name",
            "jdbc.verbose", "jdbc.print-sql", "jdbc.mask-sql", "jdbc.mask-pattern", "jdbc.sensitive-fields",
            "jdbc.mask-keep-head", "jdbc.mask-keep-tail", "jdbc.sensitive-fields-exact-match", "jdbc.commit-on-close", "jdbc.min-connections", "jdbc.max-connections",
            "jdbc.idle-timeout-sec", "jdbc.checkout-timeout-millisec", "jdbc.lifetime-sec",
            "jdbc.check-statement", "jdbc.max-statements", "jdbc.max-pre-statements", "jdbc.jmx-level", "jdbc.transaction-mode", "jdbc.lazy-init",
            "jdbc.info-sql-threshold", "jdbc.warn-sql-threshold", /* "jdbc.use-oracle-implicit-cache", */
            "jdbc.query-timeout","jdbc.connection-info", "jdbc.password-key",
    };

    void printConfig(Logger logger) {
        if (logger == null) {
            logger = LOGGER;
        }
        getPassword();  // decide if password encrypted
        logger.info("url                     = '" + url + "'");
        logger.info("username                = '" + username + "'");
        logger.info("password                = '" + (decPassword == null ? "******" : password) + "'");
        logger.info("minConnections          = " + minConnections);
        logger.info("maxConnections          = " + maxConnections);
        logger.info("maxStatements           = " + maxStatements);
        logger.info("maxPreStatements        = " + maxPreStatements);
        logger.info("idleTimeoutSec          = " + idleTimeoutSec);
        if (checkoutTimeoutMillisec <= 0) {
            logger.warn("checkoutTimeoutMillisec = " + checkoutTimeoutMillisec);
        } else {
            logger.info("checkoutTimeoutMillisec = " + checkoutTimeoutMillisec);
        }
        logger.info("lifetimeSec             = " + getLifetimeSec());
        logger.info("commitOnClose           = " + commitOnClose);
        logger.info("verbose                 = " + verbose);
        logger.info("printSql                = " + printSql);
        logger.info("maskSql                 = " + maskSql);
        logger.info("maskPattern             = " + maskPattern);
        logger.info("sensitiveFields         = " + sensitiveFields);
        logger.info("maskKeepHead            = " + maskKeepHead);
        logger.info("maskKeepTail            = " + maskKeepTail);
        logger.info("sensitiveFieldsExactMatch = " + sensitiveFieldsExactMatch);
        logger.info("checkStatement          = '" + checkStatement + "'");
        logger.info("lazyInit                = " + lazyInit);
        logger.info("infoSqlThreshold        = " + infoSqlThreshold);
        logger.info("warnSqlThreshold        = " + getWarnSqlThreshold());
        logger.info("queryTimeout            = " + queryTimeout);
        logger.info("jmxLevel                = " + jmxLevel);
        logger.info("transactionMode         = " + transactionMode);
    }

}

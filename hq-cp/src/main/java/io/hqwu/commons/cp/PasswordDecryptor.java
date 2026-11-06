package io.hqwu.commons.cp;

/**
 * 数据库口令解密器，由使用方按需实现并注入 {@link HqcpConfig}（Spring Boot 下声明为 bean 即可被 starter 装配）。
 * <p>
 * 注入后完全取代 hq-cp 内置的 AES 启发式解密（{@code jdbc.password-key} / {@code JDBC_SECRET_KEY}
 * 及内置默认密钥都不再参与），配置里的口令原值原样交给本接口处理。
 * </p>
 * <ul>
 *   <li>入参是配置的口令原值，保证非空；</li>
 *   <li>返回值是实际用于连接数据库的明文口令，不可为 {@code null} 或空串，否则 hq-cp 抛 {@link IllegalStateException}；</li>
 *   <li>遇到不认识的格式或解不开时，由实现自己决定原样返回（按明文处理）还是抛异常；</li>
 *   <li>抛出的异常会原样外抛，导致数据源初始化失败，hq-cp 不吞；</li>
 *   <li>异常会进启动失败日志，实现方抛出的异常消息不要包含入参（密文）或明文。</li>
 * </ul>
 * <p>
 * 解密在首次取口令时惰性执行并缓存（连接池初始化时即触发）。重新设置口令后会再次调用，
 * 但只在连接池首次取连接参数之前有效，之后口令已缓存进连接参数。
 * </p>
 *
 * @author taige (Wu, Hongqiang)
 * @since 1.17
 */
@FunctionalInterface
public interface PasswordDecryptor {

    /**
     * 解密配置的口令。
     *
     * @param configuredPassword 配置里的口令原值（非空）
     * @return 用于连接数据库的明文口令
     */
    String decrypt(String configuredPassword);
}

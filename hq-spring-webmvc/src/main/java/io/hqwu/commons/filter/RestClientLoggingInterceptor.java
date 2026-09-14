package io.hqwu.commons.filter;

import io.hqwu.commons.util.ClassUtil;
import io.hqwu.commons.util.Formatter;
import io.hqwu.commons.util.Logger;
import io.hqwu.commons.util.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * RestTemplate 的 REST 客户端日志拦截器
 *
 * <p>用于拦截 {@link org.springframework.web.client.RestTemplate} 发起的 HTTP 请求和响应，
 * 并记录详细的调用日志，包括请求方法、URI、请求头、请求体、响应状态码、响应头、响应体以及耗时等信息。</p>
 *
 * <p>日志级别：
 * <ul>
 *   <li>INFO 级别：记录请求的基本信息（方法、URI、请求头）和响应的基本信息（状态码、响应头、耗时）</li>
 *   <li>DEBUG 级别：额外记录请求体和响应体的详细内容（仅文本类型，二进制数据会记录类型和长度）</li>
 * </ul>
 * </p>
 *
 * <p><strong>关于响应体：</strong>DEBUG 级别记录文本响应体时，本拦截器会把响应体缓冲一份并返回
 * 可重复读取的响应，因此无论调用方是否用
 * {@link org.springframework.http.client.BufferingClientHttpRequestFactory} 包装请求工厂，
 * 业务代码都能读到完整内容。代价是该次调用会在堆上多留一份完整响应体（调用方若也做了缓冲则共两份），
 * 响应体很大时请酌情控制本拦截器的日志级别。</p>
 *
 * <p>示例用法：
 * <pre>{@code
 * RestTemplate restTemplate = new RestTemplate(
 *     new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
 * );
 * restTemplate.getInterceptors().add(
 *     new RestClientLoggingInterceptor("MyRestClient")
 * );
 * }</pre>
 * </p>
 *
 * @author taige
 * @see org.springframework.http.client.ClientHttpRequestInterceptor
 * @see org.springframework.web.client.RestTemplate
 * @see org.springframework.http.client.BufferingClientHttpRequestFactory
 * @since 2020/3/31
 */
public class RestClientLoggingInterceptor implements ClientHttpRequestInterceptor {
    private final Logger LOGGER;

    private final String logTag;
    private final URI baseUri;


    public RestClientLoggingInterceptor(String loggerName) {
        this.logTag = ClassUtil.getShortClassName(loggerName);
        this.LOGGER = LoggerFactory.getLogger(loggerName);
        this.baseUri = null;
    }

    public RestClientLoggingInterceptor(String loggerName, URI baseUri) {
        this.logTag = ClassUtil.getShortClassName(loggerName);
        this.LOGGER = LoggerFactory.getLogger(loggerName);
        this.baseUri = baseUri;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution) throws IOException {

        String requestUri = baseUri == null ? request.getURI().toString() : "/" + baseUri.relativize(request.getURI());
        HttpMethod method = request.getMethod();
        
        LOGGER.info("[%s]%sing : %s, Headers: %s", logTag, method, requestUri, request.getHeaders());
        if (LOGGER.isDebugEnabled() && ! HttpMethod.GET.equals(method) && ! HttpMethod.HEAD.equals(method)) {
            if (isTextType(request.getHeaders().getContentType())) {
                LOGGER.debug("[%s]Request body: %s", logTag, new String(body, StandardCharsets.UTF_8));
            } else {
                LOGGER.debug("[%s]Request body: [Binary data] Content-Type: %s, Length: %d", logTag, request.getHeaders().getContentType(), body.length);
            }
        }

        long startMS = System.currentTimeMillis();
        ClientHttpResponse response = execution.execute(request, body);
        HttpHeaders headers = response.getHeaders();
        LOGGER.info("[%s]%sed : %s, Status: %s, Headers: %s, time: %sms", logTag,
                method, requestUri, response.getStatusCode(), headers, Formatter.formatNS(System.currentTimeMillis() - startMS));

        if (LOGGER.isDebugEnabled()) {
            if (isTextType(headers.getContentType())) {
                // 记日志会把响应流读走，统一缓冲一份再往下传，保证业务代码仍能读到完整内容
                response = new BufferedClientHttpResponse(response);
                Charset contentCharset = Optional.ofNullable(Optional.ofNullable(headers.getContentType())
                        .orElse(MediaType.APPLICATION_JSON).getCharset()).orElse(StandardCharsets.UTF_8);
                String responseBody = StreamUtils.copyToString(response.getBody(), contentCharset);
                LOGGER.debug("[%s]Response body: %s", logTag, responseBody);
            } else {
                LOGGER.debug("[%s]Response body: [Binary data] Content-Type: %s, Length: %d", logTag, headers.getContentType(), headers.getContentLength());
            }
        }

        return response;
    }

    /**
     * 把响应体整体缓冲下来，使其可以被重复读取；其余行为一律委托给原响应。
     *
     * <p>只在 DEBUG 要记录响应体时使用。此时会在堆上多留一份完整响应体的副本：
     * 调用方若已用 {@link org.springframework.http.client.BufferingClientHttpRequestFactory}
     * 包装请求工厂，响应体会同时存在两份。响应体很大时请酌情控制本拦截器的日志级别。
     *
     * <p>不去推断原响应是否已经可重复读取——{@link ClientHttpResponse} 并无
     * “每次 {@link #getBody()} 都给出独立的流”这类契约，装饰器完全可能每次返回新的包装流、
     * 底层却共用同一条一次性网络流，据此推断会把业务侧的响应体读没。因此一律从单次
     * {@code getBody()} 缓冲。
     */
    private static class BufferedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedClientHttpResponse(ClientHttpResponse delegate) throws IOException {
            this.delegate = delegate;
            this.body = StreamUtils.copyToByteArray(delegate.getBody());
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private boolean isTextType(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        return "text".equals(mediaType.getType()) || mediaType.getSubtype().contains("json") || mediaType.getSubtype().contains("xml") || mediaType.getSubtype().contains("html");
    }

}

package io.hqwu.commons.boot4compat;

import com.sun.net.httpserver.HttpServer;
import io.hqwu.commons.filter.RestClientLoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * hq-spring-webmvc 的 {@link RestClientLoggingInterceptor} 在 Spring Boot 4 / Framework 7 下的可用性。
 * <p>
 * 它依赖 {@code ClientHttpRequestInterceptor}、{@code ClientHttpResponse} 与 {@code StreamUtils}，
 * DEBUG 级别下还会读一遍响应体。这里起一个真实 HTTP 服务端到端验证，配与不配
 * {@link BufferingClientHttpRequestFactory} 两种情况业务代码都应读到完整响应。
 */
class RestClientLoggingInterceptorBoot4Test {

    private static final String RESPONSE_BODY = "{\"res\":\"ok\"}";

    /** 请求工厂自身已做缓冲的配置（拦截器仍会再缓冲一份，这里验的是两层缓冲下响应依旧完整）。 */
    @Test
    void bufferedRestTemplateKeepsBodyReadableAfterDebugLogging() throws Exception {
        HttpServer server = startServer();
        try {
            // logback-test.xml 把 boot4-probe.RestClient 设成 DEBUG，确保走到读 body 的分支
            RestTemplate restTemplate = new RestTemplate(
                    new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));
            restTemplate.getInterceptors().add(new RestClientLoggingInterceptor("boot4-probe.RestClient"));

            String body = restTemplate.postForObject(urlOf(server), "{\"req\":1}", String.class);

            assertThat(body).isEqualTo(RESPONSE_BODY);
        } finally {
            server.stop(0);
        }
    }

    /** 没配缓冲工厂时，拦截器会自行缓冲响应体，业务代码同样读得到。 */
    @Test
    void unbufferedRestTemplateStillKeepsBodyAfterDebugLogging() throws Exception {
        HttpServer server = startServer();
        try {
            RestTemplate restTemplate = new RestTemplate(); // 有意不包 BufferingClientHttpRequestFactory
            restTemplate.getInterceptors().add(new RestClientLoggingInterceptor("boot4-probe.RestClient"));

            String body = restTemplate.postForObject(urlOf(server), "{\"req\":1}", String.class);

            assertThat(body).isEqualTo(RESPONSE_BODY);
        } finally {
            server.stop(0);
        }
    }

    /** 拦截器在 spring-test 的 MockRestServiceServer 组合下也能正常注册与执行。 */
    @Test
    void interceptorWiredIntoRestTemplateKeepsResponseIntact() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new RestClientLoggingInterceptor("boot4-probe.RestClient"));
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://example.com/api"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(RESPONSE_BODY, MediaType.APPLICATION_JSON));

        String body = restTemplate.postForObject("http://example.com/api", "{\"req\":1}", String.class);

        assertThat(body).isEqualTo(RESPONSE_BODY);
        server.verify();
    }

    private HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            byte[] resp = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }

    private String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }
}

package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** The HTTP client the sports sources share: the configured timeouts, and redirects followed. */
final class SportsHttp {
    private SportsHttp() {
    }

    /**
     * A client with {@code sports.connect-timeout} and {@code sports.read-timeout}. It
     * follows redirects because GitHub serves nflverse's release files through one.
     */
    static RestClient client(SportsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
}

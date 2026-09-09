package com.estradaplay.comunista;

import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * NETWORK_DNS_FALLBACK_V505
 *
 * Android system DNS remains the primary resolver. This helper is used only after an
 * UnknownHostException, which can happen on some mobile networks even while Chrome resolves the
 * same hostname through Secure DNS. The retry keeps the original HTTPS hostname in the URL, so
 * certificate/SNI validation is never weakened or bypassed.
 */
final class SecureDnsHttpFallback {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Dns RESILIENT_DNS = buildDns();

    private SecureDnsHttpFallback() {}

    static Result execute(String method,
                          String target,
                          JSONObject data,
                          int connectTimeoutMs,
                          int readTimeoutMs,
                          int maxChars,
                          Map<String, String> requestHeaders) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .dns(RESILIENT_DNS)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build();

        Request.Builder b = new Request.Builder().url(target);
        if (requestHeaders != null) {
            for (Map.Entry<String, String> e : requestHeaders.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (key != null && !key.trim().isEmpty() && value != null && !value.trim().isEmpty()) {
                    b.header(key, value);
                }
            }
        }

        if (data != null) {
            RequestBody body = RequestBody.create(JSON, data.toString());
            b.method(method, body);
        } else {
            b.method(method, null);
        }

        try (Response response = client.newCall(b.build()).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : responseBody.string();
            if (body.length() > maxChars) throw new IllegalStateException("Resposta maior que o limite permitido");
            Headers headers = response.headers();
            return new Result(response.code(), body, headers == null ? Collections.emptyMap() : headers.toMultimap());
        }
    }

    private static Dns buildDns() {
        try {
            Dns bootstrapDns = hostname -> {
                if ("dns.google".equalsIgnoreCase(hostname)) {
                    return Arrays.asList(
                            InetAddress.getByName("8.8.8.8"),
                            InetAddress.getByName("8.8.4.4")
                    );
                }
                return Dns.SYSTEM.lookup(hostname);
            };

            OkHttpClient bootstrapClient = new OkHttpClient.Builder()
                    .dns(bootstrapDns)
                    .followRedirects(true)
                    .build();

            DnsOverHttps doh = new DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url(HttpUrl.get("https://dns.google/dns-query"))
                    .post(true)
                    .build();

            return hostname -> {
                try {
                    List<InetAddress> normal = Dns.SYSTEM.lookup(hostname);
                    if (normal != null && !normal.isEmpty()) return normal;
                } catch (UnknownHostException ignored) {}

                List<InetAddress> secure = doh.lookup(hostname);
                if (secure == null || secure.isEmpty()) {
                    throw new UnknownHostException("DNS seguro não encontrou " + hostname);
                }
                return secure;
            };
        } catch (Throwable unavailable) {
            return Dns.SYSTEM;
        }
    }

    static final class Result {
        final int code;
        final String body;
        final Map<String, List<String>> headers;

        Result(int code, String body, Map<String, List<String>> headers) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }
    }
}

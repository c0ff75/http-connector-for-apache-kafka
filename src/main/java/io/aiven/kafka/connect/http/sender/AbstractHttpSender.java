/*
 * Copyright 2019 Aiven Oy and http-connector-for-apache-kafka project contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.http.sender;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.connect.errors.ConnectException;

import io.aiven.kafka.connect.http.config.HttpSinkConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class AbstractHttpSender {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpSender.class);
    protected final HttpClient httpClient;

    protected final HttpSinkConfig config;
    protected final HttpRequestBuilder httpRequestBuilder;

    protected AbstractHttpSender(
            final HttpSinkConfig config, final HttpRequestBuilder httpRequestBuilder, final HttpClient httpClient
    ) {
        Objects.requireNonNull(config, "config should not be null");
        this.config = config;
        this.httpRequestBuilder = httpRequestBuilder;
        this.httpClient = httpClient;
    }

    public final HttpResponse<String> send(final String body) {
        final var requestBuilderWithPayload =
                httpRequestBuilder.build(config).POST(HttpRequest.BodyPublishers.ofString(body));
        return sendWithRetries(requestBuilderWithPayload, HttpResponseHandler.ON_HTTP_ERROR_RESPONSE_HANDLER,
                config.maxRetries());
    }

    /**
     * Sends a HTTP body using {@code httpSender}, respecting the configured retry policy.
     *
     * @return whether the sending was successful.
     */
    protected HttpResponse<String> sendWithRetries(
            final Builder requestBuilderWithPayload, final HttpResponseHandler httpResponseHandler,
            final int retriesNumber
    ) {
        int remainRetries = retriesNumber;
        while (remainRetries >= 0) {
            try {
                try {
                    final var response =
                            httpClient.send(requestBuilderWithPayload.build(), HttpResponse.BodyHandlers.ofString());
                    log.debug("Server replied with status code {} and body {}", response.statusCode(), response.body());
                    httpResponseHandler.onResponse(response, remainRetries);
                    return response;
                } catch (final IOException e) {
                    log.info("Sending failed, will retry in {} ms ({} retries remain)", config.retryBackoffMs(),
                            remainRetries, e);
                    remainRetries -= 1;
                    TimeUnit.MILLISECONDS.sleep(config.retryBackoffMs());
                }
            } catch (final InterruptedException e) {
                log.error("Sending failed due to InterruptedException, stopping", e);
                throw new ConnectException(e);
            }
        }
        log.error("Sending failed and no retries remain, stopping");
        throw new ConnectException("Sending failed and no retries remain, stopping");
    }

}

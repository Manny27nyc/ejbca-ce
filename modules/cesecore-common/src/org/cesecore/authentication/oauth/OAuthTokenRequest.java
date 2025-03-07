/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.cesecore.authentication.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MIME;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.log4j.Logger;
import org.cesecore.util.FileTools;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Requests an OAuth token. See <a href="https://tools.ietf.org/html/rfc6749#section-4.1.3">RFC 6749 section 4.1.3</a>.
 *
 */
public class OAuthTokenRequest {

    private static final Logger log = Logger.getLogger(OAuthTokenRequest.class);

    private int timeoutMillis = 20_000;
    private int maxResponseBytes = 1024*1024; // 1 MiB
    private String redirectUri;
    private String clientId;
    private String uri;
    private String clientSecret;

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(final int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    /** Sets the maximum response size (in bytes) from the authorization server. Default: 1 MiB. */
    public void setMaxResponseBytes(final int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    /** The URI of the destination page after login (typically the current page) */
    public void setRedirectUri(final String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(final String clientId) {
        this.clientId = clientId;
    }

    public String getUri() {
        return uri;
    }

    /** Sets the URI of the authorization server. Must include the "/token" part at the end. */
    public void setUri(final String uri) {
        this.uri = uri;
    }
    
    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(final String clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * Requests the OAuth token from the authorization server.
     *
     * @param authCode Authorization code received from authorization server.
     * @param isRefresh true, if provided code is refresh token, false, if it is after login code
     * @return Response object, including the OAuth token.
     * @throws IOException On network errors or malformed server response.
     */
    public OAuthGrantResponseInfo execute(final String authCode, boolean isRefresh) throws IOException {
        final RequestConfig reqcfg = RequestConfig.custom()
                .setConnectionRequestTimeout(timeoutMillis)
                .setConnectTimeout(timeoutMillis)
                .setSocketTimeout(timeoutMillis)
                .build();
        try (CloseableHttpClient httpClient = HttpClientBuilder.create()
                .disableConnectionState()
                .disableCookieManagement()
                .disableRedirectHandling()
                .setDefaultRequestConfig(reqcfg)
                .setUserTokenHandler(null)
                .useSystemProperties()
                .build()) {
            return execute(authCode, httpClient, isRefresh);
        }
    }

    /**
     * Requests the OAuth token from the authorization server.
     * This method uses a custom HttpClient.
     *
     * @param authCode Authorization code received from authorization server.
     * @param httpClient The HttpClient to use for the request.
     * @param isRefresh true, if provided code is refresh token, false, if it is after login code
     * @return Response object, including the OAuth token.
     * @throws IOException On network errors or malformed server response.
     */
    public OAuthGrantResponseInfo execute(final String authCode, final CloseableHttpClient httpClient, final boolean isRefresh) throws IOException {
        final List<NameValuePair> params = new ArrayList<>();
        if (isRefresh) {
            params.add(new BasicHeader("grant_type", "refresh_token"));
            params.add(new BasicHeader("refresh_token", authCode));
        } else {
            params.add(new BasicHeader("grant_type", "authorization_code"));
            params.add(new BasicHeader("code", authCode));
        }
        params.add(new BasicHeader("redirect_uri", redirectUri));
        params.add(new BasicHeader("client_id", clientId));
        params.add(new BasicHeader("client_secret", clientSecret));
        final HttpPost post = new HttpPost();
        post.setHeader("Content-Type", "application/x-www-form-urlencoded");
        try {
            post.setURI(new URI(uri));
        } catch (URISyntaxException e) {
            log.warn("Token URL is malformed: " + uri);
            throw new IllegalStateException(e);
        }
        post.setEntity(new UrlEncodedFormEntity(params));
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                final HttpEntity entity = response.getEntity();
                if (entity != null) {
                    final byte[] responseBytes = FileTools.readStreamToByteArray(entity.getContent(), -1, maxResponseBytes);
                    String content = new String(responseBytes, StandardCharsets.UTF_8);
                    log.info("Failed to get token from authorization server. HTTP status code " + response.getStatusLine().getStatusCode()
                            + " reason: " + response.getStatusLine().getReasonPhrase()
                            + " response content " + content);
                }
                throw new IOException("Failed to get token from authorization server. HTTP status code " + response.getStatusLine().getStatusCode());
            }
            final Header[] contentType = response.getHeaders(MIME.CONTENT_TYPE);
            if (contentType == null || contentType.length != 1) {
                throw new IOException("Missing Content-Type header from authorization server.");
            }
            final String mimeType = contentType[0].getValue().toLowerCase(Locale.ROOT);
            if (!mimeType.matches("application/json( *; *charset *= *utf-8)?")) {
                throw new IOException("Invalid MIME type on response from authorization server: " + mimeType);
            }
            final HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new IOException("Received empty HTTP response from authorization server.");
            }
            final byte[] responseBytes = FileTools.readStreamToByteArray(entity.getContent(), -1, maxResponseBytes);
            final JSONParser parser = new JSONParser();
            try {
                final JSONObject json = (JSONObject) parser.parse(new String(responseBytes, StandardCharsets.UTF_8));
                final OAuthGrantResponseInfo token = new OAuthGrantResponseInfo();
                token.setAccessToken((String) json.get("access_token"));
                token.setTokenType((String) json.get("token_type"));
                if (json.containsKey("expires_in")) {
                    token.setExpiresIn(((Number) json.get("expires_in")).longValue());
                }
                token.setRefreshToken((String) json.get("refresh_token"));
                token.setScope((String) json.get("scope"));
                return token;
            } catch (ParseException e) {
                throw new IOException("Failed to parse JSON response from authorization server: " + e.getMessage(), e);
            }
        }
    }

}

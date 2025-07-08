package scouter.plugin.server.alert.messenger.works;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.annotations.SerializedName;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import scouter.server.Configure;
import scouter.server.Logger;

/**
 * NaverWorks API 인증 처리 클래스
 */
public class WorksAuth {
    private static final String AUTH_API_URL = "https://auth.worksmobile.com/oauth2/v2.0/token";
    private static final long JWT_EXPIRATION = 3600; // 1시간
    private static final long TOKEN_REFRESH_THRESHOLD = 300; // 5분

    private final Configure conf;

    private String accessToken;
    private long tokenExpiration;

    public WorksAuth(Configure conf) {
        this.conf = conf;
    }

    /**
     * Access Token을 가져옵니다.
     * 토큰이 만료되었거나 만료가 임박한 경우 새로운 토큰을 발급받습니다.
     */
    public String getAccessToken() {
        if (shouldRefreshToken()) {
            refreshAccessToken();
        }
        return accessToken;
    }

    private boolean shouldRefreshToken() {
        return accessToken == null ||
                System.currentTimeMillis() >= (tokenExpiration - TOKEN_REFRESH_THRESHOLD * 1000);
    }

    private void refreshAccessToken() {
        try {
            String clientId = conf.getValue("ext_plugin_works_client_id");
            String clientSecret = conf.getValue("ext_plugin_works_client_secret");
            String serviceAccount = conf.getValue("ext_plugin_works_service_account");
            String privateKeyPath = conf.getValue("ext_plugin_works_private_key");

            // JWT 생성
            String jwt = createJWT(clientId, serviceAccount, privateKeyPath);

            // access token 요청 파라미터 설정
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"));
            params.add(new BasicNameValuePair("client_id", clientId));
            params.add(new BasicNameValuePair("client_secret", clientSecret));
            params.add(new BasicNameValuePair("assertion", jwt));
            params.add(new BasicNameValuePair("scope", "bot bot.message bot.read"));

            println("Auth request payload: " + params);

            HttpPost post = new HttpPost(AUTH_API_URL);
            post.addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            post.setEntity(new UrlEncodedFormEntity(params));

            try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
                HttpResponse response = client.execute(post);
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                println("Auth response: " + responseBody);

                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> responseMap = mapper.readValue(responseBody, Map.class);

                    TokenResponse tokenResponse = new TokenResponse();
                    tokenResponse.accessToken = (String) responseMap.get("access_token");
                    tokenResponse.expiresIn = Long.valueOf(responseMap.get("expires_in").toString());

                    this.accessToken = tokenResponse.accessToken;
                    this.tokenExpiration = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000);

                    println("Works access token refreshed successfully");
                } else {
                    Logger.println("Failed to refresh Works access token: " + responseBody);
                }
            }
        } catch (Exception e) {
            Logger.printStackTrace(e);
        }
    }

    private String createJWT(String clientId, String serviceAccount, String privateKeyPath) throws Exception {
        // PEM 파일 읽기
        String privateKeyStr = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(privateKeyPath)),
                StandardCharsets.UTF_8);

        try {
            PrivateKey privateKey = parsePrivateKey(privateKeyStr);

            Instant now = Instant.now();
            Instant expiration = now.plusSeconds(JWT_EXPIRATION);

            return Jwts.builder()
                    .setIssuer(clientId)
                    .setIssuer(clientId)
                    .setSubject(serviceAccount)
                    .setIssuedAt(Date.from(now))
                    .setExpiration(Date.from(expiration))
                    .signWith(privateKey, SignatureAlgorithm.RS256)
                    .compact();
        } catch (IllegalArgumentException e) {
            Logger.println("Base64 decoding error. Please check the private key format.");
            Logger.printStackTrace(e);
            throw e;
        } catch (Exception e) {
            Logger.println("Error creating JWT token.");
            Logger.printStackTrace(e);
            throw e;
        }
    }

    private PrivateKey parsePrivateKey(String privateKeyString) throws Exception {
        // PEM 형식의 개인키에서 헤더/푸터 제거 및 Base64 디코딩
        String privateKeyPEM = privateKeyString
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] decoded = Base64.getDecoder().decode(privateKeyPEM);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(spec);
    }

    private static class TokenResponse {
        @SerializedName("access_token")
        String accessToken;

        @SerializedName("expires_in")
        long expiresIn;
    }

    private void println(Object o) {
        if (conf.getBoolean("ext_plugin_works_debug", false)) {
            System.out.println(o);
            Logger.println(o);
        }
    }
}
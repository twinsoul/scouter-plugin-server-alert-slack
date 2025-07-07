package scouter.plugin.server.alert.messenger.works;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import scouter.server.Configure;
import scouter.server.Logger;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Works API 인증 처리 클래스
 */
public class WorksAuth {
    private static final String AUTH_API_URL = "https://auth.worksmobile.com/oauth2/v2.0/token";
    private static final long JWT_EXPIRATION = 3600; // 1시간
    private static final long TOKEN_REFRESH_THRESHOLD = 300; // 5분

    private final Configure conf;
    private final Gson gson;

    private String accessToken;
    private long tokenExpiration;

    public WorksAuth(Configure conf) {
        this.conf = conf;
        this.gson = new Gson();
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
            String privateKeyStr = conf.getValue("ext_plugin_works_private_key");

            // JWT 생성
            String jwt = createJWT(serviceAccount, privateKeyStr);

            // Access Token 요청
            Map<String, String> params = new HashMap<>();
            params.put("assertion", jwt);
            params.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            params.put("client_id", clientId);
            params.put("client_secret", clientSecret);
            params.put("scope", "bot");

            String payload = gson.toJson(params);

            if (conf.getBoolean("ext_plugin_works_debug", false)) {
                Logger.println("Auth request payload: " + payload);
            }

            HttpPost post = new HttpPost(AUTH_API_URL);
            post.addHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

            try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
                HttpResponse response = client.execute(post);
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                if (conf.getBoolean("ext_plugin_works_debug", false)) {
                    Logger.println("Auth response: " + responseBody);
                }

                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    TokenResponse tokenResponse = gson.fromJson(responseBody, TokenResponse.class);
                    this.accessToken = tokenResponse.accessToken;
                    this.tokenExpiration = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000);

                    if (conf.getBoolean("ext_plugin_works_debug", false)) {
                        Logger.println("Works access token refreshed successfully");
                    }
                } else {
                    Logger.println("Failed to refresh Works access token: " + responseBody);
                }
            }
        } catch (Exception e) {
            Logger.printStackTrace(e);
        }
    }

    private String createJWT(String serviceAccount, String privateKeyStr) throws Exception {
        if (conf.getBoolean("ext_plugin_works_debug", false)) {
            Logger.println("Original private key: " + privateKeyStr);
        }

        // Private Key 문자열 정리
        privateKeyStr = privateKeyStr
                .replace("\\n", "\n") // 설정 파일의 \n을 실제 개행문자로 변환
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", ""); // 모든 공백 문자 제거

        if (conf.getBoolean("ext_plugin_works_debug", false)) {
            Logger.println("Cleaned private key: " + privateKeyStr);
        }

        Logger.println("--------------------------------");
        Logger.println(privateKeyStr);
        Logger.println("--------------------------------");

        try {
            // Base64 디코딩
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyStr);

            // PKCS8 형식의 Private Key 생성
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            Instant now = Instant.now();
            Instant expiration = now.plusSeconds(JWT_EXPIRATION);

            return Jwts.builder()
                    .setIssuer(serviceAccount)
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

    private static class TokenResponse {
        @SerializedName("access_token")
        String accessToken;

        @SerializedName("expires_in")
        long expiresIn;
    }
}
package scouter.plugin.server.alert.messenger.works;

import com.google.gson.Gson;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import scouter.lang.AlertLevel;
import scouter.lang.pack.AlertPack;
import scouter.plugin.server.alert.messenger.MessengerPlugin;
import scouter.server.Configure;
import scouter.server.Logger;
import scouter.server.core.AgentManager;

/**
 * Naver Works Bot 메신저 플러그인
 */
public class WorksBotPlugin implements MessengerPlugin {
    private final Configure conf = Configure.getInstance();
    private final Gson gson = new Gson();
    private final WorksAuth worksAuth;

    public WorksBotPlugin() {
        this.worksAuth = new WorksAuth(conf);
    }

    @Override
    public boolean isEnabled(String objType) {
        return conf.getBoolean("ext_plugin_works_send_alert", false);
    }

    @Override
    public void sendAlert(AlertPack pack, String objType) {
        if (!isEnabled(objType)) {
            return;
        }

        int level = conf.getInt("ext_plugin_works_level", 0);
        if (level > pack.level) {
            return;
        }

        new Thread(() -> {
            try {
                String botId = conf.getValue("ext_plugin_works_bot_id");
                String channelId = conf.getValue("ext_plugin_works_channel_id");
                String userId = "biscuit_@woongjin.co.kr";
                // String defaultApiEndpoint = "https://www.worksapis.com/v1.0/bots/" + botId +
                // "/channels/" + channelId
                // + "/messages";
                String defaultApiEndpoint = "https://www.worksapis.com/v1.0/bots/" + botId + "/users/" + userId
                        + "/messages";
                String apiEndpoint = conf.getValue("ext_plugin_works_api_endpoint", defaultApiEndpoint);

                // Access Token 가져오기
                String accessToken = worksAuth.getAccessToken();
                if (accessToken == null) {
                    Logger.println("Failed to get Works access token");
                    return;
                }

                // 에이전트 이름 조회
                String name = AgentManager.getAgentName(pack.objHash);
                if (name == null) {
                    name = "N/A";
                    if (pack.message.endsWith("connected.")) {
                        int idx = pack.message.indexOf("connected");
                        if (pack.message.indexOf("reconnected") > -1) {
                            name = pack.message.substring(0, idx - 6);
                        } else {
                            name = pack.message.substring(0, idx - 4);
                        }
                    }
                }

                // 메시지 생성
                WorksBotMessage message = new WorksBotMessage();
                message.content = new WorksBotMessage.Content();
                message.content.type = "text";
                message.content.text = String.format(
                        "[Scouter Alert]\n" +
                                "- Type: %s\n" +
                                "- Name: %s\n" +
                                "- Level: %s\n" +
                                "- Title: %s\n" +
                                "- Message: %s",
                        pack.objType.toUpperCase(),
                        name,
                        AlertLevel.getName(pack.level),
                        pack.title,
                        pack.message);

                String payload = gson.toJson(message);

                // 디버그 로깅
                if (conf.getBoolean("ext_plugin_works_debug", false)) {
                    Logger.println("Works Bot API Endpoint: " + apiEndpoint);
                    Logger.println("Works Bot Payload: " + payload);
                }

                // HTTP 요청 설정
                HttpPost post = new HttpPost(apiEndpoint);
                post.addHeader("Content-Type", "application/json");
                post.addHeader("Authorization", "Bearer " + accessToken);
                post.setEntity(new StringEntity(payload, "utf-8"));

                // HTTP 요청 실행
                try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
                    HttpResponse response = client.execute(post);

                    if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK ||
                            response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED) {
                        Logger.println("Works Bot message sent successfully.");
                    } else {
                        Logger.println("Works Bot message sending failed. Response: " +
                                EntityUtils.toString(response.getEntity(), "UTF-8"));
                    }
                }
            } catch (Exception e) {
                Logger.printStackTrace(e);
            }
        }).start();
    }
}
package scouter.plugin.server.alert.messenger.works;

/**
 * Works Bot 메시지 포맷
 */
public class WorksBotMessage {
    public Content content;

    public static class Content {
        public String type;
        public String text;
    }
}
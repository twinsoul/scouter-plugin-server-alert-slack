package scouter.plugin.server.alert.messenger;

import scouter.lang.pack.AlertPack;

/**
 * 메신저 플러그인 인터페이스
 */
public interface MessengerPlugin {
    /**
     * 알림 메시지를 전송합니다.
     * 
     * @param pack    알림 정보
     * @param objType 객체 타입
     */
    void sendAlert(AlertPack pack, String objType);

    /**
     * 메신저 설정이 유효한지 확인합니다.
     * 
     * @param objType 객체 타입
     * @return 설정 유효 여부
     */
    boolean isEnabled(String objType);
}
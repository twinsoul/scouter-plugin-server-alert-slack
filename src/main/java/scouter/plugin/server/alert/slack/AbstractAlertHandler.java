package scouter.plugin.server.alert.slack;

import scouter.lang.pack.AlertPack;
import scouter.server.Configure;
import scouter.server.Logger;
import scouter.util.FormatUtil;
import scouter.util.LinkedMap;

/**
 * 알림 처리 추상 클래스
 * 
 * 알림 처리 로직을 구현하기 위한 추상 클래스
 */
public abstract class AbstractAlertHandler {
    protected final Configure conf = Configure.getInstance();
    protected final LinkedMap<String, AlertHistory> alertHistoryLinkedMap;

    protected AbstractAlertHandler(LinkedMap<String, AlertHistory> alertHistoryLinkedMap) {
        this.alertHistoryLinkedMap = alertHistoryLinkedMap;
    }

    public AlertPack handleAlert(AlertContext context) {
        // 알림 패턴이 존재하지 않으면 초기 상태 로깅
        if (!alertHistoryLinkedMap.containsKey(context.alertPattern)) {
            alertHistoryLinkedMap.put(context.alertPattern, createHistory(System.currentTimeMillis()));
            logInitialStatus(context);
            return null;
        }

        preProcessAlert(context); // gcTimeInterval 로깅 등을 위한 훅

        AlertHistory history = alertHistoryLinkedMap.get(context.alertPattern);
        long diff = System.currentTimeMillis() - history.getLastModified();
        long intervalMillis = context.interval * 60 * 1000L;

        if (diff < intervalMillis) {
            int historyCount = history.addCount();
            alertHistoryLinkedMap.put(context.alertPattern, history);

            // 에러인 경우는 첫 이벤트라도 알림처리한다.
            if (context.isErrorPattern()) {
                byte alertLevel = determineAlertLevel(context, historyCount, diff);
                String message = formatAlertMessage(context, historyCount);
                logStatus(context, historyCount, diff, "Error alert (Not yet)");

                return createAlertPack(context, alertLevel, message, historyCount);
            } else {
                logStatus(context, historyCount, diff, "Not yet");
                return null;
            }
        } else if (diff < intervalMillis * 2) {
            int historyCount = history.getHistoryCount();
            byte alertLevel = determineAlertLevel(context, historyCount, diff);

            if (shouldSkipAlert(alertLevel, context)) {
                return null;
            }

            String message = formatAlertMessage(context, historyCount);
            alertHistoryLinkedMap.put(context.alertPattern, createHistory(System.currentTimeMillis()));
            logStatus(context, historyCount, diff, "Ok alert !!!");

            return createAlertPack(context, alertLevel, message, historyCount);
        } else {
            alertHistoryLinkedMap.put(context.alertPattern, createHistory(System.currentTimeMillis()));
            logStatus(context, 0, diff, "Put(reset) !!!");
            return null;
        }
    }

    protected void preProcessAlert(AlertContext context) {
        // 기본 구현은 빈 메서드
    }

    protected boolean shouldSkipAlert(byte alertLevel, AlertContext context) {
        return false;
    }

    protected AlertPack createAlertPack(AlertContext context, byte alertLevel, String message, int historyCount) {
        AlertPack ap = new AlertPack();
        ap.level = alertLevel;
        ap.objHash = context.objHash;
        ap.title = getAlertTitle(context, historyCount);
        ap.message = message;
        ap.time = System.currentTimeMillis();
        ap.objType = context.objType;
        return ap;
    }

    protected void logInitialStatus(AlertContext context) {
        logStatus(context, 0, 0, "Put !!!");
    }

    protected void logStatus(AlertContext context, int historyCount, long diff, String status) {
        if (conf.getBoolean("ext_plugin_slack_debug", false)) {
            String message = formatLogMessage(context, historyCount, diff, status);
            Logger.println(message);
        }
    }

    protected abstract AlertHistory createHistory(long timestamp);

    protected abstract byte determineAlertLevel(AlertContext context, int historyCount, long diff);

    protected abstract String formatAlertMessage(AlertContext context, int historyCount);

    protected abstract String getAlertTitle(AlertContext context, int historyCount);

    protected abstract String formatLogMessage(AlertContext context, int historyCount, long diff, String status);
}
package scouter.plugin.server.alert.slack;

import scouter.lang.AlertLevel;
import scouter.util.FormatUtil;
import scouter.util.LinkedMap;

/**
 * 응답시간 초과 알림 처리 핸들러
 */
public class ElapsedTimeAlertHandler extends AbstractAlertHandler {

    public ElapsedTimeAlertHandler(LinkedMap<String, AlertHistory> alertHistoryLinkedMap) {
        super(alertHistoryLinkedMap);
    }

    @Override
    protected AlertHistory createHistory(long timestamp) {
        return new ElapsedServiceHistory(timestamp, 0);
    }

    @Override
    protected byte determineAlertLevel(AlertContext context, int historyCount, long diff) {
        if (historyCount > 0) {
            float historyAvg = (float) historyCount / (context.interval * 60); // 분당 평균 계산을 위해 60으로 나눔
            if (historyAvg >= conf.getInt("ext_plugin_elapsed_time_avg_threshold", 1)) {
                return AlertLevel.FATAL;
            }
            return AlertLevel.WARN;
        }
        return AlertLevel.INFO;
    }

    @Override
    protected boolean shouldSkipAlert(byte alertLevel, AlertContext context) {
        return alertLevel == AlertLevel.INFO; // INFO 레벨은 알림 발송하지 않음
    }

    @Override
    protected String formatAlertMessage(AlertContext context, int historyCount) {
        return String.format("(%s) elapsed time(%s ms) exceed a threshold%s",
                context.serviceName,
                FormatUtil.print(Long.parseLong(context.metricValue), "#,##0"),
                historyCount > 0 ? " (+" + historyCount + ")" : "");
    }

    @Override
    protected String getAlertTitle(AlertContext context, int historyCount) {
        return "Elapsed time exceed a threshold.";
    }

    @Override
    protected String formatLogMessage(AlertContext context, int historyCount, long diff, String status) {
        return String.format("%s(%s) elapsed (%s) %s (%s ms)%s",
                context.objName,
                context.objType,
                context.serviceName,
                status,
                FormatUtil.print(Long.parseLong(context.metricValue), "#,##0"),
                diff > 0 ? String.format(" (history: %d, diff: %s ms)",
                        historyCount,
                        FormatUtil.print(diff, "#,##0")) : "");
    }
}
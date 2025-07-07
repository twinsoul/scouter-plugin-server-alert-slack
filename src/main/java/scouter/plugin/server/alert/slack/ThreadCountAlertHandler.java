package scouter.plugin.server.alert.slack;

import scouter.lang.AlertLevel;
import scouter.util.FormatUtil;
import scouter.util.LinkedMap;

public class ThreadCountAlertHandler extends AbstractAlertHandler {

    public ThreadCountAlertHandler(LinkedMap<String, AlertHistory> alertHistoryLinkedMap) {
        super(alertHistoryLinkedMap);
    }

    @Override
    protected AlertHistory createHistory(long timestamp) {
        return new AlertHistory(timestamp, 0);
    }

    @Override
    protected byte determineAlertLevel(AlertContext context, int historyCount, long diff) {
        return historyCount > 1 ? AlertLevel.FATAL : AlertLevel.INFO; // 정확히 > 1 조건 사용
    }

    @Override
    protected String formatAlertMessage(AlertContext context, int historyCount) {
        return String.format("%s's Thread count(%s) exceed a threshold%s",
                context.objName,
                context.metricValue,
                historyCount > 0 ? " (+" + historyCount + ")" : "");
    }

    @Override
    protected String getAlertTitle(AlertContext context, int historyCount) {
        return "Thread count exceed a threshold.";
    }

    @Override
    protected String formatLogMessage(AlertContext context, int historyCount, long diff, String status) {
        return String.format("%s (%s) : Thread Count(%s) => %s%s",
                context.objName,
                context.objType,
                context.metricValue,
                status,
                diff > 0 ? String.format(" (history: %d, diff: %s ms)",
                        historyCount,
                        FormatUtil.print(diff, "#,##0")) : "");
    }
}
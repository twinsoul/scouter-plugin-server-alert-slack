package scouter.plugin.server.alert.slack;

import scouter.lang.AlertLevel;
import scouter.util.FormatUtil;
import scouter.util.LinkedMap;

/**
 * GC 시간 초과 알림 처리 핸들러
 */
public class GCTimeAlertHandler extends AbstractAlertHandler {

    public GCTimeAlertHandler(LinkedMap<String, AlertHistory> alertHistoryLinkedMap) {
        super(alertHistoryLinkedMap);
    }

    @Override
    protected AlertHistory createHistory(long timestamp) {
        return new AlertHistory(timestamp, 0);
    }

    @Override
    protected byte determineAlertLevel(AlertContext context, int historyCount, long diff) {
        return historyCount > 0 ? AlertLevel.FATAL : AlertLevel.INFO;
    }

    @Override
    protected String formatAlertMessage(AlertContext context, int historyCount) {
        return String.format("%s's GC time(%s ms) exceed a threshold%s",
                context.objName,
                FormatUtil.print(Long.parseLong(context.metricValue), "#,##0"),
                historyCount > 0 ? " (+" + historyCount + ")" : "");
    }

    @Override
    protected String getAlertTitle(AlertContext context, int historyCount) {
        return "GC time exceed a threshold.";
    }

    @Override
    protected void preProcessAlert(AlertContext context) {
        // println("gcTimeInterval : " + context.interval); // GC Time 처리 시 interval 로깅
    }

    @Override
    protected String formatLogMessage(AlertContext context, int historyCount, long diff, String status) {
        return String.format("%s (%s) : GC Time(%s ms) => %s%s",
                context.objName,
                context.objType,
                FormatUtil.print(Long.parseLong(context.metricValue), "#,##0"),
                status,
                diff > 0 ? String.format(" (history: %d, diff: %s ms)",
                        historyCount,
                        FormatUtil.print(diff, "#,##0")) : "");
    }

    private void println(String message) {
        if (conf.getBoolean("ext_plugin_slack_debug", false)) {
            System.out.println(message);
            scouter.server.Logger.println(message);
        }
    }
}
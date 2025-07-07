package scouter.plugin.server.alert.slack;

import scouter.lang.AlertLevel;
import scouter.lang.TextTypes;
import scouter.server.db.TextRD;
import scouter.util.DateUtil;
import scouter.util.FormatUtil;
import scouter.util.LinkedMap;

/**
 * 에러 알림 처리 핸들러
 */
public class ErrorAlertHandler extends AbstractAlertHandler {

    public ErrorAlertHandler(LinkedMap<String, AlertHistory> alertHistoryLinkedMap) {
        super(alertHistoryLinkedMap);
    }

    @Override
    protected AlertHistory createHistory(long timestamp) {
        return new ErrorHistory(timestamp, 0);
    }

    @Override
    protected byte determineAlertLevel(AlertContext context, int historyCount, long diff) {
        return AlertLevel.ERROR; // 항상 ERROR 레벨
    }

    @Override
    protected String formatAlertMessage(AlertContext context, int historyCount) {
        String date = DateUtil.yyyymmdd(context.endTime);
        String service = TextRD.getString(date, TextTypes.SERVICE, context.service);
        String errorMsg = TextRD.getString(date, TextTypes.ERROR, Integer.parseInt(context.metricValue));

        return String.format("%s - %s%s",
                service,
                errorMsg,
                historyCount > 0 ? " (+" + historyCount + ")" : "");
    }

    @Override
    protected String getAlertTitle(AlertContext context, int historyCount) {
        String date = DateUtil.yyyymmdd(context.endTime);
        return TextRD.getString(date, TextTypes.ERROR, Integer.parseInt(context.metricValue)); // title은 errorMsg 그대로 사용
    }

    @Override
    protected String formatLogMessage(AlertContext context, int historyCount, long diff, String status) {
        String date = DateUtil.yyyymmdd(context.endTime);
        String service = TextRD.getString(date, TextTypes.SERVICE, context.service);

        return String.format("%s(%s) error (%s) %s%s",
                context.objName,
                context.objType,
                service,
                status,
                diff > 0 ? String.format(" (history: %d, diff: %s ms)",
                        historyCount,
                        FormatUtil.print(diff, "#,##0")) : "");
    }
}
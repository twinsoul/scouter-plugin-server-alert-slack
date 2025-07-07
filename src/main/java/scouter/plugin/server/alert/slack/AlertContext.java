package scouter.plugin.server.alert.slack;

public class AlertContext {
    public final String alertPattern;
    public final String objName;
    public final String objType;
    public final int interval;
    public final String metricValue;
    public final int threshold;
    public final int objHash;
    public final String serviceName;
    public final String date;
    public final int service;
    public final long endTime;
    public final String metricUnit;
    public final String metricFormatted;

    private AlertContext(Builder builder) {
        this.alertPattern = builder.alertPattern;
        this.objName = builder.objName;
        this.objType = builder.objType;
        this.interval = builder.interval;
        this.metricValue = builder.metricValue;
        this.threshold = builder.threshold;
        this.objHash = builder.objHash;
        this.serviceName = builder.serviceName;
        this.date = builder.date;
        this.service = builder.service;
        this.endTime = builder.endTime;
        this.metricUnit = builder.metricUnit;
        this.metricFormatted = builder.metricValue != null
                ? scouter.util.FormatUtil.print(Long.parseLong(builder.metricValue), "#,##0")
                : null;
    }

    public static class Builder {
        private String alertPattern;
        private String objName;
        private String objType;
        private int interval;
        private String metricValue;
        private int threshold;
        private int objHash;
        private String serviceName;
        private String date;
        private int service;
        private long endTime;
        private String metricUnit;

        public Builder alertPattern(String alertPattern) {
            this.alertPattern = alertPattern;
            return this;
        }

        public Builder objName(String objName) {
            this.objName = objName;
            return this;
        }

        public Builder objType(String objType) {
            this.objType = objType;
            return this;
        }

        public Builder interval(int interval) {
            this.interval = interval;
            return this;
        }

        public Builder metricValue(String metricValue) {
            this.metricValue = metricValue;
            return this;
        }

        public Builder threshold(int threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder objHash(int objHash) {
            this.objHash = objHash;
            return this;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder date(String date) {
            this.date = date;
            return this;
        }

        public Builder service(int service) {
            this.service = service;
            return this;
        }

        public Builder endTime(long endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder metricUnit(String unit) {
            this.metricUnit = unit;
            return this;
        }

        public AlertContext build() {
            return new AlertContext(this);
        }
    }
}
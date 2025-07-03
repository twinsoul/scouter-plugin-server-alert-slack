package scouter.plugin.server.alert.slack;

public class AlertLogBean {
	AlertLogObjectDef object;

	private AlertLogBean(Builder builder) {
		this.object = builder.object;

	}

	public static class Builder {
		private AlertLogObjectDef object;

		public Builder setObjectSpec(AlertLogObjectDef objectSpec) {
			this.object = objectSpec;
			return this;
		}

		public AlertLogBean build() {
			return new AlertLogBean(this);
		}
	}
}
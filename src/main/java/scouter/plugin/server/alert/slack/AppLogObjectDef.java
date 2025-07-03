package scouter.plugin.server.alert.slack;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AppLogObjectDef {
	String objectName;
	String objType;

	int elapsed;

	String service;
	String startTime;
	String endTime;

	int cpu;
	int sqlCount;
	int sqlTime;
	int kbytes;

	String ipaddr;
	String dump;
	String error;
	String txid;

	String userAgent;
	String referer;
	String login;
	String desc;
	String text1;
	String text2;
}

package scouter.plugin.server.alert.slack;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import scouter.lang.value.MapValue;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AlertLogObjectDef {
	public long time;
	public String objType;
	public String agentName;
	public byte level;
	public String title;
	public String message;
	public MapValue tags;
}
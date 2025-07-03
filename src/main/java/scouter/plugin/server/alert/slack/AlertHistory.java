package scouter.plugin.server.alert.slack;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AlertHistory {
    public long lastModified;
    public int historyCount;

    public int addCount() {
        this.historyCount += 1;
        return this.historyCount;
    }
}
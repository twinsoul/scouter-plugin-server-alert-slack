/*
 *  Copyright 2016 Scouter Project.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @author Se-Wang Lee
 */
package scouter.plugin.server.alert.slack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import com.google.gson.Gson;

import scouter.lang.AlertLevel;
import scouter.lang.TextTypes;
import scouter.lang.TimeTypeEnum;
import scouter.lang.counters.CounterConstants;
import scouter.lang.pack.AlertPack;
import scouter.lang.pack.MapPack;
import scouter.lang.pack.ObjectPack;
import scouter.lang.pack.PerfCounterPack;
import scouter.lang.pack.XLogPack;
import scouter.lang.plugin.PluginConstants;
import scouter.lang.plugin.annotation.ServerPlugin;
import scouter.net.RequestCmd;
import scouter.plugin.server.alert.messenger.works.WorksAuth;
import scouter.plugin.server.alert.messenger.works.WorksBotMessage;
import scouter.server.Configure;
import scouter.server.CounterManager;
import scouter.server.Logger;
import scouter.server.core.AgentManager;
import scouter.server.db.TextRD;
import scouter.server.netio.AgentCall;
import scouter.util.DateUtil;
import scouter.util.HashUtil;
import scouter.util.LinkedMap;

/**
 * Scouter server plugin to send alert via Slack
 *
 * @author Se-Wang Lee(ssamzie101@gmail.com) on 2016. 5. 2.
 */
public class SlackPlugin {

	final Configure conf = Configure.getInstance();

	private final MonitoringGroupConfigure groupConf;

	private static AtomicInteger ai = new AtomicInteger(0);
	private static List<Integer> javaeeObjHashList = new ArrayList<Integer>();
	private static LinkedMap<String, AlertHistory> alertHistoryLinkedMap = new LinkedMap<String, AlertHistory>()
			.setMax(10000);
	private static final int THREAD_COUNT_INTERVAL = 5; // (min)
	private static final int ERROR_LOG_INTERVAL = 5; // (min)
	private static final int ELAPSED_TIME_INTERVAL = 5; // (min)
	private static final int GC_TIME_INTERVAL = 5; // (min)

	private final ThreadCountAlertHandler threadCountHandler;
	private final ElapsedTimeAlertHandler elapsedTimeHandler;
	private final GCTimeAlertHandler gcTimeHandler;
	private final ErrorAlertHandler errorHandler;

	public SlackPlugin() {
		this.groupConf = new MonitoringGroupConfigure(conf);
		this.threadCountHandler = new ThreadCountAlertHandler(alertHistoryLinkedMap);
		this.elapsedTimeHandler = new ElapsedTimeAlertHandler(alertHistoryLinkedMap);
		this.gcTimeHandler = new GCTimeAlertHandler(alertHistoryLinkedMap);
		this.errorHandler = new ErrorAlertHandler(alertHistoryLinkedMap);

		initializeScheduledTasks();
	}

	private void initializeScheduledTasks() {
		if (ai.incrementAndGet() == 1) {
			ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
			executor.scheduleAtFixedRate(() -> checkThreadCount(), 0, 5, TimeUnit.SECONDS);
		}
	}

	private void checkThreadCount() {
		if (conf.getInt("ext_plugin_thread_count_threshold", 0) == 0) {
			return;
		}

		for (int objHash : javaeeObjHashList) {
			try {
				if (!AgentManager.isActive(objHash)) {
					continue;
				}

				ObjectPack objectPack = AgentManager.getAgent(objHash);
				MapPack mapPack = new MapPack();
				mapPack.put("objHash", objHash);
				mapPack = AgentCall.call(objectPack, RequestCmd.OBJECT_THREAD_LIST, mapPack);

				int threadCountThreshold = groupConf.getInt("ext_plugin_thread_count_threshold", objectPack.objType, 0);
				int threadCount = mapPack.getList("name").size();

				if (threadCountThreshold != 0 && threadCount > threadCountThreshold) {
					AlertContext context = new AlertContext.Builder()
							.alertPattern(objHash + "_" + RequestCmd.OBJECT_THREAD_LIST)
							.objName(objectPack.objName)
							.objType(objectPack.objType)
							.interval(conf.getInt("ext_plugin_thread_count_interval", THREAD_COUNT_INTERVAL))
							.metricValue(String.valueOf(threadCount))
							.threshold(threadCountThreshold)
							.objHash(objHash)
							.build();

					AlertPack alertPack = threadCountHandler.handleAlert(context);
					if (alertPack != null) {
						alert(alertPack);
					}
				}
			} catch (Exception e) {
				Logger.printStackTrace(e);
			}
		}
	}

	@ServerPlugin(PluginConstants.PLUGIN_SERVER_ALERT)
	public void alert(final AlertPack pack) {
		if (groupConf.getBoolean("ext_plugin_slack_send_alert", pack.objType, false)) {

			// 수신 레벨(0 : INFO, 1 : WARN, 2 : ERROR, 3 : FATAL)
			int level = groupConf.getInt("ext_plugin_slack_level", pack.objType, 0);

			// Get log level (0 : INFO, 1 : WARN, 2 : ERROR, 3 : FATAL)
			if (level <= pack.level) {
				new Thread() {
					public void run() {
						try {
							// 개별 서비스 이름 추출
							ObjectPack objectPack = AgentManager.getAgent(pack.objHash);

							String objectName = objectPack.objName.substring(objectPack.objName.lastIndexOf("/") + 1);
							String hostName = objectPack.objName.indexOf("/", 1) > 0
									? objectPack.objName.substring(1, objectPack.objName.indexOf("/", 1))
									: objectPack.objName.substring(1);
							println("objectName : " + objectName + ", objectPack.objName : " + objectPack.objName
									+ ", objectPack.objType : " + objectPack.objType + ", hostName : " + hostName);

							// Slack 설정
							String defaultWebhookURL = groupConf.getValue("ext_plugin_slack_webhook_url", pack.objType);
							String webhookURL = groupConf.getValue("ext_plugin_slack_webhook_url." + objectName,
									pack.objType, defaultWebhookURL);
							String channel = groupConf.getValue("ext_plugin_slack_channel", pack.objType);
							String botName = groupConf.getValue("ext_plugin_slack_botName", pack.objType);
							String iconURL = groupConf.getValue("ext_plugin_slack_icon_url", pack.objType);
							String iconEmoji = groupConf.getValue("ext_plugin_slack_icon_emoji", pack.objType);

							// NaverWorks 설정
							String botId = groupConf.getValue("ext_plugin_works_bot_id", pack.objType);
							String defaultChannelId = groupConf.getValue("ext_plugin_works_channel_id", pack.objType);
							String hostChannelId = groupConf.getValue("ext_plugin_works_channel_id." + hostName,
									pack.objType, defaultChannelId);
							String channelId = groupConf.getValue("ext_plugin_works_channel_id." + objectName,
									pack.objType, hostChannelId);
							String userId = groupConf.getValue("ext_plugin_works_user_id", pack.objType);
							println("channelId : " + channelId + ", hostChannelId : " + hostChannelId
									+ ", defaultChannelId : " + defaultChannelId);

							assert webhookURL != null;

							// Get the agent Name
							String name = AgentManager.getAgentName(pack.objHash) == null ? "N/A"
									: AgentManager.getAgentName(pack.objHash);

							if (name.equals("N/A") && pack.message.endsWith("connected.")) {
								int idx = pack.message.indexOf("connected");
								if (pack.message.indexOf("reconnected") > -1) {
									name = pack.message.substring(0, idx - 6);
								} else {
									name = pack.message.substring(0, idx - 4);
								}
							}

							String title = pack.title;
							String msg = pack.message;
							if (title.equals("INACTIVE_OBJECT")) {
								title = "An object has been inactivated.";
								msg = pack.message.substring(0, pack.message.indexOf("OBJECT") - 1);
							}

							// Make message contents
							String contents = "[TYPE] : " + pack.objType.toUpperCase() + "\n" +
									"[NAME] : " + name + "\n" +
									"[LEVEL] : " + AlertLevel.getName(pack.level) + "\n" +
									"[TITLE] : " + title + "\n" +
									"[MESSAGE] : " + msg;

							Message message = new Message(contents, channel, botName, iconURL, iconEmoji);
							String payload = new Gson().toJson(message);

							// 로깅 여부 - 기본 값은 false
							if (groupConf.getBoolean("ext_plugin_slack_debug", pack.objType, false)) {
								println("WebHookURL : " + webhookURL);
								println("param : " + payload);
							}

							// slack 전송
							HttpPost post = new HttpPost(webhookURL);
							post.addHeader("Content-Type", "application/json");
							post.setEntity(new StringEntity(payload, "utf-8"));

							// send the post request
							try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
								HttpResponse response = client.execute(post);

								if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
									println("Slack message sent to [" + channel + "] successfully.");
								} else {
									println("Slack message sent failed. Verify below information.");
								}
							} catch (Exception e) {
								Logger.println("[Error] : " + e.getMessage());
								if (conf._trace) {
									e.printStackTrace();
								}
							}

							// Works 인증 객체 생성 및 토큰 가져오기
							WorksAuth worksAuth = new WorksAuth(Configure.getInstance());
							String accessToken = worksAuth.getAccessToken();

							// 메시지 생성
							WorksBotMessage worksMessage = new WorksBotMessage();
							worksMessage.content = new WorksBotMessage.Content();
							worksMessage.content.type = "text";
							worksMessage.content.text = contents;

							Gson gson = new Gson();
							payload = gson.toJson(worksMessage);

							// 디버그 로깅
							println("Works Bot Payload: " + payload);

							// HTTP 요청 설정
							String userMessageApiEndpoint = "https://www.worksapis.com/v1.0/bots/" + botId + "/users/"
									+ userId + "/messages";

							String channelMessageApiEndpoint = "https://www.worksapis.com/v1.0/bots/" + botId
									+ "/channels/" + channelId + "/messages";

							// println("channelMessageApiEndpoint : " + channelMessageApiEndpoint);

							post = new HttpPost(channelMessageApiEndpoint);
							post.addHeader("Content-Type", "application/json");
							post.addHeader("Authorization", "Bearer " + accessToken);
							post.setEntity(new StringEntity(payload, "utf-8"));

							// HTTP 요청 실행
							try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
								HttpResponse response = client.execute(post);

								if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK ||
										response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED) {
									println("Works Bot message sent successfully.");
								} else {
									Logger.println("Works Bot message sending failed. Response: " +
											EntityUtils.toString(response.getEntity(), "UTF-8"));
								}
							}
						} catch (Exception e) {
							Logger.println("[Error] : " + e.getMessage());
							if (conf._trace) {
								e.printStackTrace();
							}
						}
					}
				}.start();
			}
		}
	}

	@ServerPlugin(PluginConstants.PLUGIN_SERVER_OBJECT)
	public void object(ObjectPack pack) {
		// object active/dead alert - default : false
		if (!conf.getBoolean("ext_plugin_slack_object_alert_enabled", false)) {
			return;
		}

		if (pack.version != null && pack.version.length() > 0) {
			AlertPack ap = null;
			ObjectPack op = AgentManager.getAgent(pack.objHash);

			if (op == null && pack.wakeup == 0L) {
				// in case of new agent connected
				ap = new AlertPack();
				ap.level = AlertLevel.INFO;
				ap.objHash = pack.objHash;
				ap.title = "An object has been activated.";
				ap.message = pack.objName + " is connected.";
				ap.time = System.currentTimeMillis();

				if (AgentManager.getAgent(pack.objHash) != null) {
					ap.objType = AgentManager.getAgent(pack.objHash).objType;
				} else {
					ap.objType = "scouter";
				}

				alert(ap);
			} else if (op.alive == false) {
				// in case of agent reconnected
				ap = new AlertPack();
				ap.level = AlertLevel.INFO;
				ap.objHash = pack.objHash;
				ap.title = "An object has been activated.";
				ap.message = pack.objName + " is reconnected.";
				ap.time = System.currentTimeMillis();
				ap.objType = AgentManager.getAgent(pack.objHash).objType;

				alert(ap);
			}
			// inactive state can be handled in alert() method.
		}
	}

	@ServerPlugin(PluginConstants.PLUGIN_SERVER_XLOG)
	public void xlog(XLogPack pack) {
		if (!conf.getBoolean("ext_plugin_slack_xlog_enabled", false)) {
			return;
		}

		String serviceName = TextRD.getString(DateUtil.yyyymmdd(pack.endTime), TextTypes.SERVICE, pack.service);
		String objName = TextRD.getString(DateUtil.yyyymmdd(pack.endTime), TextTypes.OBJECT, pack.objHash);
		String objType = AgentManager.getAgent(pack.objHash) != null ? AgentManager.getAgent(pack.objHash).objType
				: "scouter";

		if (groupConf.getBoolean("ext_plugin_slack_xlog_enabled", objType, true)) {
			// Error 처리
			if (pack.error != 0) {
				AlertContext context = new AlertContext.Builder()
						.alertPattern(pack.objHash + "_" + pack.service + "_error")
						.objName(objName)
						.objType(objType)
						.interval(conf.getInt("ext_plugin_error_log_interval", ERROR_LOG_INTERVAL))
						.metricValue(String.valueOf(pack.error))
						.service(pack.service)
						.endTime(pack.endTime)
						.objHash(pack.objHash)
						.build();

				AlertPack alertPack = errorHandler.handleAlert(context);
				if (alertPack != null) {
					alert(alertPack);
				}
			}

			// Elapsed Time 처리
			try {
				int elapsedThreshold = groupConf.getInt("ext_plugin_elapsed_time_threshold", objType, 0);
				if (elapsedThreshold != 0 && pack.elapsed > elapsedThreshold) {
					AlertContext context = new AlertContext.Builder()
							.alertPattern(pack.objHash + "_" + pack.service + "_elapsed")
							.objName(objName)
							.objType(objType)
							.interval(conf.getInt("ext_plugin_elapsed_time_interval", ELAPSED_TIME_INTERVAL))
							.metricValue(String.valueOf(pack.elapsed))
							.serviceName(serviceName)
							.threshold(elapsedThreshold)
							.objHash(pack.objHash)
							.build();

					AlertPack alertPack = elapsedTimeHandler.handleAlert(context);
					if (alertPack != null) {
						alert(alertPack);
					}
				}
			} catch (Exception e) {
				Logger.printStackTrace(e);
			}
		}
	}

	@ServerPlugin(PluginConstants.PLUGIN_SERVER_COUNTER)
	public void counter(PerfCounterPack pack) {
		String objName = pack.objName;
		int objHash = HashUtil.hash(objName);
		String objType = null;
		String objFamily = null;

		if (AgentManager.getAgent(objHash) != null) {
			objType = AgentManager.getAgent(objHash).objType;
		}

		if (objType != null) {
			objFamily = CounterManager.getInstance().getCounterEngine().getObjectType(objType).getFamily().getName();
		}

		try {
			if (CounterConstants.FAMILY_JAVAEE.equals(objFamily)) {
				if (!javaeeObjHashList.contains(objHash)) {
					javaeeObjHashList.add(objHash);
				}

				if (pack.timetype == TimeTypeEnum.REALTIME) {
					long gcTimeThreshold = groupConf.getLong("ext_plugin_gc_time_threshold", objType, 0);
					long gcTime = pack.data.getLong(CounterConstants.JAVA_GC_TIME);

					if (gcTimeThreshold != 0 && gcTime > gcTimeThreshold) {
						AlertContext context = new AlertContext.Builder()
								.alertPattern(objHash + "_" + CounterConstants.JAVA_GC_TIME)
								.objName(objName)
								.objType(objType)
								.interval(conf.getInt("ext_plugin_gc_time_interval", GC_TIME_INTERVAL))
								.metricValue(String.valueOf(gcTime))
								.threshold((int) gcTimeThreshold)
								.objHash(objHash)
								.build();

						AlertPack alertPack = gcTimeHandler.handleAlert(context);
						if (alertPack != null) {
							alert(alertPack);
						}
					}
				}
			}
		} catch (Exception e) {
			Logger.printStackTrace(e);
		}
	}

	private void println(Object o) {
		if (conf.getBoolean("ext_plugin_slack_debug", false)) {
			System.out.println(o);
			Logger.println(o);
		}
	}
}


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
import scouter.server.Configure;
import scouter.server.CounterManager;
import scouter.server.Logger;
import scouter.server.core.AgentManager;
import scouter.server.db.TextRD;
import scouter.server.netio.AgentCall;
import scouter.util.DateUtil;
import scouter.util.HashUtil;
import scouter.util.LinkedMap;
import scouter.util.FormatUtil;

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

	public SlackPlugin() {
		groupConf = new MonitoringGroupConfigure(conf);

		// 5초마다 실행
		if (ai.incrementAndGet() == 1) {
			ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

			// thread count check
			executor.scheduleAtFixedRate(new Runnable() {
				@Override
				public void run() {
					// Thread Count의 임계치 - 기본 값 0일때 Thread Count의 임계치 초과 여부를 확인하지 않는다.
					if (conf.getInt("ext_plugin_thread_count_threshold", 0) == 0) {
						return;
					}
					for (int objHash : javaeeObjHashList) {
						try {
							if (AgentManager.isActive(objHash)) {
								ObjectPack objectPack = AgentManager.getAgent(objHash);
								MapPack mapPack = new MapPack();
								mapPack.put("objHash", objHash);

								mapPack = AgentCall.call(objectPack, RequestCmd.OBJECT_THREAD_LIST, mapPack);

								// Thread Count 임계치
								int threadCountThreshold = groupConf.getInt("ext_plugin_thread_count_threshold",
										objectPack.objType, 0);
								// 현재 Thread 개수
								int threadCount = mapPack.getList("name").size();

								/**
								 * 현재 Thread 개수가 임계치를 초과하더라도 알람주기가 도래할 경우에만 alert
								 */
								if (threadCountThreshold != 0 && threadCount > threadCountThreshold) {
									int historyCount = 0;
									byte alertLevel = AlertLevel.INFO;
									String message = objectPack.objName + "'s Thread count(" + threadCount
											+ ") exceed a threshold.";

									String alertPattern = objHash + "_" + RequestCmd.OBJECT_THREAD_LIST;
									if (!alertHistoryLinkedMap.containsKey(alertPattern)) {
										alertHistoryLinkedMap.put(alertPattern,
												new AlertHistory(System.currentTimeMillis(), 0));

										println(objectPack.objName + " (" + objectPack.objType
												+ ") : Thread Count(" + threadCount + ") => Put !!!");
										return;
									} else {
										AlertHistory alertHistory = alertHistoryLinkedMap.get(alertPattern);
										long diff = System.currentTimeMillis() - alertHistory.getLastModified();
										int threadCountInterval = conf.getInt("ext_plugin_thread_count_interval",
												THREAD_COUNT_INTERVAL);

										if (diff < threadCountInterval * 60 * 1000L) {
											historyCount = alertHistory.addCount();
											alertHistoryLinkedMap.put(alertPattern, alertHistory);

											println(objectPack.objName + " (" + objectPack.objType
													+ ") : Thread Count(" + threadCount + ") => Not yet (history: "
													+ historyCount + ", diff: " + FormatUtil.print(diff, "#,##0")
													+ " ms)");
											return;
										} else {
											if (diff < threadCountInterval * 60 * 1000L * 2) {
												historyCount = alertHistory.getHistoryCount();
												alertLevel = historyCount > 1 ? AlertLevel.FATAL : AlertLevel.INFO;
												alertHistoryLinkedMap.put(alertPattern,
														new AlertHistory(System.currentTimeMillis(), 0));

												println(objectPack.objName + " (" + objectPack.objType
														+ ") : Thread Count(" + threadCount
														+ ") => Ok alert !!! (history: " + historyCount + ", diff: "
														+ FormatUtil.print(diff, "#,##0") + " ms)");

												// 발송 메시지 생성
												message = objectPack.objName + "'s Thread count(" + threadCount
														+ ") exceed a threshold. (+" + historyCount + ")";
											} else {
												// 마지막 이벤트로부터 기준시간을 경과한 경우
												alertHistoryLinkedMap.put(alertPattern,
														new AlertHistory(System.currentTimeMillis(), 0));

												println(objectPack.objName + " (" + objectPack.objType
														+ ") : Thread Count(" + threadCount + ") => Put(reset) !!!");
												return;
											}
										}
									}

									// 첫번째 이벤트와 알람주기(thread_count_interval)내 이벤트는 alert하지 않고 알람주기가 도래할 경우에만 alert
									AlertPack ap = new AlertPack();
									// ap.level = AlertLevel.WARN;
									ap.level = alertLevel;
									ap.objHash = objHash;
									ap.title = "Thread count exceed a threshold.";
									ap.message = message;
									ap.time = System.currentTimeMillis();
									ap.objType = objectPack.objType;
									alert(ap);
								}
							}
						} catch (Exception e) {
							// ignore
						}
					}
				}
			}, 0, 5, TimeUnit.SECONDS);
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
							String webhookURL = groupConf.getValue("ext_plugin_slack_webhook_url", pack.objType);
							String channel = groupConf.getValue("ext_plugin_slack_channel", pack.objType);
							String botName = groupConf.getValue("ext_plugin_slack_botName", pack.objType);
							String iconURL = groupConf.getValue("ext_plugin_slack_icon_url", pack.objType);
							String iconEmoji = groupConf.getValue("ext_plugin_slack_icon_emoji", pack.objType);

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

							HttpPost post = new HttpPost(webhookURL);
							post.addHeader("Content-Type", "application/json");
							// charset set utf-8
							post.setEntity(new StringEntity(payload, "utf-8"));

							CloseableHttpClient client = HttpClientBuilder.create().build();

							// send the post request
							HttpResponse response = client.execute(post);

							if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
								println("Slack message sent to [" + channel + "] successfully.");
							} else {
								println("Slack message sent failed. Verify below information.");
								println("[WebHookURL] : " + webhookURL);
								println("[Message] : " + payload);
								println("[Reason] : " + EntityUtils.toString(response.getEntity(), "UTF-8"));
							}

						} catch (Exception e) {
							println("[Error] : " + e.getMessage());
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
		// xlog maasege send - default : false
		if (!conf.getBoolean("ext_plugin_slack_xlog_enabled", false)) {
			return;
		}

		String serviceName = TextRD.getString(DateUtil.yyyymmdd(pack.endTime), TextTypes.SERVICE,
				pack.service);
		String objName = TextRD.getString(DateUtil.yyyymmdd(pack.endTime), TextTypes.OBJECT, pack.objHash);
		String objType = AgentManager.getAgent(pack.objHash) != null ? AgentManager.getAgent(pack.objHash).objType
				: "scouter";

		if (groupConf.getBoolean("ext_plugin_slack_xlog_enabled", objType, true)) {
			if (pack.error != 0) {
				String date = DateUtil.yyyymmdd(pack.endTime);
				String service = TextRD.getString(date, TextTypes.SERVICE, pack.service);

				int historyCount = 0;
				String alertPattern = pack.objHash + "_" + pack.service + "_error";
				String errorMsg = TextRD.getString(date, TextTypes.ERROR, pack.error);
				String message = service + " - " + errorMsg;

				if (!alertHistoryLinkedMap.containsKey(alertPattern)) {
					alertHistoryLinkedMap.put(alertPattern, new ErrorHistory(System.currentTimeMillis(), 0));

					println(objName + "(" + objType + ") error (" + service + ") Put !!!");
				} else {
					AlertHistory errorService = (ErrorHistory) alertHistoryLinkedMap.get(alertPattern);
					long diff = System.currentTimeMillis() - errorService.getLastModified();
					int errorLogInterval = conf.getInt("ext_plugin_error_log_interval", ERROR_LOG_INTERVAL);

					// 알람주기가 도래하지 않으면 alert하지 않는다.
					if (diff < errorLogInterval * 60 * 1000L) {
						historyCount = errorService.addCount();
						alertHistoryLinkedMap.put(alertPattern, errorService);

						println(objName + "(" + objType + ") error (" + service + ") => Not yet (history: "
								+ historyCount + ", diff: " + FormatUtil.print(diff, "#,##0") + " ms)");
						return;
					} else {
						if (diff < errorLogInterval * 60 * 1000L * 2) {
							historyCount = errorService.getHistoryCount();
							alertHistoryLinkedMap.put(alertPattern, new ErrorHistory(System.currentTimeMillis(), 0));

							println(objName + "(" + objType + ") error (" + service
									+ ") => Ok alert !!! (history: " + historyCount + ", diff: "
									+ FormatUtil.print(diff, "#,##0") + " ms)");

							message = service + " - " + errorMsg + (historyCount > 0 ? " (+" + historyCount + ")" : "");
						} else {
							// 마지막 이벤트로부터 기준시간을 경과한 경우
							alertHistoryLinkedMap.put(alertPattern, new ErrorHistory(System.currentTimeMillis(), 0));

							println(objName + "(" + objType + ") error (" + service + ") Put(reset) !!!");
						}
					}
				}

				AlertPack ap = new AlertPack();
				ap.level = AlertLevel.ERROR;
				ap.objHash = pack.objHash;
				ap.title = "xlog Error";
				// ap.title = errorMsg;
				ap.message = message;
				ap.time = System.currentTimeMillis();
				ap.objType = objType;
				alert(ap);
			}

			try {
				// 응답시간의 임계치 (ms) - 기본 값 0일때 응답시간의 임계치 초과 여부를 확인하지 않는다.
				int elapsedThreshold = groupConf.getInt("ext_plugin_elapsed_time_threshold", objType, 0);

				// 응답시간이 임계치를 초과한 경우
				if (elapsedThreshold != 0 && pack.elapsed > elapsedThreshold) {

					String message = "(" + serviceName + ") "
							+ "elapsed time(" + FormatUtil.print(pack.elapsed, "#,##0") + " ms) exceed a threshold.";

					int historyCount = 0;
					byte alertLevel = AlertLevel.INFO;
					String alertPattern = pack.objHash + "_" + pack.service + "_elapsed";
					if (!alertHistoryLinkedMap.containsKey(alertPattern)) {
						alertHistoryLinkedMap.put(alertPattern,
								new ElapsedServiceHistory(System.currentTimeMillis(), 0));

						println(objName + "(" + objType + ") elapsed (" + serviceName + ") Put !!! ("
								+ FormatUtil.print(pack.elapsed, "#,##0") + " ms)");
						return;
					} else {
						AlertHistory elapsedService = (ElapsedServiceHistory) alertHistoryLinkedMap.get(alertPattern);
						long diff = System.currentTimeMillis() - elapsedService.getLastModified();
						int elapsedTimeInterval = conf.getInt("ext_plugin_elapsed_time_interval",
								ELAPSED_TIME_INTERVAL);

						// alert 주기를 초과하지 않으면 alert하지 않는다.
						if (diff < elapsedTimeInterval * 60 * 1000L) {
							historyCount = elapsedService.addCount();
							alertHistoryLinkedMap.put(alertPattern, elapsedService);

							println(objName + "(" + objType + ") elapsed (" + serviceName
									+ ") => Not yet (history: " + historyCount + ", diff: "
									+ FormatUtil.print(diff, "#,##0") + " ms)");
							return;
						} else {
							if (diff < elapsedTimeInterval * 60 * 1000L * 2) {
								historyCount = elapsedService.getHistoryCount();
								float historyAvg = (float) historyCount
										/ (elapsedTimeInterval * 60);

								if (historyCount > 0) {
									if (historyAvg >= (float) conf.getInt("ext_plugin_elapsed_time_avg_threshold", 1)) {
										alertLevel = AlertLevel.FATAL;
									} else {
										alertLevel = AlertLevel.WARN;
									}
								} else {
									alertLevel = AlertLevel.INFO;
								}

								println(objName + "(" + objType + ") elapsed (" + serviceName
										+ ") => Ok alert !!! (history: " + historyCount + ", avg: " + historyAvg
										+ ", diff: " + FormatUtil.print(diff, "#,##0") + " ms)");
								alertHistoryLinkedMap.put(alertPattern,
										new ElapsedServiceHistory(System.currentTimeMillis(), 0));

								if (alertLevel == AlertLevel.INFO) {
									return;
								}

								message = "(" + serviceName + ") " + "elapsed time("
										+ FormatUtil.print(pack.elapsed, "#,##0")
										+ " ms) exceed a threshold."
										+ (historyCount > 0 ? " (+" + historyCount + ")" : "");
							} else {
								// 마지막 이벤트로부터 기준시간을 경과한 경우
								alertHistoryLinkedMap.put(alertPattern,
										new ElapsedServiceHistory(System.currentTimeMillis(), 0));

								println(objName + "(" + objType + ") elapsed (" + serviceName
										+ ") Put(reset) !!! (" + FormatUtil.print(pack.elapsed, "#,##0") + " ms)");
								return;
							}
						}
					}

					AlertPack ap = new AlertPack();
					// ap.level = AlertLevel.WARN;
					ap.level = alertLevel;
					ap.objHash = pack.objHash;
					ap.title = "Elapsed time exceed a threshold.";
					ap.message = message;
					ap.time = System.currentTimeMillis();
					ap.objType = objType;
					alert(ap);
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
			// in case of objFamily is javaee
			if (CounterConstants.FAMILY_JAVAEE.equals(objFamily)) {
				// save javaee type's objHash
				if (!javaeeObjHashList.contains(objHash)) {
					javaeeObjHashList.add(objHash);
				}

				if (pack.timetype == TimeTypeEnum.REALTIME) {
					// GC Time의 임계치 (ms) - 기본 값 0일때 GC Time의 임계치 초과 여부를 확인하지 않는다.
					long gcTimeThreshold = groupConf.getLong("ext_plugin_gc_time_threshold", objType, 0);
					long gcTime = pack.data.getLong(CounterConstants.JAVA_GC_TIME);

					if (gcTimeThreshold != 0 && gcTime > gcTimeThreshold) {
						int historyCount = 0;
						byte alertLevel = AlertLevel.INFO;
						String message = objName + "'s GC time(" + FormatUtil.print(gcTime, "#,##0")
								+ " ms) exceed a threshold.";

						// save javaee type's objHash
						String alertPattern = objHash + "_" + CounterConstants.JAVA_GC_TIME;
						if (!alertHistoryLinkedMap.containsKey(alertPattern)) {
							alertHistoryLinkedMap.put(alertPattern, new AlertHistory(System.currentTimeMillis(), 0));

							println(pack.objName + "(" + objType + ") : GC Time(" + FormatUtil.print(gcTime, "#,##0")
									+ " ms) => Put !!!");
							return;
						} else {
							AlertHistory alertHistory = alertHistoryLinkedMap.get(alertPattern);
							long diff = System.currentTimeMillis() - alertHistory.getLastModified();
							int gcTimeInterval = conf.getInt("ext_plugin_gc_time_interval", GC_TIME_INTERVAL);

							println("gcTimeInterval : " + gcTimeInterval);

							if (diff < gcTimeInterval * 60 * 1000L) {
								historyCount = alertHistory.addCount();
								alertHistoryLinkedMap.put(alertPattern, alertHistory);

								println(pack.objName + "(" + objType + ") : GC Time("
										+ FormatUtil.print(gcTime, "#,##0")
										+ " ms) => Not yet (history: " + historyCount + ", diff: "
										+ FormatUtil.print(diff, "#,##0") + " ms)");
								return;
							} else {
								if (diff < gcTimeInterval * 60 * 1000L * 2) {
									// 알람레벨 조정 : 임계치를 초과한 상태가 지속될 경우에는 WARN, 단발성일 경우 INFO
									historyCount = alertHistory.getHistoryCount();
									alertLevel = historyCount > 0 ? AlertLevel.FATAL : AlertLevel.INFO;
									alertHistoryLinkedMap.put(alertPattern,
											new AlertHistory(System.currentTimeMillis(), 0));

									println(pack.objName + " (" + objType + ") : GC Time("
											+ FormatUtil.print(gcTime, "#,##0")
											+ " ms) => Ok alert !!! (history: " + historyCount + ", diff: "
											+ FormatUtil.print(diff, "#,##0")
											+ " ms)");

									message = objName + "'s GC time(" + FormatUtil.print(gcTime, "#,##0")
											+ " ms) exceed a threshold. (+" + historyCount + ")";
								} else {
									// 마지막 이벤트로부터 기준시간을 경과한 경우
									alertHistoryLinkedMap.put(alertPattern,
											new AlertHistory(System.currentTimeMillis(), 0));

									println(pack.objName + " (" + objType + ") : GC Time("
											+ FormatUtil.print(gcTime, "#,##0")
											+ " ms) => Put(reset) !!!");
									return;
								}
							}
						}

						AlertPack ap = new AlertPack();

						// ap.level = AlertLevel.WARN;
						ap.level = alertLevel;
						ap.objHash = objHash;
						ap.title = "GC time exceed a threshold.";
						ap.message = message;
						ap.time = System.currentTimeMillis();
						ap.objType = objType;

						alert(ap);
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

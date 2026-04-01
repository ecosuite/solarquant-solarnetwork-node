/* ==================================================================
 * SolarQuantService.java - 31/03/2026
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 * ==================================================================
 */

package net.solarnetwork.node.datum.solarquant;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.domain.datum.SimpleDatum;
import net.solarnetwork.node.service.DatumQueue;
import net.solarnetwork.node.service.IdentityService;
import net.solarnetwork.service.PingTest;
import net.solarnetwork.service.PingTestResult;
import net.solarnetwork.node.service.support.BaseIdentifiable;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;
import net.solarnetwork.settings.SettingsChangeObserver;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;
import net.solarnetwork.settings.support.BasicTitleSettingSpecifier;

/**
 * Forward datum to a SolarQuant service and post predictions back to the queue.
 *
 * @author thomas
 * @version 1.0
 */
public class SolarQuantService extends BaseIdentifiable
		implements Consumer<NodeDatum>, SettingSpecifierProvider, SettingsChangeObserver, PingTest {

	/** The default value for the {@code serviceUrl} property. */
	public static final String DEFAULT_SERVICE_URL = "http://localhost:8000";

	/** The default value for the {@code sourceIdRegexValue} property. */
	public static final String DEFAULT_SOURCE_ID_REGEX = ".*";

	/** The default value for the {@code uploadSourceId} property. */
	public static final String DEFAULT_UPLOAD_SOURCE_ID = "/solarquant";

	/** The default value for the {@code dockerCommand} property. */
	public static final String DEFAULT_DOCKER_COMMAND = "/opt/solarnode/bin/solarquant";

	/** The default value for the {@code flushIntervalSecs} property. */
	public static final int DEFAULT_FLUSH_INTERVAL_SECS = 60;

	/** The default value for the {@code connectionTimeoutMs} property. */
	public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;

	/** The default value for the {@code readTimeoutMs} property. */
	public static final int DEFAULT_READ_TIMEOUT_MS = 30000;

	private final DatumQueue datumQueue;
	private final IdentityService identityService;

	private String serviceUrl = DEFAULT_SERVICE_URL;
	private String sourceIdRegexValue = DEFAULT_SOURCE_ID_REGEX;
	private String uploadSourceId = DEFAULT_UPLOAD_SOURCE_ID;
	private String containerImage = "";
	private String dockerCommand = DEFAULT_DOCKER_COMMAND;
	private int flushIntervalSecs = DEFAULT_FLUSH_INTERVAL_SECS;
	private int connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
	private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

	private volatile Pattern sourceIdRegex;
	private volatile String lastStatusMessage;
	private final ConcurrentLinkedQueue<NodeDatum> datumBuffer = new ConcurrentLinkedQueue<>();
	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> flushTask;
	private HttpClient httpClient;
	private ObjectMapper objectMapper;

	/**
	 * Constructor.
	 *
	 * @param datumQueue
	 *        the datum queue
	 * @param identityService
	 *        the identity service
	 */
	public SolarQuantService(DatumQueue datumQueue, IdentityService identityService) {
		super();
		this.datumQueue = datumQueue;
		this.identityService = identityService;
	}

	public synchronized void serviceDidStartup() {
		compileSourceIdRegex();
		objectMapper = new ObjectMapper();
		httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(connectionTimeoutMs))
				.build();

		startContainer();

		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "SolarQuant-Flush");
			t.setDaemon(true);
			return t;
		});
		flushTask = scheduler.scheduleAtFixedRate(this::flushDatums,
				flushIntervalSecs, flushIntervalSecs, TimeUnit.SECONDS);

		datumQueue.addConsumer(this);
		log.info("SolarQuant service started; forwarding to {}", serviceUrl);
	}

	public synchronized void serviceDidShutdown() {
		datumQueue.removeConsumer(this);

		if ( flushTask != null ) {
			flushTask.cancel(false);
			flushTask = null;
		}
		if ( scheduler != null ) {
			scheduler.shutdown();
			scheduler = null;
		}

		flushDatums();

		stopContainer();

		httpClient = null;
		objectMapper = null;
		log.info("SolarQuant service stopped.");
	}

	public synchronized void configurationChanged(Map<String, Object> properties) {
		serviceDidShutdown();
		serviceDidStartup();
	}

	@Override
	public void accept(NodeDatum datum) {
		if ( datum == null || datum.getSourceId() == null ) {
			return;
		}

		final String prefix = uploadSourceId;
		if ( prefix != null && datum.getSourceId().startsWith(prefix) ) {
			return;
		}

		final Pattern regex = sourceIdRegex;
		if ( regex != null && !regex.matcher(datum.getSourceId()).matches() ) {
			return;
		}

		datumBuffer.add(datum);
	}

	private void flushDatums() {
		final List<NodeDatum> batch = new ArrayList<>();
		NodeDatum d;
		while ( (d = datumBuffer.poll()) != null ) {
			batch.add(d);
		}
		if ( batch.isEmpty() ) {
			return;
		}

		final Long nodeId = (identityService != null ? identityService.getNodeId() : null);
		if ( nodeId == null ) {
			log.warn("Node ID not available; discarding {} buffered datums", batch.size());
			return;
		}

		final HttpClient client = this.httpClient;
		final ObjectMapper mapper = this.objectMapper;
		if ( client == null || mapper == null ) {
			return;
		}

		try {
			List<Map<String, Object>> datumsList = new ArrayList<>(batch.size());
			for ( NodeDatum datum : batch ) {
				Map<String, Object> dm = new LinkedHashMap<>();
				dm.put("nodeId", nodeId);
				dm.put("sourceId", datum.getSourceId());
				dm.put("timestamp", datum.getTimestamp().getEpochSecond());

				DatumSamplesOperations ops = datum.asSampleOperations();
				if ( ops != null ) {
					addSampleData(dm, "i", ops, DatumSamplesType.Instantaneous);
					addSampleData(dm, "a", ops, DatumSamplesType.Accumulating);
					addSampleData(dm, "s", ops, DatumSamplesType.Status);
				}

				datumsList.add(dm);
			}

			Map<String, Object> requestBody = Map.of("datums", datumsList);
			String json = mapper.writeValueAsString(requestBody);

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(serviceUrl + "/measure"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json))
					.timeout(Duration.ofMillis(readTimeoutMs))
					.build();

			HttpResponse<String> response = client.send(request,
					HttpResponse.BodyHandlers.ofString());

			if ( response.statusCode() == 200 ) {
				processMeasureResponse(response.body(), batch.size(), mapper);
			} else {
				lastStatusMessage = String.format("HTTP %d from %s/measure",
						response.statusCode(), serviceUrl);
				log.warn("SolarQuant service returned {}: {}", response.statusCode(),
						response.body());
			}
		} catch ( IOException e ) {
			lastStatusMessage = "Error: " + e.getMessage();
			log.error("Error forwarding {} datums to SolarQuant service at {}: {}",
					batch.size(), serviceUrl, e.getMessage());
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		} catch ( Exception e ) {
			lastStatusMessage = "Error: " + e.getMessage();
			log.error("Unexpected error flushing datums to SolarQuant service", e);
		}
	}

	private void addSampleData(Map<String, Object> dm, String key,
			DatumSamplesOperations ops, DatumSamplesType type) {
		Map<String, ?> data = ops.getSampleData(type);
		if ( data != null && !data.isEmpty() ) {
			dm.put(key, data);
		}
	}

	private void processMeasureResponse(String responseJson, int sentCount,
			ObjectMapper mapper) {
		try {
			JsonNode root = mapper.readTree(responseJson);
			int accepted = root.has("accepted") ? root.get("accepted").asInt() : 0;

			JsonNode predictions = root.get("predictions");
			if ( predictions == null || !predictions.isArray() || predictions.isEmpty() ) {
				lastStatusMessage = String.format("Sent %d, accepted %d; no predictions",
						sentCount, accepted);
				log.debug("Flushed {} datums to SolarQuant; {} accepted, no predictions",
						sentCount, accepted);
				return;
			}

			final String base = uploadSourceId != null ? uploadSourceId : "";
			int predCount = 0;

			for ( JsonNode pred : predictions ) {
				if ( !pred.has("timestamp") ) {
					continue;
				}

				String index = "1";
				JsonNode metaNode = pred.get("meta");
				if ( metaNode != null && metaNode.has("sourceIndex") ) {
					index = metaNode.get("sourceIndex").asText("1");
				}
				String sourceId = base + "/" + index;

				long ts = pred.get("timestamp").asLong();
				Instant timestamp = Instant.ofEpochSecond(ts);

				DatumSamples samples = new DatumSamples();

				JsonNode iNode = pred.get("i");
				if ( iNode != null && iNode.isObject() ) {
					Iterator<Map.Entry<String, JsonNode>> fields = iNode.fields();
					while ( fields.hasNext() ) {
						Map.Entry<String, JsonNode> e = fields.next();
						if ( e.getValue().isNumber() ) {
							samples.putInstantaneousSampleValue(
									e.getKey(), e.getValue().numberValue());
						}
					}
				}

				JsonNode sNode = pred.get("s");
				if ( sNode != null && sNode.isObject() ) {
					Iterator<Map.Entry<String, JsonNode>> fields = sNode.fields();
					while ( fields.hasNext() ) {
						Map.Entry<String, JsonNode> e = fields.next();
						samples.putStatusSampleValue(e.getKey(), e.getValue().asText());
					}
				}

				if ( metaNode != null && metaNode.isObject() ) {
					Iterator<Map.Entry<String, JsonNode>> fields = metaNode.fields();
					while ( fields.hasNext() ) {
						Map.Entry<String, JsonNode> e = fields.next();
						String key = e.getKey();
						if ( "sourceIndex".equals(key) ) {
							continue;
						}
						if ( e.getValue().isNumber() ) {
							samples.putInstantaneousSampleValue(
									"meta_" + key, e.getValue().numberValue());
						} else {
							samples.putStatusSampleValue(
									"meta_" + key, e.getValue().asText());
						}
					}
				}

				SimpleDatum datum = SimpleDatum.nodeDatum(sourceId, timestamp, samples);
				datumQueue.offer(datum, true);
				predCount++;
			}

			lastStatusMessage = String.format("Sent %d, accepted %d; %d predictions",
					sentCount, accepted, predCount);
			log.info("Flushed {} datums to SolarQuant; {} accepted, {} predictions posted",
					sentCount, accepted, predCount);

		} catch ( Exception e ) {
			lastStatusMessage = "Error parsing response: " + e.getMessage();
			log.error("Error parsing SolarQuant /measure response", e);
		}
	}

	@Override
	public String getPingTestName() {
		return "SolarQuant Anomaly Detection";
	}

	@Override
	public String getPingTestId() {
		return getUid();
	}

	@Override
	public long getPingTestMaximumExecutionMilliseconds() {
		return connectionTimeoutMs + 1000L;
	}

	@Override
	public Result performPingTest() throws Exception {
		final HttpClient client = this.httpClient;
		final ObjectMapper mapper = this.objectMapper;
		if ( client == null || mapper == null ) {
			return new PingTestResult(false, "Service not started");
		}

		if ( containerImage != null && !containerImage.isEmpty() ) {
			if ( !isContainerRunning() ) {
				return new PingTestResult(false, "Container not running");
			}
		}

		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(serviceUrl + "/health"))
					.GET()
					.timeout(Duration.ofMillis(connectionTimeoutMs))
					.build();

			HttpResponse<String> response = client.send(request,
					HttpResponse.BodyHandlers.ofString());

			if ( response.statusCode() == 200 ) {
				JsonNode root = mapper.readTree(response.body());
				String status = root.has("status") ? root.get("status").asText() : "unknown";
				boolean healthy = "healthy".equals(status);

				Map<String, Object> props = new LinkedHashMap<>();
				props.put("status", status);
				if ( root.has("uptime") ) {
					props.put("uptime", root.get("uptime").asDouble());
				}
				JsonNode details = root.get("details");
				if ( details != null && details.isObject() ) {
					Iterator<Map.Entry<String, JsonNode>> fields = details.fields();
					while ( fields.hasNext() ) {
						Map.Entry<String, JsonNode> e = fields.next();
						props.put(e.getKey(), e.getValue().asText());
					}
				}
				return new PingTestResult(healthy, status, props);
			} else {
				return new PingTestResult(false, "HTTP " + response.statusCode());
			}
		} catch ( Exception e ) {
			return new PingTestResult(false, e.getMessage());
		}
	}

	@Override
	public String getSettingUid() {
		return "net.solarnetwork.node.datum.solarquant";
	}

	@Override
	public String getDisplayName() {
		return "SolarQuant Anomaly Detection";
	}

	@Override
	public List<SettingSpecifier> getSettingSpecifiers() {
		final List<SettingSpecifier> results = new ArrayList<>(12);

		results.add(new BasicTitleSettingSpecifier("status", statusMessage(), true, true));

		results.addAll(baseIdentifiableSettings(""));

		results.add(new BasicTextFieldSettingSpecifier("containerImage", ""));
		results.add(new BasicTextFieldSettingSpecifier("serviceUrl", DEFAULT_SERVICE_URL));
		results.add(new BasicTextFieldSettingSpecifier("sourceIdRegexValue",
				DEFAULT_SOURCE_ID_REGEX));
		results.add(new BasicTextFieldSettingSpecifier("uploadSourceId",
				DEFAULT_UPLOAD_SOURCE_ID));
		results.add(new BasicTextFieldSettingSpecifier("flushIntervalSecs",
				String.valueOf(DEFAULT_FLUSH_INTERVAL_SECS)));
		results.add(new BasicTextFieldSettingSpecifier("connectionTimeoutMs",
				String.valueOf(DEFAULT_CONNECTION_TIMEOUT_MS)));
		results.add(new BasicTextFieldSettingSpecifier("readTimeoutMs",
				String.valueOf(DEFAULT_READ_TIMEOUT_MS)));
		results.add(new BasicTextFieldSettingSpecifier("dockerCommand",
				DEFAULT_DOCKER_COMMAND));

		return results;
	}

	private String statusMessage() {
		String msg = lastStatusMessage;
		int buffered = datumBuffer.size();
		if ( msg != null ) {
			return msg + (buffered > 0 ? " (" + buffered + " buffered)" : "");
		}
		return buffered > 0 ? buffered + " datums buffered" : "Idle";
	}

	private String containerName() {
		String uid = getUid();
		return "solarquant-" + (uid != null ? uid : "default");
	}

	private void startContainer() {
		final String image = containerImage;
		if ( image == null || image.isEmpty() ) {
			return;
		}

		try {
			String[] cmd = { dockerCommand, "start", image, containerName() };
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(false);
			Process pr = pb.start();

			String port;
			try ( BufferedReader reader = new BufferedReader(
					new InputStreamReader(pr.getInputStream())) ) {
				port = reader.readLine();
			}

			try ( BufferedReader errReader = new BufferedReader(
					new InputStreamReader(pr.getErrorStream())) ) {
				String line;
				while ( (line = errReader.readLine()) != null ) {
					log.debug("solarquant start: {}", line);
				}
			}

			int exitCode = pr.waitFor();
			if ( exitCode == 0 && port != null && !port.isBlank() ) {
				serviceUrl = "http://localhost:" + port.trim();
				log.info("Started container {} on port {}; serviceUrl = {}",
						containerName(), port.trim(), serviceUrl);
			} else {
				log.error("Failed to start container {} (exit {})", containerName(), exitCode);
			}
		} catch ( IOException e ) {
			log.error("Error starting Docker container: {}", e.getMessage());
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	private void stopContainer() {
		final String image = containerImage;
		if ( image == null || image.isEmpty() ) {
			return;
		}

		try {
			String[] cmd = { dockerCommand, "stop", containerName() };
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process pr = pb.start();

			try ( BufferedReader reader = new BufferedReader(
					new InputStreamReader(pr.getInputStream())) ) {
				String line;
				while ( (line = reader.readLine()) != null ) {
					log.debug("solarquant stop: {}", line);
				}
			}

			int exitCode = pr.waitFor();
			if ( exitCode == 0 ) {
				log.info("Stopped container {}", containerName());
			} else {
				log.warn("Failed to stop container {} (exit {})", containerName(), exitCode);
			}
		} catch ( IOException e ) {
			log.error("Error stopping Docker container: {}", e.getMessage());
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean isContainerRunning() {
		final String image = containerImage;
		if ( image == null || image.isEmpty() ) {
			return true; // not managing container, assume service is external
		}

		try {
			String[] cmd = { dockerCommand, "status", containerName() };
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process pr = pb.start();

			String output;
			try ( BufferedReader reader = new BufferedReader(
					new InputStreamReader(pr.getInputStream())) ) {
				output = reader.readLine();
			}

			pr.waitFor();
			return output != null && output.startsWith("running");
		} catch ( Exception e ) {
			log.debug("Error checking container status: {}", e.getMessage());
			return false;
		}
	}

	private void compileSourceIdRegex() {
		String val = sourceIdRegexValue;
		if ( val == null || val.isEmpty() ) {
			sourceIdRegex = null;
		} else {
			try {
				sourceIdRegex = Pattern.compile(val);
			} catch ( PatternSyntaxException e ) {
				log.warn("Invalid source ID regex [{}]: {}", val, e.getMessage());
				sourceIdRegex = null;
			}
		}
	}

	public String getServiceUrl() {
		return serviceUrl;
	}

	public void setServiceUrl(String serviceUrl) {
		this.serviceUrl = serviceUrl;
	}

	public String getSourceIdRegexValue() {
		return sourceIdRegexValue;
	}

	public void setSourceIdRegexValue(String sourceIdRegexValue) {
		this.sourceIdRegexValue = sourceIdRegexValue;
		compileSourceIdRegex();
	}

	public String getUploadSourceId() {
		return uploadSourceId;
	}

	public void setUploadSourceId(String uploadSourceId) {
		this.uploadSourceId = uploadSourceId;
	}

	public String getContainerImage() {
		return containerImage;
	}

	public void setContainerImage(String containerImage) {
		this.containerImage = containerImage;
	}

	public String getDockerCommand() {
		return dockerCommand;
	}

	public void setDockerCommand(String dockerCommand) {
		this.dockerCommand = dockerCommand;
	}

	public int getFlushIntervalSecs() {
		return flushIntervalSecs;
	}

	public void setFlushIntervalSecs(int flushIntervalSecs) {
		this.flushIntervalSecs = flushIntervalSecs;
	}

	public int getConnectionTimeoutMs() {
		return connectionTimeoutMs;
	}

	public void setConnectionTimeoutMs(int connectionTimeoutMs) {
		this.connectionTimeoutMs = connectionTimeoutMs;
	}

	public int getReadTimeoutMs() {
		return readTimeoutMs;
	}

	public void setReadTimeoutMs(int readTimeoutMs) {
		this.readTimeoutMs = readTimeoutMs;
	}

}

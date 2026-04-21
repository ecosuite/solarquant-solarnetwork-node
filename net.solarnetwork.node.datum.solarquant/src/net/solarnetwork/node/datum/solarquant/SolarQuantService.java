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

import static net.solarnetwork.service.OptionalService.service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.TaskScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.domain.datum.SimpleDatum;
import net.solarnetwork.node.service.DatumQueue;
import net.solarnetwork.node.service.DatumSourceIdProvider;
import net.solarnetwork.node.service.IdentityService;
import net.solarnetwork.node.service.PlaceholderService;
import net.solarnetwork.node.service.support.BaseIdentifiable;
import net.solarnetwork.service.OptionalService;
import net.solarnetwork.service.OptionalService.OptionalFilterableService;
import net.solarnetwork.service.PingTest;
import net.solarnetwork.service.PingTestResult;
import net.solarnetwork.service.ServiceLifecycleObserver;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;
import net.solarnetwork.settings.SettingsChangeObserver;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;
import net.solarnetwork.settings.support.BasicTitleSettingSpecifier;
import net.solarnetwork.util.ByteList;
import net.solarnetwork.web.jakarta.service.HttpRequestCustomizerService;

/**
 * Forward datum to a SolarQuant service and post predictions back to the queue.
 *
 * @author thomas
 * @version 1.0
 */
public class SolarQuantService extends BaseIdentifiable
		implements Consumer<NodeDatum>, SettingSpecifierProvider, SettingsChangeObserver, PingTest,
		DatumSourceIdProvider, ServiceLifecycleObserver {

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

	private static final long PING_MAX_EXECUTION_MS = 10_000L;

	private final DatumQueue datumQueue;
	private final IdentityService identityService;

	private String serviceUrl = DEFAULT_SERVICE_URL;
	private String sourceIdRegexValue = DEFAULT_SOURCE_ID_REGEX;
	private String uploadSourceId = DEFAULT_UPLOAD_SOURCE_ID;
	private String containerImage = "";
	private String dockerCommand = DEFAULT_DOCKER_COMMAND;
	private int flushIntervalSecs = DEFAULT_FLUSH_INTERVAL_SECS;

	private volatile Pattern sourceIdRegex;
	private volatile String lastStatusMessage;
	private final ConcurrentLinkedQueue<NodeDatum> datumBuffer = new ConcurrentLinkedQueue<>();
	private final Set<String> publishedSourceIds = new CopyOnWriteArraySet<>();
	private final TaskScheduler taskScheduler;
	private final ObjectMapper objectMapper;
	private final OptionalService<ClientHttpRequestFactory> httpRequestFactory;
	private ScheduledFuture<?> flushTask;
	private OptionalFilterableService<HttpRequestCustomizerService> httpRequestCustomizer;

	/**
	 * Constructor.
	 *
	 * @param datumQueue
	 *        the datum queue
	 * @param identityService
	 *        the identity service
	 * @param taskScheduler
	 *        the task scheduler for periodic flushes
	 * @param objectMapper
	 *        the JSON object mapper
	 * @param httpRequestFactory
	 *        the HTTP request factory
	 */
	public SolarQuantService(DatumQueue datumQueue, IdentityService identityService,
			TaskScheduler taskScheduler, ObjectMapper objectMapper,
			OptionalService<ClientHttpRequestFactory> httpRequestFactory) {
		super();
		this.datumQueue = datumQueue;
		this.identityService = identityService;
		this.taskScheduler = taskScheduler;
		this.objectMapper = objectMapper;
		this.httpRequestFactory = httpRequestFactory;
	}

	@Override
	public synchronized void serviceDidStartup() {
		compileSourceIdRegex();

		startContainer();

		Duration period = Duration.ofSeconds(flushIntervalSecs);
		flushTask = taskScheduler.scheduleAtFixedRate(this::flushDatums,
				Instant.now().plus(period), period);

		datumQueue.addConsumer(this);
		log.info("SolarQuant service started; forwarding to {}", serviceUrl);
	}

	@Override
	public synchronized void serviceDidShutdown() {
		datumQueue.removeConsumer(this);

		if ( flushTask != null ) {
			flushTask.cancel(false);
			flushTask = null;
		}

		flushDatums();

		stopContainer();

		log.info("SolarQuant service stopped.");
	}

	@Override
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

	@Override
	public Collection<String> publishedSourceIds() {
		return publishedSourceIds;
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

		final ClientHttpRequestFactory reqFactory = service(httpRequestFactory);
		if ( reqFactory == null ) {
			log.warn("HTTP request factory not available; discarding {} buffered datums",
					batch.size());
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

			byte[] json = objectMapper.writeValueAsBytes(Map.of("datums", datumsList));
			ByteList body = new ByteList(json);

			ClientHttpRequest req = reqFactory.createRequest(
					URI.create(serviceUrl + "/measure"), HttpMethod.POST);
			req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			req.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));

			HttpRequestCustomizerService cust = service(httpRequestCustomizer);
			if ( cust != null ) {
				req = cust.apply(reqFactory, req, body, customizerParameters());
			} else {
				req.getHeaders().setContentLength(body.size());
				req.getBody().write(body.toArrayValue());
			}

			try ( ClientHttpResponse response = req.execute() ) {
				String responseBody = new String(
						response.getBody().readAllBytes(), StandardCharsets.UTF_8);
				if ( response.getStatusCode().is2xxSuccessful() ) {
					processMeasureResponse(responseBody, batch.size());
				} else {
					int status = response.getStatusCode().value();
					lastStatusMessage = String.format("HTTP %d from %s/measure",
							status, serviceUrl);
					log.warn("SolarQuant service returned {}: {}", status, responseBody);
				}
			}
		} catch ( IOException e ) {
			lastStatusMessage = "Error: " + e.getMessage();
			log.error("Error forwarding {} datums to SolarQuant service at {}: {}",
					batch.size(), serviceUrl, e.getMessage());
		} catch ( Exception e ) {
			lastStatusMessage = "Error: " + e.getMessage();
			log.error("Unexpected error flushing datums to SolarQuant service", e);
		}
	}

	private Map<String, Object> customizerParameters() {
		PlaceholderService phs = service(getPlaceholderService());
		if ( phs != null ) {
			Map<String, Object> p = new HashMap<>();
			phs.copyPlaceholders(p);
			return p;
		}
		return Collections.emptyMap();
	}

	private void addSampleData(Map<String, Object> dm, String key,
			DatumSamplesOperations ops, DatumSamplesType type) {
		Map<String, ?> data = ops.getSampleData(type);
		if ( data != null && !data.isEmpty() ) {
			dm.put(key, data);
		}
	}

	private void processMeasureResponse(String responseJson, int sentCount) {
		try {
			JsonNode root = objectMapper.readTree(responseJson);
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
					for ( Map.Entry<String, JsonNode> e : iNode.properties() ) {
						if ( e.getValue().isNumber() ) {
							samples.putInstantaneousSampleValue(
									e.getKey(), e.getValue().numberValue());
						}
					}
				}

				JsonNode sNode = pred.get("s");
				if ( sNode != null && sNode.isObject() ) {
					for ( Map.Entry<String, JsonNode> e : sNode.properties() ) {
						samples.putStatusSampleValue(e.getKey(), e.getValue().asText());
					}
				}

				if ( metaNode != null && metaNode.isObject() ) {
					for ( Map.Entry<String, JsonNode> e : metaNode.properties() ) {
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
				publishedSourceIds.add(sourceId);
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
		return PING_MAX_EXECUTION_MS;
	}

	@Override
	public Result performPingTest() throws Exception {
		final ClientHttpRequestFactory reqFactory = service(httpRequestFactory);
		if ( reqFactory == null ) {
			return new PingTestResult(false, "Service not started");
		}

		if ( containerImage != null && !containerImage.isEmpty() ) {
			if ( !isContainerRunning() ) {
				return new PingTestResult(false, "Container not running");
			}
		}

		try {
			ClientHttpRequest req = reqFactory.createRequest(
					URI.create(serviceUrl + "/health"), HttpMethod.GET);
			req.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));

			HttpRequestCustomizerService cust = service(httpRequestCustomizer);
			if ( cust != null ) {
				req = cust.apply(reqFactory, req, new ByteList(), customizerParameters());
			}

			try ( ClientHttpResponse response = req.execute() ) {
				if ( !response.getStatusCode().is2xxSuccessful() ) {
					return new PingTestResult(false,
							"HTTP " + response.getStatusCode().value());
				}
				JsonNode root = objectMapper.readTree(response.getBody());
				String status = root.has("status") ? root.get("status").asText() : "unknown";
				boolean healthy = "healthy".equals(status);

				Map<String, Object> props = new LinkedHashMap<>();
				props.put("status", status);
				if ( root.has("uptime") ) {
					props.put("uptime", root.get("uptime").asDouble());
				}
				JsonNode details = root.get("details");
				if ( details != null && details.isObject() ) {
					for ( Map.Entry<String, JsonNode> e : details.properties() ) {
						props.put(e.getKey(), e.getValue().asText());
					}
				}
				return new PingTestResult(healthy, status, props);
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
		results.add(new BasicTextFieldSettingSpecifier("dockerCommand",
				DEFAULT_DOCKER_COMMAND));
		results.add(new BasicTextFieldSettingSpecifier("httpRequestCustomizerUid", null, false,
				"(objectClass=net.solarnetwork.web.service.HttpRequestCustomizerService)"));

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

	/**
	 * Get the SolarQuant service URL.
	 *
	 * @return the service URL
	 */
	public String getServiceUrl() {
		return serviceUrl;
	}

	/**
	 * Set the SolarQuant service URL.
	 *
	 * @param serviceUrl
	 *        the service URL to set
	 */
	public void setServiceUrl(String serviceUrl) {
		this.serviceUrl = serviceUrl;
	}

	/**
	 * Get the source ID regex used to match datums to forward.
	 *
	 * @return the source ID regex pattern
	 */
	public String getSourceIdRegexValue() {
		return sourceIdRegexValue;
	}

	/**
	 * Set the source ID regex used to match datums to forward.
	 *
	 * @param sourceIdRegexValue
	 *        the source ID regex pattern to set
	 */
	public void setSourceIdRegexValue(String sourceIdRegexValue) {
		this.sourceIdRegexValue = sourceIdRegexValue;
		compileSourceIdRegex();
	}

	/**
	 * Get the source ID prefix used when publishing prediction datums.
	 *
	 * @return the upload source ID prefix
	 */
	public String getUploadSourceId() {
		return uploadSourceId;
	}

	/**
	 * Set the source ID prefix used when publishing prediction datums.
	 *
	 * @param uploadSourceId
	 *        the upload source ID prefix to set
	 */
	public void setUploadSourceId(String uploadSourceId) {
		this.uploadSourceId = uploadSourceId;
	}

	/**
	 * Get the Docker container image to manage.
	 *
	 * @return the container image, or an empty string to disable container
	 *         management
	 */
	public String getContainerImage() {
		return containerImage;
	}

	/**
	 * Set the Docker container image to manage.
	 *
	 * @param containerImage
	 *        the container image to set, or an empty string to disable
	 *        container management
	 */
	public void setContainerImage(String containerImage) {
		this.containerImage = containerImage;
	}

	/**
	 * Get the Docker helper command path.
	 *
	 * @return the Docker command path
	 */
	public String getDockerCommand() {
		return dockerCommand;
	}

	/**
	 * Set the Docker helper command path.
	 *
	 * @param dockerCommand
	 *        the Docker command path to set
	 */
	public void setDockerCommand(String dockerCommand) {
		this.dockerCommand = dockerCommand;
	}

	/**
	 * Get the datum flush interval, in seconds.
	 *
	 * @return the flush interval in seconds
	 */
	public int getFlushIntervalSecs() {
		return flushIntervalSecs;
	}

	/**
	 * Set the datum flush interval, in seconds.
	 *
	 * @param flushIntervalSecs
	 *        the flush interval in seconds to set
	 */
	public void setFlushIntervalSecs(int flushIntervalSecs) {
		this.flushIntervalSecs = flushIntervalSecs;
	}

	/**
	 * Get the HTTP request factory.
	 *
	 * @return the HTTP request factory
	 */
	public OptionalService<ClientHttpRequestFactory> getHttpRequestFactory() {
		return httpRequestFactory;
	}

	/**
	 * Get the HTTP request customizer service.
	 *
	 * @return the HTTP request customizer service
	 */
	public OptionalFilterableService<HttpRequestCustomizerService> getHttpRequestCustomizer() {
		return httpRequestCustomizer;
	}

	/**
	 * Set the HTTP request customizer service.
	 *
	 * @param httpRequestCustomizer
	 *        the HTTP request customizer service to set
	 */
	public void setHttpRequestCustomizer(
			OptionalFilterableService<HttpRequestCustomizerService> httpRequestCustomizer) {
		this.httpRequestCustomizer = httpRequestCustomizer;
	}

	/**
	 * Get the UID of the HTTP request customizer service to use.
	 *
	 * @return the HTTP request customizer service UID, or {@literal null} if
	 *         none configured
	 */
	public String getHttpRequestCustomizerUid() {
		final OptionalFilterableService<HttpRequestCustomizerService> s = getHttpRequestCustomizer();
		return (s != null ? s.getPropertyValue(UID_PROPERTY) : null);
	}

	/**
	 * Set the UID of the HTTP request customizer service to use.
	 *
	 * @param uid
	 *        the HTTP request customizer service UID to set
	 */
	public void setHttpRequestCustomizerUid(String uid) {
		final OptionalFilterableService<HttpRequestCustomizerService> s = getHttpRequestCustomizer();
		if ( s != null ) {
			s.setPropertyFilter(UID_PROPERTY, uid);
		}
	}

}

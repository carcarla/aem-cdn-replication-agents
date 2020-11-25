package carz.aem.cdn.replication.verizon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.day.cq.replication.*;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpPut;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.slf4j.LoggerFactory;

@Component(service = TransportHandler.class,
  immediate = true,
  property = {"service.ranking:Integer=1001"})
public class VerizonTransportHandler implements TransportHandler {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(VerizonTransportHandler.class);

  private final static String SERIALIZATION_TYPE = "verizon";
  private final static String PROPERTY_TOKEN = "transportToken";
  private final static String BULK_PURGE_URL = "/edge/bulkpurge";
  private final static String BULK_LOAD_URL = "/edge/bulkload";
  private final static String JSON_PROPERTY_MEDIA_PATH = "MediaPath";
  private final static String JSON_PROPERTY_MEDIA_TYPE = "MediaType";
  private final static String MEDIA_TYPE_VALUE = "14";

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean canHandle(AgentConfig config) {
    final String serializationType = config.getSerializationType();
    final String token = String.valueOf(config.getProperties().get(PROPERTY_TOKEN));
    return serializationType != null && serializationType.equalsIgnoreCase(SERIALIZATION_TYPE) && config.getTransportURI() != null && token != null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ReplicationResult deliver(TransportContext ctx, ReplicationTransaction tx)
    throws ReplicationException {

    final ReplicationActionType replicationType = tx.getAction().getType();
    final AgentConfig agentConfig = ctx.getConfig();

    if (replicationType == ReplicationActionType.TEST) {
      final String requestURL = agentConfig.getTransportURI() + BULK_LOAD_URL;
      return doAction(ctx, tx, requestURL);
    } else if (replicationType == ReplicationActionType.ACTIVATE ||
      replicationType == ReplicationActionType.DEACTIVATE) {
      final String requestURL = agentConfig.getTransportURI() + BULK_PURGE_URL;
      return doAction(ctx, tx, requestURL);
    } else {
      throw new ReplicationException("Replication action type " + replicationType + " not supported.");
    }
  }

  /**
   * Send request to Verizon via a PUT request.
   * <p>
   * Verizon will respond with a 200 HTTP status code if the request was
   * successfully submitted. The response will have information about the
   * queue length, but we're simply interested in the fact that the request
   * was authenticated.
   *
   * @param ctx        Transport Context
   * @param tx         Replication Transaction
   * @param requestUrl Request URL
   * @return ReplicationResult OK if 200 response from Verizon
   * @throws ReplicationException
   */
  private ReplicationResult doAction(TransportContext ctx, ReplicationTransaction tx, String requestUrl)
    throws ReplicationException {

    final ReplicationLog log = tx.getLog();

    if (tx.getContent().getContentLength()<=0) {
      log.info("No Content need to purge.");
      return ReplicationResult.OK;
    }

    final HttpPut request = new HttpPut(requestUrl);

    log.info("Create new HttpClient for %s", ctx.getName());
    log.info("Sending %s request to %s", request.getMethod(), request.getURI());

    createPutBody(request, ctx, tx);

    final HttpResponse response = sendRequest(request, ctx, tx);

    if (response != null) {
      final int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode == HttpStatus.SC_OK) {
        log.info("Replication (%s) of %s successful.", tx.getAction().getType(), tx.getAction().getPath());
        return ReplicationResult.OK;
      }
    }
    log.info("Replication (%s) of %s not successful.", tx.getAction().getType(), tx.getAction().getPath());
    return new ReplicationResult(false, 0, response.getStatusLine().getReasonPhrase());
  }

  /**
   * Build preemptive basic authentication headers and send request.
   *
   * @param request The request to send to Verizon
   * @param ctx     The TransportContext containing the username and password
   * @return HttpResponse The HTTP response from Verizon
   * @throws ReplicationException if a request could not be sent
   */
  private <T extends HttpRequestBase> HttpResponse sendRequest(final T request,
                                                               final TransportContext ctx, final ReplicationTransaction tx)
    throws ReplicationException {

    final ReplicationLog log = tx.getLog();
    final ValueMap agentConfig = ctx.getConfig().getProperties();

    final String token = PropertiesUtil.toString(agentConfig.get(PROPERTY_TOKEN), "");
    request.setHeader(HttpHeaders.AUTHORIZATION, "TOK: " + token);
    request.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());

    HttpClient client = HttpClientBuilder.create().build();
    HttpResponse response;

    try {
      response = client.execute(request);
      if (response != null) {
        final int statusCode = response.getStatusLine().getStatusCode();
        log.info("sent. Response: %s %s", statusCode, response.getStatusLine().getReasonPhrase());
        log.info("------------------------------------------------");
        log.info(" >> %s", request.getRequestLine());
        for (Header header : request.getAllHeaders()) {
          if (!HttpHeaders.AUTHORIZATION.equalsIgnoreCase(header.getName())) {
            log.info(" >> %s : %s", header.getName(), header.getValue());
          }
        }
        log.info(" -- ");
        log.info(" << %s", response.getStatusLine());
        for (Header header : response.getAllHeaders()) {
          log.info(" << %s : %s", header.getName(), header.getValue());
        }
        log.info(" << ");
        String text = new BufferedReader(
          new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.joining("\n"));
        log.info(" << %s", text);
        log.info(" << ");
        log.info(" << ");
        log.info("Message sent.");
        log.info("------------------------------------------------");
      }
    } catch (IOException e) {
      throw new ReplicationException("Could not send replication request.", e);
    }

    return response;
  }

  /**
   * Build the Verizon purge request body based on the replication agent
   * settings and append it to the PUT request.
   *
   * @param request The HTTP PUT request to append the request body
   * @param ctx     TransportContext
   * @param tx      ReplicationTransaction
   * @throws ReplicationException if errors building the request body
   */
  private void createPutBody(final HttpPut request, final TransportContext ctx,
                             final ReplicationTransaction tx) throws ReplicationException {

    ReplicationLog log = tx.getLog();
    JSONObject json = new JSONObject();
    JSONArray purgeObjects = null;

    /*
     * Get the content created with the custom content builder class
     *
     * The list of activated resources (e.g.: ["/content/geometrixx/en/blog"])
     * is available in tx.getAction().getPaths(). For this example, we want the
     * content created in our custom content builder which is available in
     * tx.getContent().getInputStream().
     */
    try {
      final String content = IOUtils.toString(tx.getContent().getInputStream(), StandardCharsets.UTF_8);
      if (StringUtils.isNotBlank(content)) {
        purgeObjects = new JSONArray(content);
      }
    } catch (IOException | JSONException e) {
      throw new ReplicationException("Could not retrieve content from content builder", e);
    }

    if (purgeObjects != null && purgeObjects.length() > 0) {
      try {
        json.put(JSON_PROPERTY_MEDIA_PATH, purgeObjects)
          .put(JSON_PROPERTY_MEDIA_TYPE, MEDIA_TYPE_VALUE);
      } catch (JSONException e) {
        throw new ReplicationException("Could not build purge request content", e);
      }
      log.info("Message body: %s", json.toString());
      final StringEntity entity = new StringEntity(json.toString(), CharEncoding.UTF_8);
      request.setEntity(entity);

    } else {
      throw new ReplicationException("No path to purge");
    }
  }
}

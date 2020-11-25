package com.carz.aem.cdn.replication.akamai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import com.carz.aem.cdn.replication.constants.TransportConstants;
import com.day.cq.replication.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.apache.jackrabbit.util.Base64;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;

import com.akamai.edgegrid.signer.ClientCredential;
import com.akamai.edgegrid.signer.exceptions.RequestSigningException;
import com.akamai.edgegrid.signer.googlehttpclient.GoogleHttpClientEdgeGridRequestSigner;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.apache.ApacheHttpTransport;

/**
 * Transport handler to send test and purge requests to Akamai and handle
 * responses. The handler sets up basic authentication with the user/pass from
 * the replication agent's transport config and sends a GET request as a test
 * and POST as purge request. A valid test response is 200 while a valid purge
 * response is 201.
 *
 * The transport handler is triggered by setting your replication agent's
 * transport URL's protocol to "akamai://".
 *
 * The transport handler builds the POST request body in accordance with
 * Akamai's Fast Purge REST API {@link https://developer.akamai.com/api/core_features/fast_purge/v3.html}
 * using the replication agent properties.
 */
@Component(service = TransportHandler.class,
  immediate = true,
  property = {"service.ranking:Integer=1001"})
public class AkamaiTransportHandler implements TransportHandler {

  private final static String SERIALIZATION_TYPE = "akamai";
  private static final String AKAMAI_PROTOCOL = "akamai://";

  /** Replication agent type property name. Valid values are "arl" and "cpcode". */
  private static final String PROPERTY_AKAMAI_TYPE = "type";

  /** Replication agent environment property name. Valid values are "staging" and "production". */
  private static final String PROPERTY_AKAMAI_ENV = "environment";

  /** Replication agent action property name. Valid values are "remove" and "invalidate". */
  private static final String PROPERTY_AKAMAI_ACTION = "action";

  /** Replication agent default type value */
  private static final String PROPERTY_AKAMAI_TYPE_DEFAULT = "url";

  /** Replication agent default environment value */
  private static final String PROPERTY_AKAMAI_ENV_DEFAULT = "production";

  /** Replication agent default action value */
  private static final String PROPERTY_AKAMAI_ACTION_DEFAULT = "invalidate";

  private static final String PROPERTY_CLIENT_TOKEN = "clientToken";
  private static final String PROPERTY_CLIENT_SECRET = "clientSecret";
  private static final String PROPERTY_ACCESS_TOKEN = "accessToken";

  /**
   * {@inheritDoc}
   *  * The transport handler is triggered by setting your replication agent's
   *  * transport URL's protocol to "akamai://".
   */
  @Override
  public boolean canHandle(AgentConfig config) {
    final String transportURI = config.getTransportURI();
    final String serializationType = config.getSerializationType();
    return serializationType != null && serializationType.equalsIgnoreCase(SERIALIZATION_TYPE) && config.getTransportURI() != null && (transportURI != null) && (transportURI.toLowerCase().startsWith(AKAMAI_PROTOCOL));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ReplicationResult deliver(TransportContext ctx, ReplicationTransaction tx)
    throws ReplicationException {

    final ReplicationActionType replicationType = tx.getAction().getType();

    try {
      if (replicationType == ReplicationActionType.TEST) {
        return doAction(ctx, tx);
      } else if (replicationType == ReplicationActionType.ACTIVATE ||
        replicationType == ReplicationActionType.DEACTIVATE ||
        replicationType == ReplicationActionType.DELETE) {
        return doAction(ctx, tx);
      }
    } catch (IOException e) {
      throw new ReplicationException("IO Exception in deliver: {}", e);
    }
    throw new ReplicationException("Replication action type " + replicationType + " not supported.");
  }

  private String getTransportURI(TransportContext ctx) {
    final ValueMap properties = ctx.getConfig().getProperties();
    final String AKAMAI_HOST = ctx.getConfig().getTransportURI().replace(AKAMAI_PROTOCOL, TransportConstants.HTTPS);
    final String environment = PropertiesUtil.toString(properties.get(PROPERTY_AKAMAI_ENV), PROPERTY_AKAMAI_ENV_DEFAULT);
    final String action = PropertiesUtil.toString(properties.get(PROPERTY_AKAMAI_ACTION), PROPERTY_AKAMAI_ACTION_DEFAULT);
    final String type = PropertiesUtil.toString(properties.get(PROPERTY_AKAMAI_TYPE), PROPERTY_AKAMAI_TYPE_DEFAULT);
    return AKAMAI_HOST + "/ccu/v3/"
      + action + TransportConstants.BACK_SLASH + type + TransportConstants.BACK_SLASH + environment;
  }

  /**
   * Send purge request to Akamai via a POST request
   *
   * Akamai will respond with a 201 HTTP status code if the purge request was
   * successfully submitted.
   *
   * @param ctx Transport Context
   * @param tx Replication Transaction
   * @return ReplicationResult OK if 201 response from Akamai
   * @throws ReplicationException if a request could not be sent
   * @throws IOException if a request could not be sent
   */
  private ReplicationResult doAction(TransportContext ctx, ReplicationTransaction tx)
    throws ReplicationException, IOException {

    final ReplicationLog log = tx.getLog();

    if (tx.getContent().getContentLength()<=0) {
      log.info("No Content need to purge.");
      return ReplicationResult.OK;
    }

    final ValueMap properties = ctx.getConfig().getProperties();
    final String AKAMAI_ACCESS_TOKEN = PropertiesUtil.toString(properties.get(PROPERTY_ACCESS_TOKEN), "");
    final String AKAMAI_CLIENT_TOKEN = PropertiesUtil.toString(properties.get(PROPERTY_CLIENT_TOKEN), "");
    final String AKAMAI_CLIENT_SECRET = PropertiesUtil.toString(properties.get(PROPERTY_CLIENT_SECRET), "");
    final String AKAMAI_HOST = ctx.getConfig().getTransportURI().replace(AKAMAI_PROTOCOL, TransportConstants.HTTPS);

    ClientCredential clientCredential = ClientCredential.builder().accessToken(AKAMAI_ACCESS_TOKEN).
      clientToken(AKAMAI_CLIENT_TOKEN).clientSecret(AKAMAI_CLIENT_SECRET).host(AKAMAI_HOST.replace(TransportConstants.HTTPS, "")).build();

    JSONObject jsonObject = createPostBody(ctx, tx);

    HttpTransport httpTransport = new ApacheHttpTransport();
    HttpRequestFactory httpRequestFactory = httpTransport.createRequestFactory();

    URI uri = URI.create(getTransportURI(ctx));

    HttpRequest request = httpRequestFactory.buildPostRequest(new GenericUrl(uri), ByteArrayContent.fromString("application/json", jsonObject.toString()));

    log.info("Create new HttpClient for %s", ctx.getName());
    log.info("Sending %s request to %s", request.getRequestMethod(), request.getUrl());
    log.info("Message body: %s", jsonObject.toString());

    final HttpResponse response = sendRequest(request, ctx, tx, clientCredential);

    if (response != null) {
      final int statusCode = response.getStatusCode();
      if (statusCode == HttpStatus.SC_CREATED) {
        log.info("Replication (%s) of %s successful.", tx.getAction().getType(), tx.getAction().getPath());
        return ReplicationResult.OK;
      }
    }
    log.info("Replication (%s) of %s not successful.", tx.getAction().getType(), tx.getAction().getPath());
    return new ReplicationResult(false, 0, response != null ? response.getStatusMessage() : "No response.");
  }

  /**
   * Build preemptive basic authentication headers and send request.
   *
   * @param request The request to send to Akamai
   * @param ctx The TransportContext containing the username and password
   * @return JSONObject The HTTP response from Akamai
   * @throws ReplicationException if a request could not be sent
   */
  private HttpResponse sendRequest(final HttpRequest request, final TransportContext ctx,
                                   final ReplicationTransaction tx, ClientCredential clientCredential)
    throws ReplicationException {

    final ReplicationLog log = tx.getLog();

    final String auth = ctx.getConfig().getTransportUser() + ":" + ctx.getConfig().getTransportPassword();
    final String encodedAuth = Base64.encode(auth);

    HttpHeaders httpHeaders = new HttpHeaders();
    httpHeaders.setAuthorization("Basic " + encodedAuth);
    httpHeaders.setContentType(ContentType.APPLICATION_JSON.getMimeType());

    request.setHeaders(httpHeaders);

    HttpResponse response;

    try {
      GoogleHttpClientEdgeGridRequestSigner requestSigner = new GoogleHttpClientEdgeGridRequestSigner(clientCredential);
      requestSigner.sign(request);

      response = request.execute();

      if (response != null) {

        final int statusCode = response.getStatusCode();
        log.info("sent. Response: %s %s", statusCode, response.getStatusMessage());
        log.info("------------------------------------------------");
        log.info(" >> %s %s", request.getRequestMethod(), request.getUrl());
        log.info(" >> Content-Type : %s; charset=%s", request.getHeaders().getContentType(), response.getContentCharset());
        log.info(" -- ");
        log.info(" << %s %s", statusCode, response.getStatusMessage());
        log.info(" << Date : %s", response.getHeaders().getDate());
        log.info(" << Content-Length : %s", response.getHeaders().getContentLength());
        log.info(" << Content-Type : %s; charset=%s", response.getHeaders().getContentType(), response.getContentCharset());
        log.info(" << ");
        String text = new BufferedReader(
          new InputStreamReader(response.getContent(), StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.joining("\n"));
        log.info(" << %s", text);
        log.info(" << ");
        log.info(" << ");
        log.info("Message sent.");
        log.info("------------------------------------------------");
      }
    } catch (IOException e) {
      throw new ReplicationException("Could not send replication request. {}", e);
    } catch (RequestSigningException e) {
      throw new ReplicationException("Signing ceremony unsuccessful. {}", e);
    }
    return response;
  }

  /**
   * Build the Akamai purge request body based on the replication agent
   * settings and append it to the POST request.
   *
   * @param ctx TransportContext
   * @param tx ReplicationTransaction
   * @throws ReplicationException if errors building the request body
   */
  private JSONObject createPostBody(final TransportContext ctx,
                                    final ReplicationTransaction tx) throws ReplicationException {

    final ValueMap properties = ctx.getConfig().getProperties();
    final String type = PropertiesUtil.toString(properties.get(PROPERTY_AKAMAI_TYPE), PROPERTY_AKAMAI_TYPE_DEFAULT);
    JSONObject json = new JSONObject();
    JSONArray purgeObjects = null;

    if (type.equals(PROPERTY_AKAMAI_TYPE_DEFAULT)) {
      try {
        String content = IOUtils.toString(tx.getContent().getInputStream(), Charset.defaultCharset());


        if (StringUtils.isNotBlank(content)) {
          purgeObjects = new JSONArray(content);
        }
      } catch (JSONException | IOException e) {
        throw new ReplicationException("Could not retrieve content from content builder. {}", e);
      }
    }
    if (null != purgeObjects && purgeObjects.length() > 0) {
      try {
        json.put("objects", purgeObjects);
      } catch (JSONException e) {
        throw new ReplicationException("Could not build purge request content. {}", e);
      }
    } else {
      throw new ReplicationException("No CP codes or pages to purge");
    }
    return json;
  }
}

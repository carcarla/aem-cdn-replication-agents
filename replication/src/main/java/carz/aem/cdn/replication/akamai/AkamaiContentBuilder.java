package carz.aem.cdn.replication.akamai;

import carz.aem.cdn.replication.service.CDNFlushRules;
import com.day.cq.replication.*;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.jcr.resource.api.JcrResourceConstants;
import org.apache.sling.settings.SlingSettingsService;
import org.json.JSONArray;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Akamai content builder to create replication content containing a JSON array
 * of URLs for Akamai to purge through the Akamai Transport Handler. This class
 * takes the internal resource path and converts it to external URLs as well as
 * adding vanity URLs and pages that may Sling include the activated resource.
 */
@Component(service = ContentBuilder.class,
  immediate = true,
  property = {"name=akamai", "service.ranking:Integer=1001"},
  configurationPolicy = ConfigurationPolicy.REQUIRE)
public class AkamaiContentBuilder implements ContentBuilder {

  @Reference
  private ResourceResolverFactory resolverFactory;

  @Reference
  private SlingSettingsService slingSettingService;

  @Reference
  private CDNFlushRules cdnFlushRules;

  private static final Logger LOG = LoggerFactory.getLogger(AkamaiContentBuilder.class);

  /**
   * The name of the replication agent
   */
  public static final String NAME = "akamai";

  /**
   * The serialization type as it will display in the replication
   * agent edit dialog selection field.
   */
  public static final String TITLE = "Akamai Purge Agent";

  private final static String PROPERTY_DOMAIN = "akamaiDomain";
  private final static String AUTHOR_RUN_MODE = "author";

  private boolean disableFlushWithoutFlushRules = true;

  @ObjectClassDefinition(name = "Akamai content builder config")
  public @interface Config {

    @AttributeDefinition(name = "Disable flush without flush rules")
    boolean disable_flush_without_flush_rules() default true;

  }

  @Activate
  @Modified
  protected void activate(final AkamaiContentBuilder.Config config) {
    disableFlushWithoutFlushRules = config.disable_flush_without_flush_rules();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ReplicationContent create(Session session, ReplicationAction action,
                                   ReplicationContentFactory factory) throws ReplicationException {
    return create(session, action, factory, null);
  }

  /**
   * Create the replication content containing the public facing URLs for
   * Akamai to purge.
   */
  @Override
  public ReplicationContent create(Session session, ReplicationAction action,
                                   ReplicationContentFactory factory, Map<String, Object> parameters)
    throws ReplicationException {

    final String path = action.getPath();

    final String domain = getDomain(action);

    ResourceResolver resolver = null;
    PageManager pageManager = null;
    JSONArray jsonArray = new JSONArray();

    if (StringUtils.isNotBlank(path)) {
      List<String> targetPathList = cdnFlushRules.getFlushRulesTargetPath(path);

      if (targetPathList.isEmpty() && targetPathList.size() <= 0 && disableFlushWithoutFlushRules) {
        return ReplicationContent.VOID;
      }
      try {
        HashMap<String, Object> sessionMap = new HashMap<>();
        sessionMap.put(JcrResourceConstants.AUTHENTICATION_INFO_SESSION, session);
        resolver = resolverFactory.getResourceResolver(sessionMap);

        if (resolver != null) {
          pageManager = resolver.adaptTo(PageManager.class);
        }
      } catch (LoginException e) {
        LOG.error("Could not retrieve Page Manager", e);
      }

      if (pageManager != null) {
        Page purgedPage = pageManager.getPage(path);

        /*
         * Get the external URL if the resource is a page. Otherwise, use the
         * provided resource path.
         */
        if (purgedPage != null) {

          String link = domain + resolver.map(path);
          jsonArray.put(link + ".html");

          if (slingSettingService.getRunModes().contains(AUTHOR_RUN_MODE)) {
            String authorLink = domain + path.replaceFirst("/content", "");
            if (!link.equalsIgnoreCase(authorLink))
              jsonArray.put(authorLink + ".html");
          }

          if (!link.equalsIgnoreCase(domain + path))
            jsonArray.put(domain + path + ".html");

          /*
           * Add page's vanity URL if it exists.
           */
          final String vanityUrl = purgedPage.getVanityUrl();

          if (StringUtils.isNotBlank(vanityUrl)) {
            jsonArray.put(domain + vanityUrl);
          }

          /*
           * Get containing pages that includes the resource.
           */
          // Run project specific query

        } else {
          jsonArray.put(domain + path);
        }

        for (String targetPath : targetPathList) {
          String targetUrl = resolver.map(targetPath);
          jsonArray.put(domain + targetUrl);
          if (!targetPath.equalsIgnoreCase(targetUrl)) {
            jsonArray.put(domain + targetPath);
          }
        }

        return createContent(factory, action, jsonArray);
      }
    }

    return ReplicationContent.VOID;
  }

  /**
   * Create the replication content containing
   *
   * @param factory   Factory to create replication content
   * @param jsonArray JSON array of URLS to include in replication content
   * @return replication content
   * @throws ReplicationException if an error occurs
   */
  private ReplicationContent createContent(final ReplicationContentFactory factory, ReplicationAction action,
                                           final JSONArray jsonArray) throws ReplicationException {

    Path tempFile;

    try {
      tempFile = Files.createTempFile("akamai_purge_agent", ".tmp");
    } catch (IOException e) {
      throw new ReplicationException("Could not create temporary file", e);
    }

    try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
      writer.write(jsonArray.toString());
      writer.flush();
      return factory.create("text/plain", tempFile.toFile(), true);
    } catch (IOException e) {
      throw new ReplicationException("Could not write to temporary file", e);
    }
  }

  private String getDomain(ReplicationAction action) {
    ValueMap agentConfig = action.getConfig().getProperties();

    String domain = PropertiesUtil.toString(agentConfig.get(PROPERTY_DOMAIN), "");

    return String.format("%s", domain);
  }

  /**
   * {@inheritDoc}
   *
   * @return {@value #NAME}
   */
  @Override
  public String getName() {
    return NAME;
  }

  /**
   * {@inheritDoc}
   *
   * @return {@value #TITLE}
   */
  @Override
  public String getTitle() {
    return TITLE;
  }

}

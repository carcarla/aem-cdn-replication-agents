package com.carz.aem.cdn.replication.service;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component(service = CDNFlushRulesConfig.class,
  configurationPolicy = ConfigurationPolicy.REQUIRE,
  immediate = true)
@Designate(ocd = CDNFlushRulesConfigImpl.Config.class, factory = true)
public class CDNFlushRulesConfigImpl implements CDNFlushRulesConfig {

  private static final Logger LOG = LoggerFactory.getLogger(CDNFlushRulesConfigImpl.class);

  private String[] flushRules;
  private Map<Pattern, String[]> hierarchicalFlushRules = new LinkedHashMap<Pattern, String[]>();

  @ObjectClassDefinition(name = "CDN flush rules config")
  public @interface Config {

    @AttributeDefinition(name = "Flush Rules",
      description = "Pattern to Path associations for flush rules. "
        + "Format: <pattern-of-trigger-content>=<path-to-flush>")
    String[] flush_rules() default {};

  }

  @Activate
  @Modified
  protected void activate(final CDNFlushRulesConfigImpl.Config config) {
    flushRules = config.flush_rules();
    try {
      hierarchicalFlushRules = configureFlushRules(toMap(flushRules, "="));
    } catch (Exception e) {
      LOG.info("Exception on hierarchicalFlushRules: {}", e);
    }
  }

  @Override
  public Map<Pattern, String[]> getHierarchicalFlushRules() {
    return hierarchicalFlushRules;
  }

  protected final Map<Pattern, String[]> configureFlushRules(final Map<String, String> configuredRules)
    throws Exception {
    final Map<Pattern, String[]> rules = new LinkedHashMap<Pattern, String[]>();

    for (final Map.Entry<String, String> entry : configuredRules.entrySet()) {
      final Pattern pattern = Pattern.compile(entry.getKey().trim());
      rules.put(pattern, entry.getValue().trim().split("&"));
    }

    return rules;
  }

  private static Map<String, String> toMap(final String[] values, final String separator) {

    final Map<String, String> map = new LinkedHashMap<String, String>();

    if (values == null || values.length < 1) {
      return map;
    }

    for (final String value : values) {
      final String[] tmp = StringUtils.split(value, separator);

      if (tmp.length == 2
        && StringUtils.stripToNull(tmp[0]) != null) {
        map.put(StringUtils.trim(tmp[0]), StringUtils.trimToEmpty(tmp[1]));
      }
    }

    return map;
  }

}

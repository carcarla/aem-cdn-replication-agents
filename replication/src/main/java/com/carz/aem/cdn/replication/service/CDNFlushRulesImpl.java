package com.carz.aem.cdn.replication.service;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component(service = CDNFlushRules.class)
public class CDNFlushRulesImpl implements CDNFlushRules {

  private static final Logger LOG = LoggerFactory.getLogger(CDNFlushRulesImpl.class);

  @Reference(cardinality = ReferenceCardinality.MULTIPLE,
    policy = ReferencePolicy.DYNAMIC,
    bind = "bindCDNFlushRulesConfig",
    unbind = "unbindCDNFlushRulesConfig")
  private final List<CDNFlushRulesConfig> cdnFlushRulesConfigList = new ArrayList<>();

  private Map<Pattern, String[]> hierarchicalFlushRules = new LinkedHashMap<Pattern, String[]>();

  protected synchronized void bindCDNFlushRulesConfig(final CDNFlushRulesConfig config) {
    if (hierarchicalFlushRules == null) {
      hierarchicalFlushRules = new LinkedHashMap<Pattern, String[]>();
    }
    hierarchicalFlushRules.putAll(config.getHierarchicalFlushRules());
  }

  protected synchronized void unbindCDNFlushRulesConfig(final CDNFlushRulesConfig config) {
    if (hierarchicalFlushRules != null && config.getHierarchicalFlushRules() != null) {
      for (Map.Entry<Pattern, String[]> entry : config.getHierarchicalFlushRules().entrySet()) {
        hierarchicalFlushRules.remove(entry.getKey());
      }
    }
  }

  @Override
  public List<String> getFlushRulesTargetPath(String path) {
    List<String> flushRulesTargetPathList = new ArrayList<>();
    if (hierarchicalFlushRules != null && hierarchicalFlushRules.size() > 0) {
      for (final Map.Entry<Pattern, String[]> entry : this.hierarchicalFlushRules.entrySet()) {
        final Pattern pattern = entry.getKey();
        final Matcher m = pattern.matcher(path);
        if (m.matches()) {
          for (final String value : entry.getValue()) {
            final String flushPath = m.replaceAll(value);

            LOG.debug("Requesting hierarchical flush of associated path: {} ~> {}", path,
              flushPath);
            flushRulesTargetPathList.add(flushPath);
          }
        }
      }
    }
    return flushRulesTargetPathList;
  }
}

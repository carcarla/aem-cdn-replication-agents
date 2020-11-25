package carz.aem.cdn.replication.service;

import java.util.Map;
import java.util.regex.Pattern;

public interface CDNFlushRulesConfig {
  public Map<Pattern, String[]> getHierarchicalFlushRules();
}

package com.carz.aem.cdn.replication.service;

import org.osgi.annotation.versioning.ProviderType;

import java.util.List;

@ProviderType
public interface CDNFlushRules {
  public List<String> getFlushRulesTargetPath(String path);
}

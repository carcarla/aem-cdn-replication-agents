# AEM CDN replication agents

This project is custom replication agents to flush Akamai and Verizon CDN cache with flush rules.
OSGi factory config CDNFlushRulesConfigImpl-xxxxx.

* Each agent suggested enable in one publisher only, which can control by create OSGi config (AkamaiContentBuilder.config or VerizonContentBuilder.config) in different run mode.

## Akamai purge agent

### Akamai API
This project is base on Akamai API document to create the bundle.

https://developer.akamai.com/api/core_features/fast_purge/v3.html

### Test Connection
When you click Test Connection it will use Akamai Fast purge V3 API and use “/content” for path of content to test the connection.

### Purge Content
It will use Akamai Fast purge V3 API to flush the content.

### Config
All the Akamai config will config under the replication agent. Serialization Type must be use Akamai Purge Agent.

URI: URI should start with begin with akamai://

## Verizon purge agent

### Verizon API
This project is base on Verizon Rest API document to create the bundle.

https://docs.vdms.com/pdfs/VDMS_Web_Services_REST_API_Guide.pdf

### Test Connection
When you click Test Connection it will use Verizon Bulk Load Content API to test the connection.

### Purge Content
It will use Verizon Bulk purge Content API to flush the content.

### Config
All the Verizon config will config under the replication agent. Serialization Type must be use Verizon Purge Agent.

URI: URI should start with begin with verizon://

## How to build

To build all the modules run in the project root directory the following command with Maven 3:

    mvn clean install




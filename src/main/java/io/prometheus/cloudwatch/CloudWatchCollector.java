package io.prometheus.cloudwatch;

import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;
import com.amazonaws.services.cloudwatch.model.*;
import io.prometheus.client.Collector;
import io.prometheus.client.Counter;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class CloudWatchCollector extends Collector {
    private static final Logger LOGGER = Logger.getLogger(CloudWatchCollector.class.getName());

    static class ActiveConfig implements Cloneable {
        ArrayList<MetricRule> rules;
        AmazonCloudWatchClient client;

        @Override
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }

    static class MetricRule {
      String awsNamespace;
      String awsMetricName;
      int periodSeconds;
      int rangeSeconds;
      int delaySeconds;
      List<String> awsStatistics;
      List<String> awsExtendedStatistics;
      List<String> awsDimensions;
      Map<String,List<String>> awsDimensionSelect;
      Map<String,List<String>> awsDimensionSelectRegex;
      String help;
      boolean cloudwatchTimestamp;
    }

    ActiveConfig activeConfig = new ActiveConfig();

    private static final Counter cloudwatchRequests = Counter.build()
            .labelNames("action", "namespace")
            .name("cloudwatch_requests_total").help("API requests made to CloudWatch").register();

    private static final List<String> brokenDynamoMetrics = Arrays.asList(
            "ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits",
            "ProvisionedReadCapacityUnits", "ProvisionedWriteCapacityUnits",
            "ReadThrottleEvents", "WriteThrottleEvents");

    public CloudWatchCollector(Reader in) throws IOException {
        loadConfig(in, null);
    }
    public CloudWatchCollector(String yamlConfig) {
        this((Map<String, Object>)new Yaml().load(yamlConfig),null);
    }

    /* For unittests. */
    protected CloudWatchCollector(String jsonConfig, AmazonCloudWatchClient client) {
        this((Map<String, Object>)new Yaml().load(jsonConfig), client);
    }

    private CloudWatchCollector(Map<String, Object> config, AmazonCloudWatchClient client) {
        loadConfig(config, client);
    }

    protected void reloadConfig() throws IOException {
        LOGGER.log(Level.INFO, "Reloading configuration");

        loadConfig(new FileReader(WebServer.configFilePath), activeConfig.client);
    }

    protected void loadConfig(Reader in, AmazonCloudWatchClient client) throws IOException {
        loadConfig((Map<String, Object>)new Yaml().load(in), client);
    }
    private void loadConfig(Map<String, Object> config, AmazonCloudWatchClient client) {
        if(config == null) {  // Yaml config empty, set config to empty map.
            config = new HashMap<String, Object>();
        }
        if (!config.containsKey("region")) {
          throw new IllegalArgumentException("Must provide region");
        }

        int defaultPeriod = 60;
        if (config.containsKey("period_seconds")) {
          defaultPeriod = ((Number)config.get("period_seconds")).intValue();
        }
        int defaultRange = 600;
        if (config.containsKey("range_seconds")) {
          defaultRange = ((Number)config.get("range_seconds")).intValue();
        }
        int defaultDelay = 600;
        if (config.containsKey("delay_seconds")) {
          defaultDelay = ((Number)config.get("delay_seconds")).intValue();
        }

        boolean defaultCloudwatchTimestamp = true;
        if (config.containsKey("set_timestamp")) {
            defaultCloudwatchTimestamp = (Boolean)config.get("set_timestamp");
        }

        if (client == null) {
          if (config.containsKey("role_arn")) {
            STSAssumeRoleSessionCredentialsProvider credentialsProvider = new STSAssumeRoleSessionCredentialsProvider(
              (String) config.get("role_arn"),
              "cloudwatch_exporter"
            );
            client = new AmazonCloudWatchClient(credentialsProvider);
          } else {
            client = new AmazonCloudWatchClient();
          }
          Region region = RegionUtils.getRegion((String) config.get("region"));
          client.setEndpoint(getMonitoringEndpoint(region));
        }

        if (!config.containsKey("metrics")) {
          throw new IllegalArgumentException("Must provide metrics");
        }

        ArrayList<MetricRule> rules = new ArrayList<MetricRule>();

        for (Object ruleObject : (List<Map<String,Object>>) config.get("metrics")) {
          Map<String, Object> yamlMetricRule = (Map<String, Object>)ruleObject;
          MetricRule rule = new MetricRule();
          rules.add(rule);
          if (!yamlMetricRule.containsKey("aws_namespace") || !yamlMetricRule.containsKey("aws_metric_name")) {
            throw new IllegalArgumentException("Must provide aws_namespace and aws_metric_name");
          }
          rule.awsNamespace = (String)yamlMetricRule.get("aws_namespace");
          rule.awsMetricName = (String)yamlMetricRule.get("aws_metric_name");
          if (yamlMetricRule.containsKey("help")) {
            rule.help = (String)yamlMetricRule.get("help");
          }
          if (yamlMetricRule.containsKey("aws_dimensions")) {
            rule.awsDimensions = (List<String>)yamlMetricRule.get("aws_dimensions");
          }
          if (yamlMetricRule.containsKey("aws_dimension_select") && yamlMetricRule.containsKey("aws_dimension_select_regex")) {
            throw new IllegalArgumentException("Must not provide aws_dimension_select and aws_dimension_select_regex at the same time");
          }
          if (yamlMetricRule.containsKey("aws_dimension_select")) {
            rule.awsDimensionSelect = (Map<String, List<String>>)yamlMetricRule.get("aws_dimension_select");
          }
          if (yamlMetricRule.containsKey("aws_dimension_select_regex")) {
            rule.awsDimensionSelectRegex = (Map<String,List<String>>)yamlMetricRule.get("aws_dimension_select_regex");
          }
          if (yamlMetricRule.containsKey("aws_statistics")) {
            rule.awsStatistics = (List<String>)yamlMetricRule.get("aws_statistics");
          } else if (!yamlMetricRule.containsKey("aws_extended_statistics")) {
            rule.awsStatistics = new ArrayList(Arrays.asList("Sum", "SampleCount", "Minimum", "Maximum", "Average"));
          }
          if (yamlMetricRule.containsKey("aws_extended_statistics")) {
            rule.awsExtendedStatistics = (List<String>)yamlMetricRule.get("aws_extended_statistics");
          }
          if (yamlMetricRule.containsKey("period_seconds")) {
            rule.periodSeconds = ((Number)yamlMetricRule.get("period_seconds")).intValue();
          } else {
            rule.periodSeconds = defaultPeriod;
          }
          if (yamlMetricRule.containsKey("range_seconds")) {
            rule.rangeSeconds = ((Number)yamlMetricRule.get("range_seconds")).intValue();
          } else {
            rule.rangeSeconds = defaultRange;
          }
          if (yamlMetricRule.containsKey("delay_seconds")) {
            rule.delaySeconds = ((Number)yamlMetricRule.get("delay_seconds")).intValue();
          } else {
            rule.delaySeconds = defaultDelay;
          }
          if (yamlMetricRule.containsKey("set_timestamp")) {
              rule.cloudwatchTimestamp = (Boolean)yamlMetricRule.get("set_timestamp");
          } else {
              rule.cloudwatchTimestamp = defaultCloudwatchTimestamp;
          }
        }

        loadConfig(rules, client);
    }

    private void loadConfig(ArrayList<MetricRule> rules, AmazonCloudWatchClient client) {
        synchronized (activeConfig) {
            activeConfig.client = client;
            activeConfig.rules = rules;
        }
    }

    public String getMonitoringEndpoint(Region region) {
      return "https://" + region.getServiceEndpoint("monitoring");
    }

    private List<List<Dimension>> getDimensions(MetricRule rule, AmazonCloudWatchClient client) {
        if (
                rule.awsDimensions != null &&
                rule.awsDimensionSelect != null &&
                rule.awsDimensions.size() > 0 &&
                rule.awsDimensions.size() == rule.awsDimensionSelect.size() &&
                rule.awsDimensionSelect.keySet().containsAll(rule.awsDimensions)
        ) {
            // The full list of dimensions is known so no need to request it from cloudwatch.
            return permuteDimensions(rule.awsDimensions, rule.awsDimensionSelect);
        } else {
            return listDimensions(rule, client);
        }
    }

    private List<List<Dimension>> permuteDimensions(List<String> dimensions, Map<String, List<String>> dimensionValues) {
        ArrayList<List<Dimension>> result = new ArrayList<List<Dimension>>();

        if (dimensions.size() == 0) {
            result.add(new ArrayList<Dimension>());
        } else {
            List<String> dimensionsCopy = new ArrayList<String>(dimensions);
            String dimensionName = dimensionsCopy.remove(dimensionsCopy.size() - 1);
            for (List<Dimension> permutation : permuteDimensions(dimensionsCopy, dimensionValues)) {
                for (String dimensionValue : dimensionValues.get(dimensionName)) {
                    Dimension dimension = new Dimension();
                    dimension.setValue(dimensionValue);
                    dimension.setName(dimensionName);
                    ArrayList<Dimension> permutationCopy = new ArrayList<Dimension>(permutation);
                    permutationCopy.add(dimension);
                    result.add(permutationCopy);
                }
            }
        }

        return result;
    }

    private List<List<Dimension>> listDimensions(MetricRule rule, AmazonCloudWatchClient client) {
      List<List<Dimension>> dimensions = new ArrayList<List<Dimension>>();
      if (rule.awsDimensions == null) {
        dimensions.add(new ArrayList<Dimension>());
        return dimensions;
      }

      ListMetricsRequest request = new ListMetricsRequest();
      request.setNamespace(rule.awsNamespace);
      request.setMetricName(rule.awsMetricName);
      List<DimensionFilter> dimensionFilters = new ArrayList<DimensionFilter>();
      for (String dimension : rule.awsDimensions) {
        dimensionFilters.add(new DimensionFilter().withName(dimension));
      }
      request.setDimensions(dimensionFilters);

      String nextToken = null;
      do {
        request.setNextToken(nextToken);
        ListMetricsResult result = client.listMetrics(request);
        cloudwatchRequests.labels("listMetrics", rule.awsNamespace).inc();
        for (Metric metric : result.getMetrics()) {
          if (metric.getDimensions().size() != dimensionFilters.size()) {
            // AWS returns all the metrics with dimensions beyond the ones we ask for,
            // so filter them out.
            continue;
          }
          if (useMetric(rule, metric)) {
            dimensions.add(metric.getDimensions());
          }
        }
        nextToken = result.getNextToken();
      } while (nextToken != null);

      return dimensions;
    }

    /**
     * Check if a metric should be used according to `aws_dimension_select` or `aws_dimension_select_regex`
     */
    private boolean useMetric(MetricRule rule, Metric metric) {
      if (rule.awsDimensionSelect == null && rule.awsDimensionSelectRegex == null) {
        return true;
      }
      if (rule.awsDimensionSelect != null  && metricsIsInAwsDimensionSelect(rule, metric)) {
        return true;
      }
      if (rule.awsDimensionSelectRegex != null  && metricIsInAwsDimensionSelectRegex(rule, metric)) {
        return true;
      }
      return false;
    }

    /**
     * Check if a metric is matched in `aws_dimension_select`
     */
    private boolean metricsIsInAwsDimensionSelect(MetricRule rule, Metric metric) {
      Set<String> dimensionSelectKeys = rule.awsDimensionSelect.keySet();
      for (Dimension dimension : metric.getDimensions()) {
        String dimensionName = dimension.getName();
        String dimensionValue = dimension.getValue();
        if (dimensionSelectKeys.contains(dimensionName)) {
          List<String> allowedDimensionValues = rule.awsDimensionSelect.get(dimensionName);
          if (!allowedDimensionValues.contains(dimensionValue)) {
            return false;
          }
        }
      }
      return true;
    }

    /**
     * Check if a metric is matched in `aws_dimension_select_regex`
     */
    private boolean metricIsInAwsDimensionSelectRegex(MetricRule rule, Metric metric) {
      Set<String> dimensionSelectRegexKeys = rule.awsDimensionSelectRegex.keySet();
      for (Dimension dimension : metric.getDimensions()) {
        String dimensionName = dimension.getName();
        String dimensionValue = dimension.getValue();
        if (dimensionSelectRegexKeys.contains(dimensionName)) {
          List<String> allowedDimensionValues = rule.awsDimensionSelectRegex.get(dimensionName);
          if (!regexListMatch(allowedDimensionValues, dimensionValue)) {
            return false;
          }
        }
      }
      return true;
    }

    /**
     * Check if any regex string in a list matches a given input value
     */
    protected static boolean regexListMatch(List<String> regexList, String input) {
      for (String regex: regexList) {
        if (Pattern.matches(regex, input)) {
          return true;
        }
      }
      return false;
    }

    private Datapoint getNewestDatapoint(java.util.List<Datapoint> datapoints) {
      Datapoint newest = null;
      for (Datapoint d: datapoints) {
        if (newest == null || newest.getTimestamp().before(d.getTimestamp())) {
          newest = d;
        }
      }
      return newest;
    }

    private String toSnakeCase(String str) {
      return str.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private String safeName(String s) {
      // Change invalid chars to underscore, and merge underscores.
      return s.replaceAll("[^a-zA-Z0-9:_]", "_").replaceAll("__+", "_");
    }

    private String help(MetricRule rule, String unit, String statistic) {
      if (rule.help != null) {
          return rule.help;
      }
      return "CloudWatch metric " + rule.awsNamespace + " " + rule.awsMetricName
          + " Dimensions: " + rule.awsDimensions + " Statistic: " + statistic
          + " Unit: " + unit;
    }

    private String queryId(List<Dimension> dimensions, String stat) {
      return dimensions.hashCode() + "-" + stat;
    }

    private MetricDataQuery metricDataQuery(String metricName,
                                            List<Dimension> dimensions,
                                            String namespace,
                                            int periodSecs,
                                            String stat) {
      Metric metric = new Metric()
              .withMetricName(metricName)
              .withNamespace(namespace)
              .withDimensions(dimensions);

      MetricStat metricStat = new MetricStat()
              .withMetric(metric)
              .withPeriod(periodSecs)
              .withStat(stat);

      return new MetricDataQuery()
              .withId(queryId(dimensions, stat))
              .withMetricStat(metricStat);
    }

    private List<MetricDataQuery> queriesForRuleDimensions(MetricRule rule, List<Dimension> dimensions) {
      List<MetricDataQuery> queries = new ArrayList<MetricDataQuery>();

      for (String stat : rule.awsStatistics) {
        queries.add(metricDataQuery(rule.awsMetricName, dimensions, rule.awsNamespace, rule.periodSeconds, stat));
      }
      for (String stat : rule.awsExtendedStatistics) {
        queries.add(metricDataQuery(rule.awsMetricName, dimensions, rule.awsNamespace, rule.periodSeconds, stat));
      }

      return queries;
    }

    private GetMetricDataRequest requestForRule(MetricRule rule, ActiveConfig config, long start) {
      List<MetricDataQuery> queries = new ArrayList<MetricDataQuery>();

      Date startDate = new Date(start - 1000 * rule.delaySeconds);
      Date endDate = new Date(start - 1000 * (rule.delaySeconds + rule.rangeSeconds));

      GetMetricDataRequest request = new GetMetricDataRequest()
              .withStartTime(startDate)
              .withEndTime(endDate);

      for (List<Dimension> dimensions : getDimensions(rule, config.client)) {
        queries.addAll(queriesForRuleDimensions(rule, dimensions));
      }
      request.setMetricDataQueries(queries);

      return request;
    }

    private List<MetricFamilySamples.Sample> mfssFromResult(MetricRule rule, GetMetricDataResult result) {
      List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
      return samples;
    }

    private List<MetricFamilySamples> scrape2() throws CloneNotSupportedException {
      ActiveConfig config = (ActiveConfig) activeConfig.clone();
      List<MetricFamilySamples> samples = new ArrayList<MetricFamilySamples>();
      Map<String, List<MetricFamilySamples.Sample>> statsTypeToSamples =
              new HashMap<String, List<MetricFamilySamples.Sample>>();

      long start = System.currentTimeMillis();

      Map<String, MetricDataResult> idsToResults = new HashMap<String, MetricDataResult>();
      for (MetricRule rule : config.rules) {
        GetMetricDataResult getResult = config.client.getMetricData(requestForRule(rule, config, start));
        for (MetricDataResult result : getResult.getMetricDataResults()) {
          idsToResults.put(result.getId(), result);
        }
      }

      return samples;
    }

    private void scrape(List<MetricFamilySamples> mfs) throws CloneNotSupportedException {
      ActiveConfig config = (ActiveConfig) activeConfig.clone();

      long start = System.currentTimeMillis();
      for (MetricRule rule: config.rules) {
        Date startDate = new Date(start - 1000 * rule.delaySeconds); //ok
        Date endDate = new Date(start - 1000 * (rule.delaySeconds + rule.rangeSeconds)); //ok
        GetMetricStatisticsRequest request = new GetMetricStatisticsRequest(); //ok
        request.setNamespace(rule.awsNamespace); //ok
        request.setMetricName(rule.awsMetricName); //ok
        request.setStatistics(rule.awsStatistics); //ok
        request.setExtendedStatistics(rule.awsExtendedStatistics); //ok
        request.setEndTime(startDate); // OK
        request.setStartTime(endDate); // OK
        request.setPeriod(rule.periodSeconds); //ok

        String baseName = safeName(rule.awsNamespace.toLowerCase() + "_" + toSnakeCase(rule.awsMetricName));
        String jobName = safeName(rule.awsNamespace.toLowerCase());
        List<MetricFamilySamples.Sample> sumSamples = new ArrayList<MetricFamilySamples.Sample>();
        List<MetricFamilySamples.Sample> sampleCountSamples = new ArrayList<MetricFamilySamples.Sample>();
        List<MetricFamilySamples.Sample> minimumSamples = new ArrayList<MetricFamilySamples.Sample>();
        List<MetricFamilySamples.Sample> maximumSamples = new ArrayList<MetricFamilySamples.Sample>();
        List<MetricFamilySamples.Sample> averageSamples = new ArrayList<MetricFamilySamples.Sample>();
        HashMap<String, ArrayList<MetricFamilySamples.Sample>> extendedSamples = new HashMap<String, ArrayList<MetricFamilySamples.Sample>>();

        String unit = null;

        if (rule.awsNamespace.equals("AWS/DynamoDB")
                && rule.awsDimensions.contains("GlobalSecondaryIndexName")
                && brokenDynamoMetrics.contains(rule.awsMetricName)) {
            baseName += "_index";
        }

        for (List<Dimension> dimensions: getDimensions(rule, config.client)) {
          request.setDimensions(dimensions);

          GetMetricStatisticsResult result = config.client.getMetricStatistics(request);
          cloudwatchRequests.labels("getMetricStatistics", rule.awsNamespace).inc();
          Datapoint dp = getNewestDatapoint(result.getDatapoints());
          if (dp == null) {
            continue;
          }
          unit = dp.getUnit();

          List<String> labelNames = new ArrayList<String>();
          List<String> labelValues = new ArrayList<String>();
          labelNames.add("job");
          labelValues.add(jobName);
          labelNames.add("instance");
          labelValues.add("");
          for (Dimension d: dimensions) {
            labelNames.add(safeName(toSnakeCase(d.getName())));
            labelValues.add(d.getValue());
          }

          Long timestamp = null;
          if (rule.cloudwatchTimestamp) {
            timestamp = dp.getTimestamp().getTime();
          }

          if (dp.getSum() != null) {
            sumSamples.add(new MetricFamilySamples.Sample(
                baseName + "_sum", labelNames, labelValues, dp.getSum(), timestamp));
          }
          if (dp.getSampleCount() != null) {
            sampleCountSamples.add(new MetricFamilySamples.Sample(
                baseName + "_sample_count", labelNames, labelValues, dp.getSampleCount(), timestamp));
          }
          if (dp.getMinimum() != null) {
            minimumSamples.add(new MetricFamilySamples.Sample(
                baseName + "_minimum", labelNames, labelValues, dp.getMinimum(), timestamp));
          }
          if (dp.getMaximum() != null) {
            maximumSamples.add(new MetricFamilySamples.Sample(
                baseName + "_maximum",labelNames, labelValues, dp.getMaximum(), timestamp));
          }
          if (dp.getAverage() != null) {
            averageSamples.add(new MetricFamilySamples.Sample(
                baseName + "_average", labelNames, labelValues, dp.getAverage(), timestamp));
          }
          if (dp.getExtendedStatistics() != null) {
            for (Map.Entry<String, Double> entry : dp.getExtendedStatistics().entrySet()) {
              ArrayList<MetricFamilySamples.Sample> samples = extendedSamples.get(entry.getKey());
              if (samples == null) {
                samples = new ArrayList<MetricFamilySamples.Sample>();
                extendedSamples.put(entry.getKey(), samples);
              }
              samples.add(new MetricFamilySamples.Sample(
                  baseName + "_" + safeName(toSnakeCase(entry.getKey())), labelNames, labelValues, entry.getValue(), timestamp));
            }
          }
        }

        if (!sumSamples.isEmpty()) {
          mfs.add(new MetricFamilySamples(baseName + "_sum", Type.GAUGE, help(rule, unit, "Sum"), sumSamples));
        }
        if (!sampleCountSamples.isEmpty()) {
          mfs.add(new MetricFamilySamples(baseName + "_sample_count", Type.GAUGE, help(rule, unit, "SampleCount"), sampleCountSamples));
        }
        if (!minimumSamples.isEmpty()) {
          mfs.add(new MetricFamilySamples(baseName + "_minimum", Type.GAUGE, help(rule, unit, "Minimum"), minimumSamples));
        }
        if (!maximumSamples.isEmpty()) {
          mfs.add(new MetricFamilySamples(baseName + "_maximum", Type.GAUGE, help(rule, unit, "Maximum"), maximumSamples));
        }
        if (!averageSamples.isEmpty()) {
          mfs.add(new MetricFamilySamples(baseName + "_average", Type.GAUGE, help(rule, unit, "Average"), averageSamples));
        }
        for (Map.Entry<String, ArrayList<MetricFamilySamples.Sample>> entry : extendedSamples.entrySet()) {
          mfs.add(new MetricFamilySamples(baseName + "_" + safeName(toSnakeCase(entry.getKey())), Type.GAUGE, help(rule, unit, entry.getKey()), entry.getValue()));
        }
      }
    }

    public List<MetricFamilySamples> collect() {
      long start = System.nanoTime();
      double error = 0;
      List<MetricFamilySamples> mfs = new ArrayList<MetricFamilySamples>();
      try {
        scrape(mfs);
      } catch (Exception e) {
        error = 1;
        LOGGER.log(Level.WARNING, "CloudWatch scrape failed", e);
      }
      List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
      samples.add(new MetricFamilySamples.Sample(
          "cloudwatch_exporter_scrape_duration_seconds", new ArrayList<String>(), new ArrayList<String>(), (System.nanoTime() - start) / 1.0E9));
      mfs.add(new MetricFamilySamples("cloudwatch_exporter_scrape_duration_seconds", Type.GAUGE, "Time this CloudWatch scrape took, in seconds.", samples));

      samples = new ArrayList<MetricFamilySamples.Sample>();
      samples.add(new MetricFamilySamples.Sample(
          "cloudwatch_exporter_scrape_error", new ArrayList<String>(), new ArrayList<String>(), error));
      mfs.add(new MetricFamilySamples("cloudwatch_exporter_scrape_error", Type.GAUGE, "Non-zero if this scrape failed.", samples));
      return mfs;
    }

    /**
     * Convenience function to run standalone.
     */
    public static void main(String[] args) throws Exception {
      String region = "eu-west-1";
      if (args.length > 0) {
        region = args[0];
      }
      CloudWatchCollector jc = new CloudWatchCollector(("{"
      + "`region`: `" + region + "`,"
      + "`metrics`: [{`aws_namespace`: `AWS/ELB`, `aws_metric_name`: `RequestCount`, `aws_dimensions`: [`AvailabilityZone`, `LoadBalancerName`]}] ,"
      + "}").replace('`', '"'));
      for(MetricFamilySamples mfs : jc.collect()) {
        System.out.println(mfs);
      }
    }
}


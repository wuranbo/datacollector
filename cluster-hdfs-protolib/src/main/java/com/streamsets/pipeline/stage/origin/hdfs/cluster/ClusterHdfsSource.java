/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.origin.hdfs.cluster;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.streamsets.pipeline.cluster.Consumer;
import com.streamsets.pipeline.cluster.ControlChannel;
import com.streamsets.pipeline.cluster.DataChannel;
import com.streamsets.pipeline.cluster.Producer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.CombineFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.streamsets.pipeline.OffsetAndResult;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.impl.ClusterSource;
import com.streamsets.pipeline.api.ErrorListener;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.OffsetCommitter;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.config.LogMode;
import com.streamsets.pipeline.config.OnParseError;
import com.streamsets.pipeline.lib.parser.DataParser;
import com.streamsets.pipeline.lib.parser.DataParserException;
import com.streamsets.pipeline.lib.parser.DataParserFactory;
import com.streamsets.pipeline.lib.parser.DataParserFactoryBuilder;
import com.streamsets.pipeline.lib.parser.log.LogDataFormatValidator;
import com.streamsets.pipeline.lib.parser.log.RegExConfig;

public class ClusterHdfsSource extends BaseSource implements OffsetCommitter, ErrorListener, ClusterSource {
  private static final Logger LOG = LoggerFactory.getLogger(ClusterHdfsSource.class);
  private String hdfsDirLocation;
  private Configuration hadoopConf;
  private ControlChannel controlChannel;
  private DataChannel dataChannel;
  private Producer producer;
  private Consumer consumer;
  private final DataFormat dataFormat;
  private final int textMaxLineLen;
  private final int jsonMaxObjectLen;
  private final LogMode logMode;
  private final boolean retainOriginalLine;
  private final String customLogFormat;
  private final String regex;
  private final List<RegExConfig> fieldPathsToGroupName;
  private final String grokPatternDefinition;
  private final String grokPattern;
  private final boolean enableLog4jCustomLogFormat;
  private final String log4jCustomLogFormat;
  private LogDataFormatValidator logDataFormatValidator;
  private final int logMaxObjectLen;
  private DataParserFactory parserFactory;
  private boolean produceSingleRecordPerMessage;
  private Map<String, String> hdfsConfigs;
  private boolean kerberosAuth;
  private String kerberosPrincipal;
  private String kerberosKeytab;
  private UserGroupInformation ugi;
  private final boolean recursive;
  private int parallelism;
  private long recordsProduced;


  public ClusterHdfsSource(String hdfsDirLocation, boolean recursive, Map<String, String> hdfsConfigs, DataFormat dataFormat, int textMaxLineLen,
    int jsonMaxObjectLen, LogMode logMode, boolean retainOriginalLine, String customLogFormat, String regex,
    List<RegExConfig> fieldPathsToGroupName, String grokPatternDefinition, String grokPattern,
    boolean enableLog4jCustomLogFormat, String log4jCustomLogFormat, int logMaxObjectLen, boolean produceSingleRecordPerMessage,
    boolean kerberosAuth, String kerberosPrincipal, String kerberosKeytab) {
    controlChannel = new ControlChannel();
    dataChannel = new DataChannel();
    producer = new Producer(controlChannel, dataChannel);
    consumer = new Consumer(controlChannel, dataChannel);
    this.recursive = recursive;
    this.hdfsConfigs = hdfsConfigs;
    this.hdfsDirLocation = hdfsDirLocation;
    this.dataFormat = dataFormat;
    this.textMaxLineLen = textMaxLineLen;
    this.jsonMaxObjectLen = jsonMaxObjectLen;
    this.logMode = logMode;
    this.retainOriginalLine = retainOriginalLine;
    this.customLogFormat = customLogFormat;
    this.regex = regex;
    this.fieldPathsToGroupName = fieldPathsToGroupName;
    this.enableLog4jCustomLogFormat = enableLog4jCustomLogFormat;
    this.grokPatternDefinition = grokPatternDefinition;
    this.grokPattern = grokPattern;
    this.log4jCustomLogFormat = log4jCustomLogFormat;
    this.logMaxObjectLen = logMaxObjectLen;
    this.produceSingleRecordPerMessage = produceSingleRecordPerMessage;
    this.kerberosAuth = kerberosAuth;
    this.kerberosPrincipal = kerberosPrincipal;
    this.kerberosKeytab = kerberosKeytab;
    this.recordsProduced = 0;
  }

  @Override
  public List<ConfigIssue> init() {
    hadoopConf = new Configuration();
    // This is for getting no of splits - no of executors
    hadoopConf.set(FileInputFormat.LIST_STATUS_NUM_THREADS, "5"); // Per Hive-on-Spark
    hadoopConf.set(FileInputFormat.SPLIT_MAXSIZE, String.valueOf(750000000)); // Per Hive-on-Spark
    for (Map.Entry<String, String> config : hdfsConfigs.entrySet()) {
      hadoopConf.set(config.getKey(), config.getValue());
    }
    List<ConfigIssue> issues = super.init();
    if (hdfsDirLocation == null || hdfsDirLocation.isEmpty()) {
      issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_00));
    }
    hadoopConf.set(FileInputFormat.INPUT_DIR, hdfsDirLocation);
    hadoopConf.set(FileInputFormat.INPUT_DIR_RECURSIVE, Boolean.toString(recursive));

    if (hdfsDirLocation.contains("://")) {
      try {
        URI hdfsURI = new URI(hdfsDirLocation);
        if (!hdfsURI.getScheme().equals("hdfs")) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_12,
            hdfsURI.getScheme()));
        }
        String authority = hdfsURI.getAuthority();
        if (authority == null) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_13,
            hdfsDirLocation));
        } else {
          String[] hostPort = authority.split(":");
          if (hostPort.length != 2) {
            issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_14,
              authority));
          }
        }
        hadoopConf.set(CommonConfigurationKeys.FS_DEFAULT_NAME_KEY,
          hdfsURI.getScheme() + "://" + hdfsURI.getAuthority());
      } catch (Exception ex) {
        issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_03,
          hdfsDirLocation, ex.getMessage(), ex));
      }
    } else {
      issues.add(getContext()
        .createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_02, hdfsDirLocation));
    }
    try {
      validateKerberosConfigs(issues);
      if (issues.isEmpty()) {
        FileSystem fs = getFileSystemForInitDestroy();
        Path ph = new Path(hdfsDirLocation);
        if (!fs.exists(ph)) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_10));
        } else if (!fs.getFileStatus(ph).isDirectory()) {
          issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_15,
            hdfsDirLocation));
        } else {
          try {
            Object[] files = fs.listStatus(ph);
            if (files == null || files.length == 0) {
              issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_16,
                                                        hdfsDirLocation));
            }
          } catch (IOException ex) {
            issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_09,
                                                      hdfsDirLocation, ex.getMessage()));
          }
        }
      }
    } catch (IOException ioe) {
      LOG.warn("Error connecting to hadoop filesystem: " + ioe, ioe);
      issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsDirLocation", Errors.HADOOPFS_11,
        hdfsDirLocation));
    }

    switch (dataFormat) {
      case JSON:
        if (jsonMaxObjectLen < 1) {
          issues.add(getContext().createConfigIssue(Groups.JSON.name(), "jsonMaxObjectLen", Errors.HADOOPFS_04));
        }
        break;
      case TEXT:
        if (textMaxLineLen < 1) {
          issues.add(getContext().createConfigIssue(Groups.TEXT.name(), "textMaxLineLen", Errors.HADOOPFS_05));
        }
        break;
      case LOG:
        logDataFormatValidator = new LogDataFormatValidator(logMode, logMaxObjectLen, retainOriginalLine,
                                                            customLogFormat, regex, grokPatternDefinition, grokPattern,
                                                            enableLog4jCustomLogFormat, log4jCustomLogFormat,
                                                            OnParseError.ERROR, 0, Groups.LOG.name(),
                                                            getFieldPathToGroupMap(fieldPathsToGroupName));
        logDataFormatValidator.validateLogFormatConfig(issues, getContext());
        break;
      default:
        issues.add(getContext().createConfigIssue(Groups.LOG.name(), "dataFormat", Errors.HADOOPFS_06, dataFormat));
    }
    validateParserFactoryConfigs(issues);
    return issues;
  }

  @VisibleForTesting
  Configuration getConfiguration() {
    return hadoopConf;
  }

  private FileSystem getFileSystemForInitDestroy() throws IOException {
    try {
      // we need to relogin if the TGT is expiring
      ugi.checkTGTAndReloginFromKeytab();
      return ugi.doAs(new PrivilegedExceptionAction<FileSystem>() {
        @Override
        public FileSystem run() throws Exception {
          return FileSystem.get(new URI(hdfsDirLocation), hadoopConf);
        }
      });
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private void validateKerberosConfigs(List<ConfigIssue> issues) throws IOException {
    if (kerberosAuth) {
      LOG.info("Using Kerberos Authentication");
      hadoopConf.set(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
        UserGroupInformation.AuthenticationMethod.KERBEROS.name());
      UserGroupInformation.setConfiguration(hadoopConf);
      ugi = UserGroupInformation.loginUserFromKeytabAndReturnUGI(kerberosPrincipal, kerberosKeytab);
      if (ugi.getAuthenticationMethod() != UserGroupInformation.AuthenticationMethod.KERBEROS) {
        issues.add(getContext().createConfigIssue(Groups.HADOOP_FS.name(), "hdfsKerberos", Errors.HADOOPFS_00,
          ugi.getAuthenticationMethod(), UserGroupInformation.AuthenticationMethod.KERBEROS));
      }
    } else {
      LOG.info("Using Simple Authentication");
      hadoopConf.set(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
        UserGroupInformation.AuthenticationMethod.SIMPLE.name());
      ugi = UserGroupInformation.getLoginUser();
    }
  }

  private void validateParserFactoryConfigs(List<ConfigIssue> issues) {
    DataParserFactoryBuilder builder = new DataParserFactoryBuilder(getContext(), dataFormat.getParserFormat())
      .setCharset(Charset.defaultCharset());

    // TextInputFormat supports Hadoop Text class which is Standard UTF-8
    builder.setCharset(StandardCharsets.UTF_8);

    switch (dataFormat) {
      case TEXT:
        builder.setMaxDataLen(textMaxLineLen);
        break;
      case JSON:
        builder.setMode(JsonMode.MULTIPLE_OBJECTS);
        builder.setMaxDataLen(jsonMaxObjectLen);
        break;
      case LOG:
        logDataFormatValidator.populateBuilder(builder);
        break;
    }
    parserFactory = builder.build();
  }

  @Override
  public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
    // Ignore the batch size -- TODO why?
    OffsetAndResult<Map.Entry> offsetAndResult = consumer.take();
    if (offsetAndResult == null) {
      LOG.info("Received null batch, returning null");
      return null;
    }
    int count = 0;
    for (Map.Entry message : offsetAndResult.getResult()) {
      count++;
      String messageId = getRecordId();
      List<Record> listRecords = processMessage(messageId, String.valueOf(message.getValue()));
      for (Record record : listRecords) {
        batchMaker.addRecord(record);
      }
    }
    if (count == 0) {
      LOG.info("Received no records, returning null");
      return null;
    }
    return offsetAndResult.getOffset();
  }

  protected List<Record> processMessage(String messageId, String message) throws StageException {
    List<Record> records = new ArrayList<>();
    try (DataParser parser = parserFactory.getParser(messageId, message)) {
      Record record = parser.parse();
      while (record != null) {
        records.add(record);
        record = parser.parse();
      }
    } catch (IOException|DataParserException ex) {
      handleException(messageId, ex);
    }
    if (produceSingleRecordPerMessage) {
      List<Field> list = new ArrayList<>();
      for (Record record : records) {
        list.add(record.get());
      }
      Record record = records.get(0);
      record.set(Field.create(list));
      records.clear();
      records.add(record);
    }
    return records;
  }

  private String getRecordId() {
    return "mr:hdfs";
  }

  @Override
  public void put(List<Map.Entry> batch) throws InterruptedException {
    recordsProduced += batch.size();
    producer.put(new OffsetAndResult<>(String.valueOf(recordsProduced), batch));
  }

  @Override
  public long getRecordsProduced() {
    return recordsProduced;
  }

  @Override
  public boolean inErrorState() {
    return producer.inErrorState() || consumer.inErrorState();
  }

  @Override
  public void errorNotification(Throwable throwable) {
    consumer.error(throwable);
  }

  @Override
  public void commit(String offset) throws StageException {
    consumer.commit(offset);
  }

  @Override
  public void destroy() {
    shutdown();
    super.destroy();
  }

  @Override
  public void shutdown() {
    producer.complete();
  }


  private Map<String, Integer> getFieldPathToGroupMap(List<RegExConfig> fieldPathsToGroupName) {
    if(fieldPathsToGroupName == null) {
      return new HashMap<>();
    }
    Map<String, Integer> fieldPathToGroup = new HashMap<>();
    for(RegExConfig r : fieldPathsToGroupName) {
      fieldPathToGroup.put(r.fieldPath, r.group);
    }
    return fieldPathToGroup;
  }

  private void handleException(String messageId, Exception ex) throws StageException {
    switch (getContext().getOnErrorRecord()) {
      case DISCARD:
        break;
      case TO_ERROR:
        getContext().reportError(Errors.HADOOPFS_08, messageId, ex.getMessage(), ex);
        break;
      case STOP_PIPELINE:
        if (ex instanceof StageException) {
          throw (StageException) ex;
        } else {
          throw new StageException(Errors.HADOOPFS_08, messageId, ex.getMessage(), ex);
        }
      default:
        throw new IllegalStateException(Utils.format("It should never happen. OnError '{}'",
          getContext().getOnErrorRecord(), ex));
    }
  }

  @Override
  public int getParallelism() throws IOException {
    return 0; // not used as MR calculates splits
  }

  @Override
  public String getName() {
    return "hdfs";
  }

  @Override
  public boolean isInBatchMode() {
    // for now directory source requires batch mode
    return true;
  }

  @Override
  public Map<String, String> getConfigsToShip() {
    Map<String, String> configsToShip = new HashMap<String, String>();
    configsToShip.put(FileInputFormat.LIST_STATUS_NUM_THREADS, hadoopConf.get(FileInputFormat.LIST_STATUS_NUM_THREADS));
    configsToShip.put(FileInputFormat.SPLIT_MAXSIZE, hadoopConf.get(FileInputFormat.SPLIT_MAXSIZE));
    configsToShip.put(FileInputFormat.INPUT_DIR_RECURSIVE, hadoopConf.get(FileInputFormat.INPUT_DIR_RECURSIVE));
    return configsToShip;
  }


}

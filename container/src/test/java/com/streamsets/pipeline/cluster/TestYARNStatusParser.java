/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.cluster;

import com.google.common.io.Resources;
import com.streamsets.pipeline.util.MiniSDCSystemProcessImpl;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class TestYARNStatusParser {

  @Test
  public void testValidOutput() throws Exception {
    assertValidOutput("/yarn-status-success.txt", "SUCCEEDED");
    assertValidOutput("/yarn-status-running.txt", "RUNNING");
    assertValidOutput("/yarn-status-failed.txt", "FAILED");
    YARNStatusParser parser = new YARNStatusParser();
    Assert.assertEquals("RUNNING", parser.parseStatus(Arrays.asList(MiniSDCSystemProcessImpl.YARN_STATUS_SUCCESS)));
  }

  private static void assertValidOutput(String name, String output) throws Exception {
    YARNStatusParser parser = new YARNStatusParser();
    Assert.assertEquals(output, parser.parseStatus(readFile(name)));
    ClusterPipelineStatus.valueOf(output);
  }

  @Test(expected = IllegalStateException.class)
  public void testInvalidOutput() throws Exception {
    YARNStatusParser parser = new YARNStatusParser();
    parser.parseStatus(Arrays.<String>asList());
  }

  private static List<String> readFile(String name) throws Exception {
    return Resources.readLines(TestYARNStatusParser.class.getResource(name),
      StandardCharsets.UTF_8);
  }
}

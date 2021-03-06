/**
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.restapi.bean;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Date;

public class PipelineRevInfoJson {

  private final com.streamsets.datacollector.store.PipelineRevInfo pipelineRevInfo;

  public PipelineRevInfoJson(com.streamsets.datacollector.store.PipelineRevInfo pipelineRevInfo) {
    this.pipelineRevInfo = pipelineRevInfo;
  }

  @JsonCreator
  public PipelineRevInfoJson(
    @JsonProperty("date") Date date,
    @JsonProperty("user") String user,
    @JsonProperty("rev") String rev,
    @JsonProperty("tag") String tag,
    @JsonProperty("description") String description,
    @JsonProperty("valid") boolean valid) {
    this.pipelineRevInfo = new com.streamsets.datacollector.store.PipelineRevInfo(date, user, rev, tag, description, valid);
  }

  public Date getDate() {
    return pipelineRevInfo.getDate();
  }

  public String getUser() {
    return pipelineRevInfo.getUser();
  }

  public String getRev() {
    return pipelineRevInfo.getRev();
  }

  public String getTag() {
    return pipelineRevInfo.getTag();
  }

  public String getDescription() {
    return pipelineRevInfo.getDescription();
  }

  public boolean isValid() {
    return pipelineRevInfo.isValid();
  }

  @JsonIgnore
  public com.streamsets.datacollector.store.PipelineRevInfo getPipelineRevInfo() {
    return pipelineRevInfo;
  }
}
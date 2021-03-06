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
package com.streamsets.pipeline.lib.parser.log;

import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.ext.io.OverrunReader;
import com.streamsets.pipeline.lib.parser.DataParserException;
import com.streamsets.pipeline.lib.parser.shaded.org.aicer.grok.util.Grok;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class GrokParser extends LogCharDataParser {

  private final Grok compiledPattern;
  private final String formatName;

  public GrokParser(Stage.Context context,
                    String readerId,
                    OverrunReader reader,
                    long readerOffset,
                    int maxObjectLen,
                    boolean retainOriginalText,
                    int maxStackTraceLines,
                    Grok compiledPattern,
                    String formatName,
                    GenericObjectPool<StringBuilder> currentLineBuilderPool,
                    GenericObjectPool<StringBuilder> previousLineBuilderPool
  ) throws IOException {
    super(context, readerId, reader, readerOffset, maxObjectLen, retainOriginalText, maxStackTraceLines, currentLineBuilderPool, previousLineBuilderPool);
    this.compiledPattern = compiledPattern;
    this.formatName = formatName;
  }

  @Override
  public Map<String, Field> parseLogLine(StringBuilder logLine) throws DataParserException {
    Map<String, String> namedGroupToValuesMap = compiledPattern.extractNamedGroups(logLine.toString());
    if(namedGroupToValuesMap == null) {
      //Did not match
      handleNoMatch(logLine.toString());
    }
    Map<String, Field> map = new LinkedHashMap<>();
    for(Map.Entry<String, String> e : namedGroupToValuesMap.entrySet()) {
      map.put(e.getKey(), Field.create(e.getValue()));
    }
    return map;
  }

  protected void handleNoMatch(String logLine) throws DataParserException {
    throw new DataParserException(Errors.LOG_PARSER_03, logLine, formatName);
  }

}

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
package com.streamsets.pipeline.stage.origin.udptokafka;

import com.streamsets.pipeline.lib.udp.UDPMessageSerializer;
import org.apache.commons.pool2.PooledObject;
import org.junit.Assert;
import org.junit.Test;

public class TestUDPMessageSerializerPooledObjectFactory {

  @Test
  public void testFactory() throws Exception {
    UDPMessageSerializerPooledObjectFactory factory = new UDPMessageSerializerPooledObjectFactory();
    UDPMessageSerializer serializer = factory.create();
    Assert.assertNotNull(serializer);
    PooledObject<UDPMessageSerializer> pooledObject = factory.wrap(serializer);
    Assert.assertEquals(serializer, pooledObject.getObject());
  }

}

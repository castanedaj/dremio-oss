/*
 * Copyright (C) 2017-2018 Dremio Corporation
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
package com.dremio.exec.store;

import java.util.Collection;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.BufferManager;
import org.apache.arrow.vector.SchemaChangeCallBack;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.CallBack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.server.SabotContext;
import com.dremio.sabot.exec.context.BufferManagerImpl;
import com.dremio.sabot.op.scan.OutputMutator;
import com.google.common.collect.Maps;

import io.netty.buffer.ArrowBuf;


/**
 * Used for sampling, etc
 * TODO rename this class, since it used for more than sampling now
 */
public class SampleMutator implements OutputMutator, AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(SampleMutator.class);

  private final Map<String, ValueVector> fieldVectorMap = Maps.newHashMap();
  private final SchemaChangeCallBack callBack = new SchemaChangeCallBack();
  private final VectorContainer container = new VectorContainer();
  private final BufferAllocator allocator;
  private final BufferManager bufferManager;

  /**
   * TODO: Stop using, uses root allocator rather than a specific one.
   */
  @Deprecated
  public SampleMutator(SabotContext context) {
    this(context.getAllocator());
  }

  public SampleMutator(BufferAllocator allocator) {
    this.allocator = allocator;
    this.bufferManager = new BufferManagerImpl(allocator);
  }

  public <T extends ValueVector> T addField(Field field, Class<T> clazz) throws SchemaChangeException {
    ValueVector v = fieldVectorMap.get(field.getName().toLowerCase());
    if (v == null || v.getClass() != clazz) {
      // Field does not exist--add it to the map and the output container.
      v = TypeHelper.getNewVector(field, allocator, callBack);
      if (!clazz.isAssignableFrom(v.getClass())) {
        throw new SchemaChangeException(
                String.format(
                        "The class that was provided, %s, does not correspond to the "
                                + "expected vector type of %s.",
                        clazz.getSimpleName(), v.getClass().getSimpleName()));
      }

      final ValueVector old = fieldVectorMap.put(field.getName().toLowerCase(), v);
      if (old != null) {
        container.replace(old, v);
      } else {
        container.add(v);
      }
      // Added new vectors to the container--mark that the schema has changed.
    }

    return clazz.cast(v);
  }

  @Override
  public ValueVector getVector(String name) {
    return fieldVectorMap.get(name.toLowerCase());
  }

  @Override
  public Collection<ValueVector> getVectors() {
    return fieldVectorMap.values();
  }

  @Override
  public void allocate(int recordCount) {
    container.allocateNew();
  }

  @Override
  public ArrowBuf getManagedBuffer() {
    return bufferManager.getManagedBuffer();
  }

  @Override
  public CallBack getCallBack() {
    return callBack;
  }

  public Map<String,ValueVector> getFieldVectorMap() {
    return fieldVectorMap;
  }

  public VectorContainer getContainer() {
    return container;
  }

  @Override
  public boolean isSchemaChanged() {
    return container.isNewSchema() || callBack.getSchemaChangedAndReset();
  }

  /**
   * Since this OutputMutator is passed by TextRecordReader to get the header out
   * the mutator might not get cleaned up elsewhere. TextRecordReader will call
   * this method to clear any allocations
   */
  public void close() {
    logger.debug("closing mutator");
    for (final ValueVector v : fieldVectorMap.values()) {
      v.clear();
    }
    fieldVectorMap.clear();
    container.clear();
    bufferManager.close();
  }
}

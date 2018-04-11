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
package com.dremio.exec.store.sys.accel;

import java.util.List;

import com.dremio.exec.planner.sql.parser.PartitionDistributionStrategy;
import com.dremio.exec.planner.sql.parser.SqlCreateReflection.NameAndGranularity;

public class LayoutDefinition {

  public static enum Type {AGGREGATE, RAW};

  private final Type type;
  private final List<String> display;
  private final List<NameAndGranularity> dimension;
  private final List<String> measure;
  private final List<String> sort;
  private final List<String> distribution;
  private final List<String> partition;
  private final PartitionDistributionStrategy partitionDistributionStrategy;
  private final String name;

  public LayoutDefinition(String name, Type type, List<String> display, List<NameAndGranularity> dimension, List<String> measure,
                          List<String> sort, List<String> distribution, List<String> partition,
                          PartitionDistributionStrategy partitionDistributionStrategy) {
    super();
    this.name = name;
    this.type = type;
    this.display = display;
    this.dimension = dimension;
    this.measure = measure;
    this.sort = sort;
    this.distribution = distribution;
    this.partition = partition;
    this.partitionDistributionStrategy = partitionDistributionStrategy;
  }

  public String getName() {
    return name;
  }

  public Type getType() {
    return type;
  }


  public List<String> getDisplay() {
    return display;
  }


  public List<NameAndGranularity> getDimension() {
    return dimension;
  }


  public List<String> getMeasure() {
    return measure;
  }


  public List<String> getSort() {
    return sort;
  }


  public List<String> getDistribution() {
    return distribution;
  }


  public List<String> getPartition() {
    return partition;
  }

  public PartitionDistributionStrategy getPartitionDistributionStrategy() {
    return partitionDistributionStrategy;
  }

}

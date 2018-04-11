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
package com.dremio.exec.planner.cost;

public interface RelOptCostFactory extends org.apache.calcite.plan.RelOptCostFactory {

  /**
   * Creates a cost object.
   */
  RelOptCost makeCost(double rowCount, double cpu, double io, double network, double memory);

  /**
   * Creates a cost object.
   */
  RelOptCost makeCost(double rowCount, double cpu, double io, double network);
}

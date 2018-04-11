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
package com.dremio.exec.planner.physical.visitor;

import java.util.Collections;

import org.apache.calcite.rel.RelNode;

import com.dremio.exec.planner.physical.ComplexToJsonPrel;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.ScreenPrel;

public class ComplexToJsonPrelVisitor extends BasePrelVisitor<Prel, Void, RuntimeException> {

  private static final ComplexToJsonPrelVisitor INSTANCE = new ComplexToJsonPrelVisitor();

  public static Prel addComplexToJsonPrel(Prel prel) {
    return prel.accept(INSTANCE, null);
  }

  @Override
  public Prel visitScreen(ScreenPrel prel, Void value) throws RuntimeException {
    return prel.copy(prel.getTraitSet(), Collections.singletonList((RelNode)new ComplexToJsonPrel((Prel)prel.getInput())));
  }

}

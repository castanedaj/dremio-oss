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
package com.dremio.exec.planner.sql.parser.impl;

import java.io.Reader;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.util.SqlVisitor;

import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.parser.CompoundIdentifierConverter;

public class ParserWithCompoundIdConverter extends ParserImpl {

  /**
   * {@link org.apache.calcite.sql.parser.SqlParserImplFactory} implementation for creating parser.
   */

  public static final SqlParserImplFactory FACTORY = new SqlParserImplFactory() {
    @Override
    public SqlAbstractParserImpl getParser(Reader stream) {
      SqlAbstractParserImpl parserImpl = new ParserWithCompoundIdConverter(stream);
      parserImpl.setIdentifierMaxLength(PlannerSettings.DEFAULT_IDENTIFIER_MAX_LENGTH);
      return parserImpl;
    }
  };

  public ParserWithCompoundIdConverter(Reader stream) {
    super(stream);
  }

  protected SqlVisitor<SqlNode> createConverter() {
    return new CompoundIdentifierConverter();
  }

  @Override
  public SqlNode parseSqlExpressionEof() throws Exception {
    SqlNode originalSqlNode = super.parseSqlExpressionEof();
    return originalSqlNode.accept(createConverter());
  }

  @Override
  public SqlNode parseSqlStmtEof() throws Exception {
    SqlNode originalSqlNode = super.parseSqlStmtEof();
    return originalSqlNode.accept(createConverter());
  }
}

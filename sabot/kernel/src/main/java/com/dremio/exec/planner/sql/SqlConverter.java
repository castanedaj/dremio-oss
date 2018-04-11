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
package com.dremio.exec.planner.sql;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.SqlToRelConverter;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.DremioCatalogReader;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.ops.ViewExpansionContext;
import com.dremio.exec.planner.DremioRexBuilder;
import com.dremio.exec.planner.DremioVolcanoPlanner;
import com.dremio.exec.planner.acceleration.substitution.AccelerationAwareSubstitutionProvider;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionProviderFactory;
import com.dremio.exec.planner.cost.DefaultRelMetadataProvider;
import com.dremio.exec.planner.cost.DremioCost;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.SqlValidatorImpl.FlattenOpCounter;
import com.dremio.exec.planner.sql.handlers.RexSubQueryUtils.RelsWithRexSubQueryFlattener;
import com.dremio.exec.planner.types.RelDataTypeSystemImpl;
import com.dremio.exec.server.MaterializationDescriptorProvider;
import com.dremio.exec.server.options.OptionManager;
import com.dremio.sabot.exec.context.FunctionContext;
import com.dremio.sabot.rpc.user.UserSession;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;


/**
 * Class responsible for managing parsing, validation and toRel conversion for sql statements.
 */
public class SqlConverter {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SqlConverter.class);

  public static final RelDataTypeSystem TYPE_SYSTEM = RelDataTypeSystemImpl.REL_DATATYPE_SYSTEM;

  private final JavaTypeFactory typeFactory;
  private final ParserConfig parserConfig;
  private final DremioCatalogReader catalogReader;
  private final PlannerSettings settings;
  private final SqlOperatorTable opTab;
  private final RelOptCostFactory costFactory;
  private final SqlValidatorImpl validator;
  private final boolean isInnerQuery;
  private final FunctionContext functionContext;
  private final FunctionImplementationRegistry functions;
  private final RelOptPlanner planner;
  private final RelOptCluster cluster;
  private final AttemptObserver observer;
  private final int nestingLevel;
  private final AccelerationAwareSubstitutionProvider substitutions;
  private final MaterializationList materializations;
  private final UserSession session;
  private final ViewExpansionContext viewExpansionContext;
  private final FlattenOpCounter flattenCounter;

  public SqlConverter(
      final PlannerSettings settings,
      final SqlOperatorTable operatorTable,
      final FunctionContext functionContext,
      final MaterializationDescriptorProvider materializationProvider,
      final FunctionImplementationRegistry functions,
      final UserSession session,
      final AttemptObserver observer,
      final Catalog catalog,
      final SubstitutionProviderFactory factory
      ) {
    this.nestingLevel = 0;
    this.flattenCounter = new FlattenOpCounter();
    this.observer = observer;
    this.settings = settings;
    this.functionContext = functionContext;
    this.functions = functions;
    this.session = Preconditions.checkNotNull(session, "user session is required");
    this.parserConfig = ParserConfig.newInstance(session, settings);
    this.isInnerQuery = false;
    this.typeFactory = new JavaTypeFactoryImpl(TYPE_SYSTEM);
    this.catalogReader = new DremioCatalogReader(catalog, typeFactory);
    this.opTab = new ChainedSqlOperatorTable(ImmutableList.<SqlOperatorTable>of(operatorTable, this.catalogReader));
    this.costFactory = (settings.useDefaultCosting()) ? null : new DremioCost.Factory();
    this.validator = new SqlValidatorImpl(flattenCounter, opTab, this.catalogReader, typeFactory, DremioSqlConformance.INSTANCE);
    validator.setIdentifierExpansion(true);
    this.materializations = new MaterializationList(this, session, materializationProvider);
    this.substitutions = AccelerationAwareSubstitutionProvider.of(factory.getSubstitutionProvider(materializations, this.settings.options));
    this.planner = new DremioVolcanoPlanner(this);
    this.cluster = RelOptCluster.create(planner, new DremioRexBuilder(typeFactory));
    this.cluster.setMetadataProvider(DefaultRelMetadataProvider.INSTANCE);
    this.viewExpansionContext = new ViewExpansionContext(catalog.getUser());
  }

  SqlConverter(SqlConverter parent, DremioCatalogReader catalog) {
    this.nestingLevel = parent.nestingLevel + 1;
    // since this is level 1 or deeper, we need to use system defaults instead of any overridden edge parser.
    this.parserConfig = parent.parserConfig.cloneWithSystemDefault();
    this.substitutions = parent.substitutions;
    this.functions = parent.functions;
    this.session = parent.session;
    this.functionContext = parent.functionContext;
    this.isInnerQuery = true;
    this.observer = parent.observer;
    this.typeFactory = parent.typeFactory;
    this.costFactory = parent.costFactory;
    this.settings = parent.settings;
    this.flattenCounter = parent.flattenCounter;
    this.cluster = parent.cluster;
    this.catalogReader = catalog;
    this.opTab = parent.opTab;
    this.planner = parent.planner;
    this.materializations = parent.materializations;
    this.validator = new SqlValidatorImpl(parent.flattenCounter, opTab, catalog, typeFactory, DremioSqlConformance.INSTANCE);
    validator.setIdentifierExpansion(true);
    this.viewExpansionContext = parent.viewExpansionContext;
  }


  public SqlNode parse(String sql) {
    try {
      SqlParser parser = SqlParser.create(sql, parserConfig);
      return parser.parseStmt();
    } catch (SqlParseException e) {
      UserException.Builder builder = SqlExceptionHelper.parseError(sql, e);

      builder.message(isInnerQuery ? SqlExceptionHelper.INNER_QUERY_PARSING_ERROR : SqlExceptionHelper.QUERY_PARSING_ERROR);
      throw builder.build(logger);
    }
  }


  public ViewExpansionContext getViewExpansionContext() {
    return viewExpansionContext;
  }

  public UserSession getSession() {
    return session;
  }

  public SqlNode validate(final SqlNode parsedNode) {
    return validator.validate(parsedNode);
  }

  public FunctionImplementationRegistry getFunctionImplementationRegistry() {
    return functions;
  }

  public PlannerSettings getSettings() {
    return settings;
  }

  public RelDataType getOutputType(SqlNode validatedNode) {
    return validator.getValidatedNodeType(validatedNode);
  }

  public JavaTypeFactory getTypeFactory() {
    return typeFactory;
  }

  public SqlOperatorTable getOpTab() {
    return opTab;
  }

  public RelOptCostFactory getCostFactory() {
    return costFactory;
  }

  public FunctionContext getFunctionContext() {
    return functionContext;
  }

  public Catalog getCatalog() {
    return catalogReader.getCatalog();
  }

  public DremioCatalogReader getCatalogReader() {
    return catalogReader;
  }

  public SqlParser.Config getParserConfig() {
    return parserConfig;
  }

  public AttemptObserver getObserver() {
    return observer;
  }

  public MaterializationList getMaterializations() {
    return materializations;
  }

  public int getNestingLevel() {
    return nestingLevel;
  }

  public RelOptCluster getCluster() {
    return cluster;
  }

  public AccelerationAwareSubstitutionProvider getSubstitutionProvider() {
    return substitutions;
  }

  /**
   * Returns a rel root that defers materialization of scans via {@link com.dremio.exec.planner.logical.ConvertibleScan}
   *
   * Used for serialization.
   */
  public RelRoot toConvertibleRelRoot(final SqlNode validatedNode, boolean expand) {

    final OptionManager o = settings.getOptions();
    final long inSubQueryThreshold =  o.getOption(ExecConstants.FAST_OR_ENABLE) ? o.getOption(ExecConstants.FAST_OR_MAX_THRESHOLD) : settings.getOptions().getOption(ExecConstants.PLANNER_IN_SUBQUERY_THRESHOLD);
    final SqlToRelConverter.Config config = SqlToRelConverter.configBuilder()
      .withInSubQueryThreshold((int) inSubQueryThreshold)
      .withTrimUnusedFields(false)
      .withConvertTableAccess(false)
      .withExpand(expand)
      .build();
    final SqlToRelConverter sqlToRelConverter = new DremioSqlToRelConverter(this, validator, ConvertletTable.INSTANCE, config);
    // Previously we had "top" = !innerQuery, but calcite only adds project if it is not a top query.
    final RelRoot rel = sqlToRelConverter.convertQuery(validatedNode, false /* needs validate */, false /* top */);
    final RelNode rel2 = sqlToRelConverter.flattenTypes(rel.rel, true);
    final RelNode rel3;
    if (expand) {
      rel3 = rel2;
    } else {
      // Then we did not expand all the subqueries, so go and flatten the subqueries as well.
      rel3 = rel2.accept(new RelsWithRexSubQueryFlattener(sqlToRelConverter));
    }
    final RelNode rel4 = RelDecorrelator.decorrelateQuery(rel3);

    if (logger.isDebugEnabled()) {
      logger.debug("ConvertQuery with expand = {}:\n{}", expand, RelOptUtil.toString(rel4, SqlExplainLevel.ALL_ATTRIBUTES));
    }
    return RelRoot.of(rel4, rel.kind);
  }

  /**
   *
   * @param sql
   *          the SQL sent to the server
   * @param pos
   *          the position of the error
   * @return The sql with a ^ character under the error
   */
  static String formatSQLParsingError(String sql, SqlParserPos pos) {
    StringBuilder sb = new StringBuilder();
    String[] lines = sql.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      sb.append(line).append("\n");
      if (i == (pos.getLineNum() - 1)) {
        for (int j = 0; j < pos.getColumnNum() - 1; j++) {
          sb.append(" ");
        }
        sb.append("^\n");
      }
    }
    return sb.toString();
  }

}

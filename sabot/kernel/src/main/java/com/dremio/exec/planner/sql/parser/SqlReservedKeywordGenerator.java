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
package com.dremio.exec.planner.sql.parser;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParser;

import com.dremio.exec.planner.sql.ParserConfig;

/**
 * Helper class to generate a file with list of SQL reserved keywords.
 */
public class SqlReservedKeywordGenerator {
  private static final String RESERVED_KEYWORD_FILE_NAME = "sql-reserved-keywords.txt";

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: java {cp} " + SqlReservedKeywordGenerator.class.getName() +
          " path/where/to/write/the/file");
    }

    final File outputFile = new File(args[0], RESERVED_KEYWORD_FILE_NAME);
    System.out.println("Writing reserved SQL keywords to file: " + outputFile.getAbsolutePath());

    try(PrintWriter outFile = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outputFile), UTF_8))) {
      outFile.printf("# AUTO-GENERATED LIST OF SQL RESERVED KEYWORDS (generated by %s)",
          SqlReservedKeywordGenerator.class.getName());
      outFile.println();

      final SqlAbstractParserImpl.Metadata metadata = SqlParser.create("", new ParserConfig(Quoting.DOUBLE_QUOTE, 256)).getMetadata();
      for (String s : metadata.getTokens()) {
        if (metadata.isKeyword(s) && metadata.isReservedWord(s)) {
          outFile.println(s);
        }
      }
    }
  }
}

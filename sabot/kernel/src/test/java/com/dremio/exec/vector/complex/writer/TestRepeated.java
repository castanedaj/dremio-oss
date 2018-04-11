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
package com.dremio.exec.vector.complex.writer;

import java.io.ByteArrayOutputStream;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocatorFactory;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.complex.NullableMapVector;
import org.apache.arrow.vector.complex.impl.ComplexWriterImpl;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.complex.writer.BaseWriter.ListWriter;
import org.apache.arrow.vector.complex.writer.BaseWriter.MapWriter;
import org.apache.arrow.vector.complex.writer.IntWriter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.ExecTest;
import com.dremio.exec.vector.complex.fn.JsonWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Charsets;

public class TestRepeated extends ExecTest {
  // private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TestRepeated.class);

  private static BufferAllocator allocator;

  @BeforeClass
  public static void setupAllocator() {
    allocator = RootAllocatorFactory.newRoot(DEFAULT_SABOT_CONFIG);
  }

  @AfterClass
  public static void destroyAllocator() {
    AutoCloseables.closeNoChecked(allocator);
  }
//
//  @Test
//  public void repeatedMap() {
//
//    /**
//     * We're going to try to create an object that looks like:
//     *
//     *  {
//     *    a: [
//     *      {x: 1, y: 2}
//     *      {x: 2, y: 1}
//     *    ]
//     *  }
//     *
//     */
//    MapVector v = new MapVector("", allocator);
//    ComplexWriter writer = new ComplexWriterImpl("col", v);
//
//    MapWriter map = writer.rootAsMap();
//
//    map.start();
//    ListWriter list = map.list("a");
//    MapWriter inner = list.map();
//
//    IntHolder holder = new IntHolder();
//    IntWriter xCol = inner.integer("x");
//    IntWriter yCol = inner.integer("y");
//
//    inner.start();
//
//    holder.value = 1;
//    xCol.write(holder);
//    holder.value = 2;
//    yCol.write(holder);
//
//    inner.end();
//
//    inner.start();
//
//    holder.value = 2;
//    xCol.write(holder);
//    holder.value = 1;
//    yCol.write(holder);
//
//    inner.end();
//
//    IntWriter numCol = map.integer("nums");
//    holder.value = 14;
//    numCol.write(holder);
//
//    map.end();
//
//
//    assertTrue(writer.ok());
//
//    System.out.println(v.getAccessor().getObject(0));
//
//  }

  @Test
  public void listOfList() throws Exception {
    // TODO ARROW-308 causes col.a to contain a single inner list instead of 2
    /**
     * We're going to try to create 2 objects that looks like:
     *
     *  {
     *    "col" : {
     *      "a" : [ [ 1, 2, 3], [4, 5 ] ],
     *      "nums" : 14,
     *      "b" : [ {
     *        "c" : 1
     *      }, {
     *        "c" : 2,
     *        "x" : 15
     *      } ]
     *    }
     *  }
     *
     *  {
     *    "col" : {
     *      "a" : [ [ -1, -2, -3], [-4, -5 ] ],
     *      "nums" : -28,
     *      "b" : [ {
     *        "c" : -1
     *      }, {
     *        "c" : -2,
     *        "x" : -30
     *      } ]
     *    }
     *  }
     */

    final MapVector mapVector = new MapVector("", allocator, null);
    final ComplexWriterImpl writer = new ComplexWriterImpl("col", mapVector);
    final MapWriter map = writer.rootAsMap();

    {
      map.start();

      final ListWriter list = map.list("a");
      list.startList();

      final ListWriter innerList = list.list();
      final IntWriter innerInt = innerList.integer();

      innerList.startList();
      innerInt.writeInt(1);
      innerInt.writeInt(2);
      innerInt.writeInt(3);
      innerList.endList();

      innerList.startList();
      innerInt.writeInt(4);
      innerInt.writeInt(5);
      innerList.endList();

      list.endList();

      map.integer("nums").writeInt(14);

      final MapWriter repeatedMap = map.list("b").map();
      repeatedMap.start();
      repeatedMap.integer("c").writeInt(1);
      repeatedMap.end();

      repeatedMap.start();
      repeatedMap.integer("c").writeInt(2);
      repeatedMap.bigInt("x").writeBigInt(15);
      repeatedMap.end();

      map.end();
    }

    writer.setPosition(1);
    {
      map.start();

      final ListWriter list = map.list("a");
      list.startList();

      final ListWriter innerList = list.list();
      final IntWriter innerInt = innerList.integer();

      innerList.startList();
      innerInt.writeInt(-1);
      innerInt.writeInt(-2);
      innerInt.writeInt(-3);
      innerList.endList();

      innerList.startList();
      innerInt.writeInt(-4);
      innerInt.writeInt(-5);
      innerList.endList();

      list.endList();

      map.integer("nums").writeInt(-28);

      map.list("b").startList();
      final MapWriter repeatedMap = map.list("b").map();
      repeatedMap.start();
      repeatedMap.integer("c").writeInt(-1);
      repeatedMap.end();

      repeatedMap.start();
      repeatedMap.integer("c").writeInt(-2);
      repeatedMap.bigInt("x").writeBigInt(-30);
      repeatedMap.end();
      map.list("b").endList();

      map.end();
    }
    writer.setValueCount(2);

    final ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();

    System.out.println("Map of Object[0]: " + ow.writeValueAsString(mapVector.getObject(0)));
    System.out.println("Map of Object[1]: " + ow.writeValueAsString(mapVector.getObject(1)));

    final ByteArrayOutputStream stream = new ByteArrayOutputStream();
    final JsonWriter jsonWriter = new JsonWriter(stream, true, true);
    final FieldReader reader = mapVector.getChild("col", NullableMapVector.class).getReader();
    reader.setPosition(0);
    jsonWriter.write(reader);
    reader.setPosition(1);
    jsonWriter.write(reader);
    System.out.print("Json Read: ");
    System.out.println(new String(stream.toByteArray(), Charsets.UTF_8));

    writer.close();
  }
}

/*
 * Copyright © 2018 Andrew Rice (acr31@cam.ac.uk)
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

package uk.ac.cam.acr31.features.javac.graph;

import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.apache.commons.text.StringEscapeUtils;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureEdge;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureEdge.EdgeType;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureNode;

public class DotOutput {

  public static void writeToDot(File outputFile, FeatureGraph graph, boolean verboseDot) {
    try (FileWriter w = new FileWriter(outputFile)) {
      w.write(createDot(graph, verboseDot));

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static String createDot(FeatureGraph graph, boolean verboseDot) {
    StringWriter result = new StringWriter();
    try (PrintWriter w = new PrintWriter(result)) {
      w.println("digraph {");
      w.println(" rankdir=LR;");

      Set<FeatureNode> nodeSet = ImmutableSet.of(graph.root());
      while (!nodeSet.isEmpty()) {
        writeSubgraph(w, nodeSet, "same", verboseDot);
        nodeSet = getAstChildren(nodeSet, graph);
      }

      Set<FeatureNode> commentSet = graph.comments();
      writeSubgraph(w, commentSet, null, verboseDot);

      Set<FeatureNode> symbolSet = graph.symbols();
      writeSubgraph(w, symbolSet, null, verboseDot);

      Set<FeatureNode> tokenSet = graph.tokens();
      writeSubgraph(w, tokenSet, "max", verboseDot);

      for (FeatureEdge edge : graph.edges()) {
        w.println(dotEdge(edge));
      }

      w.println("}");
    }
    return result.toString();
  }

  private static String dotNode(FeatureNode node, boolean verbose) {
    if (verbose) {
      return String.format(
          "%d [ label=\"%d:%s\\n%s\\nPos:%d - %d\"];\n",
          node.getId(),
          node.getId(),
          node.getType(),
          escapeContents(node),
          node.getStartPosition(),
          node.getEndPosition());
    } else {
      return String.format("%d [ label=\"%s\"];\n", node.getId(), escapeContents(node));
    }
  }

  private static String escapeContents(FeatureNode node) {
    if (node.getContents().isEmpty()) {
      return node.getType().toString();
    }
    return StringEscapeUtils.escapeJava(node.getContents());
  }

  private static String dotEdge(FeatureEdge edge) {
    EdgeType edgeType = edge.getType();
    String ports;
    switch (edgeType) {
      case NEXT_TOKEN:
        ports = "headport=n, tailport=s, weight=1000";
        break;
      case AST_CHILD:
        ports = "headport=w, tailport=e";
        break;
      case LAST_WRITE:
        ports = "headport=e, tailport=e, color=red, weight=0";
        break;
      case LAST_USE:
        ports = "headport=e, tailport=e, color=green, weight=0";
        break;
      case COMPUTED_FROM:
        ports = "headport=e, tailport=e, color=purple, weight=0";
        break;
      case LAST_LEXICAL_USE:
        ports = "headport=w, tailport=w, color=orange, weight=0";
        break;
      case RETURNS_TO:
        ports = "headport=e, tailport=w, color=blue, weight=0";
        break;
      case FORMAL_ARG_NAME:
        ports = "headport=w, tailport=w, color=yellow, weight=0";
        break;
      case GUARDED_BY:
        ports = "headport=e, tailport=w, color=pink, weight=0";
        break;
      case GUARDED_BY_NEGATION:
        ports = "headport=e, tailport=w, color=pink, weight=0, style=dashed";
        break;
      default:
        ports = "";
    }
    return String.format("%d -> %d [ %s];\n", edge.getSourceId(), edge.getDestinationId(), ports);
  }

  private static void writeSubgraph(
      PrintWriter w, Set<FeatureNode> nodeSet, String rank, boolean verbose) {
    w.println(" subgraph {");
    if (rank != null) {
      w.println(String.format("  rank=%s;", rank));
    }
    nodeSet.forEach(n -> w.println(dotNode(n, verbose)));
    w.println(" }");
  }

  private static Set<FeatureNode> getAstChildren(Set<FeatureNode> nodeSet, FeatureGraph graph) {
    ImmutableSet.Builder<FeatureNode> result = ImmutableSet.builder();
    for (FeatureNode node : nodeSet) {
      graph.successors(node, EdgeType.AST_CHILD).forEach(result::add);
    }
    return result.build();
  }
}

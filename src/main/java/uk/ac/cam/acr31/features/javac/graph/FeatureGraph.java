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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.graph.MutableNetwork;
import com.google.common.graph.NetworkBuilder;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureEdge;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureEdge.EdgeType;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureNode;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureNode.NodeType;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.Graph;

public class FeatureGraph {

  private final String sourceFileName;
  private final MutableNetwork<FeatureNode, FeatureEdge> graph;
  private final Map<Tree, FeatureNode> nodeMap;
  private int nodeIdCounter = 0;
  private final EndPosTable endPosTable;
  private final LineMap lineMap;

  public FeatureGraph(String sourceFileName, EndPosTable endPosTable, LineMap lineMap) {
    this.sourceFileName = sourceFileName;
    this.graph = NetworkBuilder.directed().allowsSelfLoops(true).allowsParallelEdges(true).build();
    this.nodeMap = new IdentityHashMap<>();
    this.endPosTable = endPosTable;
    this.lineMap = lineMap;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public FeatureNode getFeatureNode(Tree tree) {
    return nodeMap.get(tree);
  }

  public void replaceNodeInNodeMap(FeatureNode original, FeatureNode replacement) {
    Tree tree =
        nodeMap
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue().equals(original))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
    nodeMap.put(tree, replacement);
  }

  public FeatureNode createFeatureNode(NodeType nodeType, String contents, Tree tree) {
    // If your code says: String a = "a", b = "b", then javac synths up some extra ast nodes along
    // the lines of String a = "a"; String b = "b";  some of the extra nodes will be clones, some
    // (leaves) will just be the same node reused.  In this case we will try to create a node twice
    // when we visit the reused node for the second time.
    if (nodeMap.containsKey(tree)) {
      return nodeMap.get(tree);
    } else {
      int startPosition = ((JCTree) tree).getStartPosition();
      int endPosition = ((JCTree) tree).getEndPosition(endPosTable);
      FeatureNode result = createFeatureNode(nodeType, contents, startPosition, endPosition);
      nodeMap.put(tree, result);
      return result;
    }
  }

  public FeatureNode createFeatureNode(
      NodeType nodeType, String contents, int startPosition, int endPosition) {
    int startLine = (int) lineMap.getLineNumber(startPosition);
    int endLine = (int) lineMap.getLineNumber(endPosition);
    return FeatureNode.newBuilder()
        .setId(nodeIdCounter++)
        .setType(nodeType)
        .setContents(contents)
        .setStartPosition(startPosition)
        .setEndPosition(endPosition)
        .setStartLineNumber(startLine)
        .setEndLineNumber(endLine)
        .build();
  }

  public Set<FeatureNode> nodes() {
    // returns an unmodifiable set
    return graph.nodes();
  }

  private Set<FeatureNode> nodes(NodeType... nodeTypes) {
    ImmutableList<NodeType> nodes = ImmutableList.copyOf(nodeTypes);
    return graph
        .nodes()
        .stream()
        .filter(n -> nodes.contains(n.getType()))
        .collect(toImmutableSet());
  }

  public FeatureNode root() {
    return Iterables.getOnlyElement(nodes(NodeType.AST_ROOT));
  }

  public Set<FeatureNode> tokens() {
    return nodes(NodeType.TOKEN, NodeType.IDENTIFIER_TOKEN);
  }

  public Set<FeatureNode> astNodes() {
    return nodes(NodeType.AST_ELEMENT);
  }

  public Set<FeatureNode> comments() {
    return nodes(NodeType.COMMENT_BLOCK, NodeType.COMMENT_JAVADOC, NodeType.COMMENT_LINE);
  }

  public Set<FeatureNode> symbols() {
    return nodes(NodeType.SYMBOL);
  }

  public Set<FeatureEdge> edges() {
    // returns an unmodifiable set
    return graph.edges();
  }

  public Set<FeatureEdge> edges(EdgeType edgeType) {
    return graph
        .edges()
        .stream()
        .filter(e -> e.getType().equals(edgeType))
        .collect(toImmutableSet());
  }

  public Set<FeatureEdge> edges(FeatureNode node) {
    return graph.incidentEdges(node);
  }

  public Set<FeatureEdge> edges(FeatureNode source, FeatureNode destination) {
    // returns an unmodifiable set
    return graph.edgesConnecting(source, destination);
  }

  public void removeNode(FeatureNode node) {
    graph.removeNode(node);
  }

  public Set<FeatureNode> successors(FeatureNode node) {
    // returns an unmodifiable set
    return graph.successors(node);
  }

  public Set<FeatureNode> successors(FeatureNode node, EdgeType... edgeTypes) {
    ImmutableList<EdgeType> edgeTypeList = ImmutableList.copyOf(edgeTypes);
    return graph
        .successors(node)
        .stream()
        .filter(
            n ->
                graph
                    .edgesConnecting(node, n)
                    .stream()
                    .anyMatch(e -> edgeTypeList.contains(e.getType())))
        .collect(toImmutableSet());
  }

  public FeatureNode toIdentifierNode(FeatureNode node) {
    if (node.getType().equals(NodeType.IDENTIFIER_TOKEN)) {
      return node;
    }

    return successors(node, EdgeType.ASSOCIATED_TOKEN)
        .stream()
        .filter(n -> n.getType().equals(NodeType.IDENTIFIER_TOKEN))
        .findAny()
        .orElseThrow();
  }

  public Set<FeatureNode> predecessors(FeatureNode node) {
    return graph.predecessors(node);
  }

  public Set<FeatureNode> predecessors(FeatureNode node, EdgeType... edgeTypes) {
    ImmutableList<EdgeType> edgeTypeList = ImmutableList.copyOf(edgeTypes);
    return graph
        .predecessors(node)
        .stream()
        .filter(
            n ->
                graph
                    .edgesConnecting(n, node)
                    .stream()
                    .anyMatch(e -> edgeTypeList.contains(e.getType())))
        .collect(toImmutableSet());
  }

  /** Remove all ast nodes that have no successors. */
  public void pruneAstNodes() {
    //noinspection StatementWithEmptyBody
    while (pruneLeavesOnce()) {
      // do nothing
    }
  }

  private boolean pruneLeavesOnce() {
    ImmutableSet<FeatureNode> toRemove =
        graph
            .nodes()
            .stream()
            .filter(
                n ->
                    n.getType().equals(NodeType.AST_ELEMENT)
                        || n.getType().equals(NodeType.SYNTHETIC_AST_ELEMENT))
            .filter(n -> graph.successors(n).isEmpty())
            .collect(toImmutableSet());
    toRemove.forEach(graph::removeNode);
    return !toRemove.isEmpty();
  }

  public void addEdge(FeatureNode source, FeatureNode dest, EdgeType type) {
    graph.addEdge(
        source,
        dest,
        FeatureEdge.newBuilder()
            .setSourceId(source.getId())
            .setDestinationId(dest.getId())
            .setType(type)
            .build());
  }

  public void removeEdge(FeatureEdge edge) {
    graph.removeEdge(edge);
  }

  Graph toProtobuf() {
    return Graph.newBuilder()
        .setSourceFile(sourceFileName)
        .addAllNode(nodes())
        .addAllEdge(edges())
        .build();
  }

  public Set<FeatureNode> findNode(int start, int end) {
    return nodes()
        .stream()
        .filter(node -> node.getStartPosition() == start)
        .filter(node -> node.getEndPosition() == end)
        .collect(toImmutableSet());
  }
}

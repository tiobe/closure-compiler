/*
 * Copyright 2017 The Closure Compiler Authors.
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

package com.google.javascript.jscomp;

import static com.google.common.truth.Truth.assertThat;
import static com.google.javascript.jscomp.CompilerOptions.LanguageMode.ECMASCRIPT_NEXT;
import static com.google.javascript.jscomp.testing.NodeSubject.assertNode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.javascript.jscomp.CrossModuleReferenceCollector.TopLevelStatement;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.List;

public final class CrossModuleReferenceCollectorTest extends CompilerTestCase {
  private CrossModuleReferenceCollector testedCollector;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    setLanguage(ECMASCRIPT_NEXT, ECMASCRIPT_NEXT);
  }

  @Override
  protected int getNumRepetitions() {
    // Default behavior for CompilerTestCase.test*() methods is to do the whole test twice,
    // because passes that modify the AST need to be idempotent.
    // Since CrossModuleReferenceCollector() just gathers information, it doesn't make sense to
    // run it twice, and doing so just complicates debugging test cases.
    return 1;
  }

  @Override
  protected CompilerPass getProcessor(final Compiler compiler) {
    ScopeCreator scopeCreator = new Es6SyntacticScopeCreator(compiler);
    testedCollector = new CrossModuleReferenceCollector(
        compiler,
        scopeCreator);
    return testedCollector;
  }

  public void testVarInBlock() {
    testSame(LINE_JOINER.join(
            "  if (true) {",
            "    var y = x;",
            "    y;",
            "    y;",
            "  }"));
    ImmutableMap<String, Var> globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    assertThat(globalVariableNamesMap).containsKey("y");
    Var yVar = globalVariableNamesMap.get("y");
    ReferenceCollection yRefs = testedCollector.getReferences(yVar);
    assertThat(yRefs.isAssignedOnceInLifetime()).isTrue();
    assertThat(yRefs.isWellDefined()).isTrue();
  }

  public void testVarInLoopNotAssignedOnlyOnceInLifetime() {
    testSame("var x; while (true) { x = 0; }");
    ImmutableMap<String, Var> globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    Var xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    ReferenceCollection xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.isAssignedOnceInLifetime()).isFalse();

    testSame("let x; while (true) { x = 0; }");
    globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.isAssignedOnceInLifetime()).isFalse();
  }

  /**
   * Although there is only one assignment to x in the code, it's in a function which could be
   * called multiple times, so {@code isAssignedOnceInLifetime()} returns false.
   */
  public void testVarInFunctionNotAssignedOnlyOnceInLifetime() {
    testSame("var x; function f() { x = 0; }");
    ImmutableMap<String, Var> globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    Var xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    ReferenceCollection xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.isAssignedOnceInLifetime()).isFalse();

    testSame("let x; function f() { x = 0; }");
    globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.isAssignedOnceInLifetime()).isFalse();
  }

  public void testVarAssignedOnceInLifetime1() {
    testSame("var x = 0;");
    ImmutableMap<String, Var> globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    Var xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    ReferenceCollection xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.isAssignedOnceInLifetime()).isTrue();

    testSame("let x = 0;");
    globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.isAssignedOnceInLifetime()).isTrue();
  }

  public void testVarAssignedOnceInLifetime2() {
    testSame("{ var x = 0; }");
    ImmutableMap<String, Var> globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    Var xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    ReferenceCollection xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.isAssignedOnceInLifetime()).isTrue();
  }

  public void testBasicBlocks() {
    testSame(LINE_JOINER.join(
            "var x = 0;",
            "switch (x) {",
            "  case 0:",
            "    x;",
            "}"));
    ImmutableMap<String, Var> globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    Var xVar = globalVariableNamesMap.get("x");
    assertThat(globalVariableNamesMap).containsKey("x");
    ReferenceCollection xRefs = testedCollector.getReferences(xVar);
    assertThat(xRefs.references).hasSize(3);
    assertNode(xRefs.references.get(0).getBasicBlock().getRoot()).hasType(Token.ROOT);
    assertNode(xRefs.references.get(1).getBasicBlock().getRoot()).hasType(Token.ROOT);
    assertNode(xRefs.references.get(2).getBasicBlock().getRoot()).hasType(Token.CASE);
  }

  public void testTopLevelStatements() {
    testSame(LINE_JOINER.join(
        "var x = 1;",
        "const y = x;",
        "let z = x - y;",
        "function f(x, y) {",   // only f and z globals referenced
        "  return x + y + z;",
        "}"));

    // Pull out all the references for comparison.
    ImmutableMap<String, Var> globalVariableNamesMap = testedCollector.getGlobalVariableNamesMap();
    ImmutableList<Reference> xReferences =
        ImmutableList.copyOf(testedCollector.getReferences(globalVariableNamesMap.get("x")));
    ImmutableList<Reference> yReferences =
        ImmutableList.copyOf(testedCollector.getReferences(globalVariableNamesMap.get("y")));
    ImmutableList<Reference> zReferences =
        ImmutableList.copyOf(testedCollector.getReferences(globalVariableNamesMap.get("z")));
    ImmutableList<Reference> fReferences =
        ImmutableList.copyOf(testedCollector.getReferences(globalVariableNamesMap.get("f")));

    // Make sure the statements have the references we expect.
    List<TopLevelStatement> topLevelStatements = testedCollector.getTopLevelStatements();
    assertThat(topLevelStatements).hasSize(4);
    // var x = 1;
    TopLevelStatement xEquals1 = topLevelStatements.get(0);
    assertThat(xEquals1.getOriginalOrder()).isEqualTo(0);
    assertThat(xEquals1.getDeclaredNameReference()).isEqualTo(xReferences.get(0));
    assertThat(xEquals1.getNonDeclarationReferences()).isEmpty();
    // const y = x;
    TopLevelStatement yEqualsX = topLevelStatements.get(1);
    assertThat(yEqualsX.getOriginalOrder()).isEqualTo(1);
    assertThat(yEqualsX.getNonDeclarationReferences())
        .containsExactly(yReferences.get(0), xReferences.get(1));
    // let z = x - y;
    TopLevelStatement zEqualsXMinusY = topLevelStatements.get(2);
    assertThat(zEqualsXMinusY.getOriginalOrder()).isEqualTo(2);
    assertThat(zEqualsXMinusY.getNonDeclarationReferences())
        .containsExactly(zReferences.get(0), xReferences.get(2), yReferences.get(1));
    // function f(x, y) { return x + y + z; }
    TopLevelStatement functionDeclaration = topLevelStatements.get(3);
    assertThat(functionDeclaration.getOriginalOrder()).isEqualTo(3);
    assertThat(functionDeclaration.getDeclaredNameReference()).isEqualTo(fReferences.get(0));
    assertThat(functionDeclaration.getNonDeclarationReferences())
        .containsExactly(zReferences.get(1));
  }

  public void testVarDeclarationStatement() {
    testSame("var x = 1;");

    List<TopLevelStatement> statements = testedCollector.getTopLevelStatements();
    ReferenceCollection xRefs = getReferencesForName("x", testedCollector);
    assertThat(statements).hasSize(1);
    TopLevelStatement varStatement = statements.get(0);
    Reference declaredNameReference = varStatement.getDeclaredNameReference();
    assertThat(declaredNameReference).isNotNull();
    assertThat(declaredNameReference).isEqualTo(xRefs.references.get(0));
    assertThat(varStatement.getNonDeclarationReferences()).isEmpty();
    Node valueNode = varStatement.getDeclaredValueNode();
    assertThat(valueNode).isNotNull();
    assertThat(valueNode.getDouble()).isEqualTo(1.0);
  }

  public void testFunctionDeclarationStatement() {
    testSame("function x() {}");

    List<TopLevelStatement> statements = testedCollector.getTopLevelStatements();
    assertThat(statements).hasSize(1);
    ReferenceCollection xRefs = getReferencesForName("x", testedCollector);

    TopLevelStatement functionDeclaration = statements.get(0);
    Reference declaredNameReference = functionDeclaration.getDeclaredNameReference();
    assertThat(declaredNameReference).isNotNull();
    assertThat(declaredNameReference).isEqualTo(xRefs.references.get(0));
    assertThat(functionDeclaration.getNonDeclarationReferences()).isEmpty();
  }

  public void testVariableAssignmentStatement() {
    testSame("var x; x = 1;");

    List<TopLevelStatement> statements = testedCollector.getTopLevelStatements();
    assertThat(statements).hasSize(2);
    ReferenceCollection xRefs = getReferencesForName("x", testedCollector);

    TopLevelStatement assignmentStatement = statements.get(1);
    Reference declaredNameReference = assignmentStatement.getDeclaredNameReference();
    assertThat(declaredNameReference).isNotNull();
    assertThat(declaredNameReference).isEqualTo(xRefs.references.get(1));
    assertThat(assignmentStatement.getNonDeclarationReferences()).isEmpty();
    Node valueNode = assignmentStatement.getDeclaredValueNode();
    assertThat(valueNode).isNotNull();
    assertThat(valueNode.getDouble()).isEqualTo(1.0);
  }

  public void testPropertyAssignmentStatement() {
    testSame("var x = {}; x.prop = 1;");

    List<TopLevelStatement> statements = testedCollector.getTopLevelStatements();
    assertThat(statements).hasSize(2);
    ReferenceCollection xRefs = getReferencesForName("x", testedCollector);

    TopLevelStatement assignmentStatement = statements.get(1);
    Reference declaredNameReference = assignmentStatement.getDeclaredNameReference();
    assertThat(declaredNameReference).isNotNull();
    assertThat(declaredNameReference).isEqualTo(xRefs.references.get(1));
    assertThat(assignmentStatement.getNonDeclarationReferences()).isEmpty();
    Node valueNode = assignmentStatement.getDeclaredValueNode();
    assertThat(valueNode).isNotNull();
    assertThat(valueNode.getDouble()).isEqualTo(1.0);
  }

  public void testGoogInheritsIsMovableDeclaration() {
    testSame("function A() {} function B() {} goog.inherits(B, A);");

    List<TopLevelStatement> statements = testedCollector.getTopLevelStatements();
    assertThat(statements).hasSize(3);

    ReferenceCollection refsToA = getReferencesForName("A", testedCollector);
    ReferenceCollection refsToB = getReferencesForName("B", testedCollector);

    TopLevelStatement inheritsStatement = statements.get(2);
    Reference declaredNameReference = inheritsStatement.getDeclaredNameReference();
    assertThat(declaredNameReference).isNotNull();
    assertThat(declaredNameReference).isEqualTo(refsToB.references.get(1));
    assertThat(inheritsStatement.getNonDeclarationReferences())
        .containsExactly(refsToA.references.get(1));
    // inherits statements are always movable
    assertThat(inheritsStatement.isMovableDeclaration()).isTrue();
  }

  // TODO: add test cases for isMovableDeclarationStatement()
  public void testFunctionDeclarationOrAssignmentIsMovable() {
    testSame("function f() {}");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isTrue();
    testSame("var f = function() {};");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isTrue();
  }

  public void testLiteralValueIsMovable() {
    testSame("var f = 1;");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isTrue();
  }

  public void testFunctionCallsAreNotMovableExceptForMethodStubs() {
    testSame(LINE_JOINER.join(
        "function Foo() {}",
        "Foo.prototype.stub = JSCompiler_stubMethod(x);",
        "Foo.prototype.unstub = JSCompiler_unstubMethod(x);",
        "Foo.prototype.other = other();"));
    List<TopLevelStatement> statements = testedCollector.getTopLevelStatements();
    assertThat(statements.get(1).isMovableDeclaration()).isTrue();
    assertThat(statements.get(2).isMovableDeclaration()).isFalse();
    assertThat(statements.get(3).isMovableDeclaration()).isFalse();
  }

  public void testUnknownNameValueIsImmovable() {
    testSame("var a = unknownName;");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isFalse();
  }

  public void testWellDefinedNameValueIsMovable() {
    testSame("var wellDefined = 1; var other = wellDefined;");
    assertThat(testedCollector.getTopLevelStatements().get(1).isMovableDeclaration()).isTrue();
  }

  public void testUninitializedNameValueIsNotMovable() {
    testSame("var value; var other = value;");
    assertThat(testedCollector.getTopLevelStatements().get(1).isMovableDeclaration()).isFalse();
  }

  public void testReDefinedNameValueIsNotMovable() {
    testSame("var redefined = 1; redefined = 2; var other = redefined;");
    assertThat(testedCollector.getTopLevelStatements().get(2).isMovableDeclaration()).isFalse();
  }

  public void testEmptyArrayLiteralIsMovable() {
    testSame("var a = [];");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isTrue();
  }

  public void testArrayLiteralOfMovablesIsMovable() {
    testSame("var wellDefinedName = 1; var a = [function(){}, 1, wellDefinedName, []];");
    assertThat(testedCollector.getTopLevelStatements().get(1).isMovableDeclaration()).isTrue();
  }

  public void testArrayLiteralWithImmovableIsImmovable() {
    testSame("var a = [unknownValue];");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isFalse();
  }

  public void testEmptyObjectLiteralIsMovable() {
    testSame("var o = {};");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isTrue();
  }

  public void testObjectLiteralOfMovablesIsMovable() {
    testSame(LINE_JOINER.join(
        "var wellDefinedName = 1;",
        "var o = { f: function(){}, one: 1, n: wellDefinedName, o: {}};"));
    assertThat(testedCollector.getTopLevelStatements().get(1).isMovableDeclaration()).isTrue();
  }

  public void testObjectLiteralWithImmovableIsImmovable() {
    testSame("var o = { v: unknownValue };");
    assertThat(testedCollector.getTopLevelStatements().get(0).isMovableDeclaration()).isFalse();
  }

  //  try to find cases to copy from CrossModuleCodeMotion
  private ReferenceCollection getReferencesForName(
      String name, CrossModuleReferenceCollector collector) {
    Var v = collector.getGlobalVariableNamesMap().get(name);
    assertThat(v).isNotNull();
    return collector.getReferences(v);
  }
}

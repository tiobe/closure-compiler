/*
 * Copyright 2007 The Closure Compiler Authors.
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
import static com.google.javascript.jscomp.CompilerTestCase.LINE_JOINER;
import static com.google.javascript.jscomp.testing.NodeSubject.assertNode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.NodeTraversal.AbstractNodeTypePruningCallback;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeTraversal.ChangeScopeRootCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import junit.framework.TestCase;

/**
 * Tests for {@link NodeTraversal}.
 */
public final class NodeTraversalTest extends TestCase {
  public void testPruningCallbackShouldTraverse1() {
    PruningCallback include =
      new PruningCallback(ImmutableSet.of(Token.SCRIPT, Token.VAR), true);

    Node script = new Node(Token.SCRIPT);
    assertTrue(include.shouldTraverse(null, script, null));
    assertTrue(include.shouldTraverse(null, new Node(Token.VAR), null));
    assertFalse(include.shouldTraverse(null, new Node(Token.NAME), null));
    assertFalse(include.shouldTraverse(null, new Node(Token.ADD), null));
  }

  public void testPruningCallbackShouldTraverse2() {
    PruningCallback include =
      new PruningCallback(ImmutableSet.of(Token.SCRIPT, Token.VAR), false);

    Node script = new Node(Token.SCRIPT);
    assertFalse(include.shouldTraverse(null, script, null));
    assertFalse(include.shouldTraverse(null, new Node(Token.VAR), null));
    assertTrue(include.shouldTraverse(null, new Node(Token.NAME), null));
    assertTrue(include.shouldTraverse(null, new Node(Token.ADD), null));
  }

  /**
   * Concrete implementation of AbstractPrunedCallback to test the
   * AbstractNodeTypePruningCallback shouldTraverse method.
   */
  static class PruningCallback extends AbstractNodeTypePruningCallback {
    public PruningCallback(Set<Token> nodeTypes, boolean include) {
      super(nodeTypes, include);
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      throw new UnsupportedOperationException();
    }
  }

  public void testReport() {
    final List<JSError> errors = new ArrayList<>();

    Compiler compiler = new Compiler(new BasicErrorManager() {

      @Override public void report(CheckLevel level, JSError error) {
        errors.add(error);
      }

      @Override public void println(CheckLevel level, JSError error) {
      }

      @Override protected void printSummary() {
      }
    });
    compiler.initCompilerOptionsIfTesting();

    NodeTraversal t = new NodeTraversal(compiler, null, new Es6SyntacticScopeCreator(compiler));
    DiagnosticType dt = DiagnosticType.warning("FOO", "{0}, {1} - {2}");

    t.report(new Node(Token.EMPTY), dt, "Foo", "Bar", "Hello");
    assertThat(errors).hasSize(1);
    assertEquals("Foo, Bar - Hello", errors.get(0).description);
  }

  private static final String TEST_EXCEPTION = "test me";

  public void testUnexpectedException() {

    NodeTraversal.Callback cb = new NodeTraversal.AbstractPostOrderCallback() {
      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {
        throw new RuntimeException(TEST_EXCEPTION);
      }
    };

    Compiler compiler = new Compiler();

    try {
      String code = "function foo() {}";
      Node tree = parse(compiler, code);
      NodeTraversal.traverseEs6(compiler, tree, cb);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertThat(e.getMessage())
          .startsWith("INTERNAL COMPILER ERROR.\n"
              + "Please report this problem.\n\n"
              + "test me");
    }
  }


  public void testGetScopeRoot() {
    Compiler compiler = new Compiler();
    String code = LINE_JOINER.join(
        "var a;",
        "function foo() {",
        "  var b",
        "}");
    Node tree = parse(compiler, code);
    NodeTraversal.traverseEs6(
        compiler,
        tree,
        new NodeTraversal.ScopedCallback() {

          @Override
          public void enterScope(NodeTraversal t) {
            Node root1 = t.getScopeRoot();
            Scope scope2 = t.getScope();
            Node root2 = scope2.getRootNode();
            assertNode(root2).isEqualTo(root1);
          }

          @Override
          public void exitScope(NodeTraversal t) {}

          @Override
          public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
            return true;
          }

          @Override
          public void visit(NodeTraversal t, Node n, Node parent) {}
        });
  }

  public void testGetHoistScopeRoot() {
    Compiler compiler = new Compiler();
    String code = LINE_JOINER.join(
        "function foo() {",
        "  if (true) { var XXX; }",
        "}");
    Node tree = parse(compiler, code);
    NodeTraversal.traverseEs6(compiler, tree,
        new NodeTraversal.Callback() {

          @Override
          public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
            return true;
          }

          @Override
          public void visit(NodeTraversal t, Node n, Node parent) {
            if (n.isName() && n.getString().equals("XXX")) {
              Node root = t.getClosestHoistScopeRoot();
              assertThat(NodeUtil.isFunctionBlock(root)).isTrue();

              t.getScope();  // force scope creation

              root = t.getClosestHoistScopeRoot();
              assertThat(NodeUtil.isFunctionBlock(root)).isTrue();
            }
          }
        }
    );
  }

  private static class NameChangingCallback implements NodeTraversal.Callback {
    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      return true;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName() && n.getString().equals("change")) {
        n.setString("xx");
        t.reportCodeChange();
      }
    }
  }

  public void testReportChange1() {
    String code = LINE_JOINER.join(
        "var change;",
        "function foo() {",
        "  var b",
        "}");
    assertChangesRecorded(code, new NameChangingCallback());
  }

  public void testReportChange2() {
    String code = LINE_JOINER.join(
        "var a;",
        "function foo() {",
        "  var change",
        "}");
    assertChangesRecorded(code, new NameChangingCallback());
  }

   public void testReportChange3() {
    String code = LINE_JOINER.join(
        "var a;",
        "function foo() {",
        "  var b",
        "}",
        "var change");
    assertChangesRecorded(code, new NameChangingCallback());
  }

  public void testReportChange4() {
    String code = LINE_JOINER.join(
        "function foo() {",
        "  function bar() {",
        "    var change",
        "  }",
        "}");
    assertChangesRecorded(code, new NameChangingCallback());
  }

  private void assertChangesRecorded(String code, NodeTraversal.Callback callback) {
    final String externs = "";
    Compiler compiler = new Compiler();
    Node tree = parseRoots(compiler, externs, code);

    ChangeVerifier changeVerifier = new ChangeVerifier(compiler).snapshot(tree);
    NodeTraversal.traverseRootsEs6(
        compiler, callback,  tree.getFirstChild(), tree.getSecondChild());
    changeVerifier.checkRecordedChanges(tree);
  }


  public void testGetLineNoAndGetCharno() {
    Compiler compiler = new Compiler();
    String code = ""
        + "var a; \n"
        + "function foo() {\n"
        + "  var b;\n"
        + "  if (a) { var c;}\n"
        + "}";
    Node tree = parse(compiler, code);
    final StringBuilder builder = new StringBuilder();
    NodeTraversal.traverseEs6(compiler, tree,
        new NodeTraversal.ScopedCallback() {

          @Override
          public void enterScope(NodeTraversal t) {
          }

          @Override
          public void exitScope(NodeTraversal t) {
          }

          @Override
          public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
            return true;
          }

          @Override
          public void visit(NodeTraversal t, Node n, Node parent) {
            builder.append("visit ");
            builder.append(t.getCurrentNode().toString(false, true, true));
            builder.append(" @");
            builder.append(t.getLineNumber());
            builder.append(":");
            builder.append(t.getCharno());
            builder.append("\n");
          }
        }
    );

    // Note the char numbers are 0-indexed but the line numbers are 1-indexed.
    String expectedResult =
        ""
            + "visit NAME a [source_file: [testcode]] @1:4\n"
            + "visit VAR [source_file: [testcode]] @1:0\n"
            + "visit NAME foo [source_file: [testcode]] @2:9\n"
            + "visit PARAM_LIST [source_file: [testcode]] @2:12\n"
            + "visit NAME b [source_file: [testcode]] @3:6\n"
            + "visit VAR [source_file: [testcode]] @3:2\n"
            + "visit NAME a [source_file: [testcode]] @4:6\n"
            + "visit NAME c [source_file: [testcode]] @4:15\n"
            + "visit VAR [source_file: [testcode]] @4:11\n"
            + "visit BLOCK [source_file: [testcode]] @4:9\n"
            + "visit IF [source_file: [testcode]] @4:2\n"
            + "visit BLOCK [source_file: [testcode]] @2:15\n"
            + "visit FUNCTION foo [source_file: [testcode]] @2:0\n"
            + "visit SCRIPT [source_file: [testcode]] "
            + "[input_id: InputId: [testcode]] "
            + "[feature_set: [block function]] @1:0\n";

    assertEquals(expectedResult, builder.toString());
  }

  public void testGetCurrentNode() {
    Compiler compiler = new Compiler();
    ScopeCreator creator = SyntacticScopeCreator.makeUntyped(compiler);
    ExpectNodeOnEnterScope callback = new ExpectNodeOnEnterScope();
    NodeTraversal t = new NodeTraversal(compiler, callback, creator);

    String code = LINE_JOINER.join(
        "var a;",
        "function foo() {",
        "  var b;",
        "}");

    Node tree = parse(compiler, code);
    Scope topScope = creator.createScope(tree, null);

    // Calling #traverseWithScope uses the given scope but starts traversal at
    // the given node.
    callback.expect(tree.getFirstChild(), tree);
    t.traverseWithScope(tree.getFirstChild(), topScope);
    callback.assertEntered();

    // Calling #traverse creates a new scope with the given node as the root.
    callback.expect(tree.getFirstChild(), tree.getFirstChild());
    t.traverse(tree.getFirstChild());
    callback.assertEntered();

    // Calling #traverseAtScope starts traversal from the scope's root.
    Node fn = tree.getSecondChild();
    Scope fnScope = creator.createScope(fn, topScope);
    callback.expect(fn, fn);
    t.traverseAtScope(fnScope);
    callback.assertEntered();
  }

  public void testTraverseAtScopeWithBlockScope() {
    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT_NEXT);
    compiler.initOptions(options);
    ScopeCreator creator = new Es6SyntacticScopeCreator(compiler);
    ExpectNodeOnEnterScope callback = new ExpectNodeOnEnterScope();
    NodeTraversal t = new NodeTraversal(compiler, callback, creator);

    String code = LINE_JOINER.join(
        "function foo() {",
        "  if (bar) {",
        "    let x;",
        "  }",
        "}");

    Node tree = parse(compiler, code);
    Scope topScope = creator.createScope(tree, null);

    Node innerBlock = tree  // script
        .getFirstChild()    // function
        .getLastChild()     // function body
        .getFirstChild()    // if
        .getLastChild();    // block

    Scope blockScope = creator.createScope(innerBlock, topScope);
    callback.expect(innerBlock, innerBlock);
    t.traverseAtScope(blockScope);
    callback.assertEntered();
  }

  public void testTraverseAtScopeWithForScope() {
    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT_2015);
    compiler.initOptions(options);
    ScopeCreator creator = new Es6SyntacticScopeCreator(compiler);
    ExpectNodeOnEnterScope callback = new ExpectNodeOnEnterScope();
    NodeTraversal t = new NodeTraversal(compiler, callback, creator);

    String code =
        LINE_JOINER.join(
            "function foo() {",
            "  var b = [0];",
            "  for (let a of b) {",
            "    let x;", "  }",
            "}");

    Node tree = parse(compiler, code);
    Scope topScope = creator.createScope(tree, null);

    Node forNode =
        tree // script
            .getFirstChild() // function
            .getLastChild() // function body
            .getSecondChild(); // for (first child is var b)

    Node innerBlock = forNode.getLastChild();

    Scope forScope = creator.createScope(forNode, topScope);
    creator.createScope(innerBlock, forScope);

    callback.expect(forNode, forNode);
    t.traverseAtScope(forScope);
    callback.assertEntered();
  }

  public void testTraverseAtScopeWithSwitchScope() {
    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT_2015);
    compiler.initOptions(options);
    ScopeCreator creator = new Es6SyntacticScopeCreator(compiler);
    ExpectNodeOnEnterScope callback = new ExpectNodeOnEnterScope();
    NodeTraversal t = new NodeTraversal(compiler, callback, creator);

    String code =
        LINE_JOINER.join(
            "function foo() {",
            "  var b = [0];",
            "  switch(b) {",
            "    case 1:",
            "       return b;",
            "    case 2:",
            "  }",
            "}");

    Node tree = parse(compiler, code);
    Scope topScope = creator.createScope(tree, null);

    Node innerBlock =
        tree // script
            .getFirstChild() // function
            .getLastChild() // function body
            .getSecondChild(); // switch (first child is var b)

    Scope blockScope = creator.createScope(innerBlock, topScope);
    callback.expect(innerBlock, innerBlock);
    t.traverseAtScope(blockScope);
    callback.assertEntered();
  }

  public void testTraverseAtScopeWithModuleScope() {
    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT_NEXT);
    compiler.initOptions(options);
    ScopeCreator creator = new Es6SyntacticScopeCreator(compiler);
    ExpectNodeOnEnterScope callback = new ExpectNodeOnEnterScope();
    NodeTraversal t = new NodeTraversal(compiler, callback, creator);

    String code = LINE_JOINER.join(
        "goog.module('example.module');",
        "",
        "var x;");

    Node tree = parse(compiler, code);
    Scope globalScope = creator.createScope(tree, null);
    Node moduleBody = tree.getFirstChild();
    Scope moduleScope = creator.createScope(moduleBody, globalScope);

    callback.expect(moduleBody, moduleBody);

    t.traverseAtScope(moduleScope);

    callback.assertEntered();
  }

  public void testGetVarAccessible() {
    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT_2015);
    compiler.initOptions(options);
    ScopeCreator creator = new Es6SyntacticScopeCreator(compiler);
    AccessibleCallback callback = new AccessibleCallback();
    NodeTraversal t = new NodeTraversal(compiler, callback, creator);

    // variables are hoisted to their enclosing scope
    String code =
        LINE_JOINER.join(
            "var varDefinedInScript;",
            "var foo = function(param) {",
            "  var varDefinedInFoo;",
            "  var baz = function() {",
            "    var varDefinedInBaz;",
            "  }",
            "}",
            "var bar = function() {",
            "  var varDefinedInBar;",
            "}");

    // the function scope should have access to all variables defined before and in the function
    // scope
    Node tree = parse(compiler, code);
    Node fooNode =
        tree // script
        .getSecondChild() // var foo declaration (first child is var varDefinedInScript)
        .getFirstFirstChild(); // child of the var foo declaration is the foo function
    Scope topScope = creator.createScope(tree, null);
    Scope fooScope = creator.createScope(fooNode, topScope);
    callback.expect(4);
    t.traverseAtScope(fooScope);
    callback.assertAccessible(fooScope);

    // the function block scope should have access to all variables defined in the global, function,
    // and function block scopes
    Node fooBlockNode = fooNode.getLastChild();
    Scope fooBlockScope = creator.createScope(fooBlockNode, fooScope);
    callback.expect(6);
    t.traverseAtScope(fooBlockScope);
    callback.assertAccessible(fooBlockScope);

    // let and const variables are block scoped
    code =
        LINE_JOINER.join(
            "var foo = function() {",
            "  var varDefinedInFoo;",
            "  var baz = function() {",
            "    var varDefinedInBaz;",
            "    let varDefinedInFoo;", // shadows parent scope
            "  }",
            "  let bar = 1;",
            "}");

    // the baz block scope has access to variables in its scope and parent scopes
    tree = parse(compiler, code);
    fooNode =
        tree // script
        .getFirstChild()// var foo declaration (first child is var varDefinedInScript)
        .getFirstFirstChild(); // child of the var foo declaration is the foo function
    fooBlockNode = fooNode.getLastChild(); // first child is param list of foo
    Node bazNode = fooBlockNode.getSecondChild().getFirstFirstChild();
    Node bazBlockNode = bazNode.getLastChild();

    topScope = creator.createScope(tree, null);
    fooScope = creator.createScope(fooNode, topScope);
    fooBlockScope = creator.createScope(fooBlockNode, fooScope);
    Scope bazScope = creator.createScope(bazNode, fooBlockScope);
    Scope bazBlockScope = creator.createScope(bazBlockNode, bazScope);

    // bar, baz, foo, varDefinedInFoo(in baz function), varDefinedInBaz
    callback.expect(5);
    t.traverseAtScope(bazBlockScope);
    callback.assertAccessible(bazBlockScope);
  }

  public void testTraverseEs6ScopeRoots_isLimitedToScope() {
    Compiler compiler = new Compiler();
    StringAccumulator callback = new StringAccumulator();

    String code =
        LINE_JOINER.join(
            "function foo() {",
            "  'string in foo';",
            "  function baz() {",
            "    'string nested in baz';",
            "  }",
            "}",
            "function bar() {",
            "  'string in bar';",
            "}");

    Node tree = parse(compiler, code);
    Node fooFunction = tree.getFirstChild();

    // Traverse without entering nested scopes.
    NodeTraversal.traverseEs6ScopeRoots(
        compiler, null, ImmutableList.of(fooFunction), callback, false);
    assertThat(callback.strings).containsExactly("string in foo");

    callback.strings.clear();

    // Traverse *with* entering nested scopes, now also sees "string nested in baz".
    NodeTraversal.traverseEs6ScopeRoots(
        compiler, null, ImmutableList.of(fooFunction), callback, true);
    assertThat(callback.strings).containsExactly("string in foo", "string nested in baz");
  }

  public void testTraverseEs6ScopeRoots_parentScopesWork() {
    Compiler compiler = new Compiler();
    LexicallyScopedVarsAccumulator callback = new LexicallyScopedVarsAccumulator();

    String code =
        LINE_JOINER.join(
            "var varDefinedInScript;",
            "var foo = function() {",
            "  var varDefinedInFoo;",
            "  var baz = function() {",
            "    var varDefinedInBaz;",
            "  }",
            "}",
            "var bar = function() {",
            "  var varDefinedInBar;",
            "}");

    Node tree = parse(compiler, code);
    Node fooFunction = tree.getSecondChild().getFirstFirstChild();

    // Traverse without entering nested scopes.
    NodeTraversal.traverseEs6ScopeRoots(
        compiler, null, ImmutableList.of(fooFunction), callback, false);
    assertThat(callback.varNames)
        .containsExactly("varDefinedInScript", "foo", "bar", "varDefinedInFoo", "baz");

    callback.varNames.clear();

    // Traverse *with* entering nested scopes, now also sees "varDefinedInBaz".
    NodeTraversal.traverseEs6ScopeRoots(
        compiler, null, ImmutableList.of(fooFunction), callback, true);
    assertThat(callback.varNames)
        .containsExactly(
            "varDefinedInScript", "foo", "bar", "varDefinedInFoo", "baz", "varDefinedInBaz");
  }

  public void testTraverseEs6ScopeRoots_callsEnterFunction() {
    Compiler compiler = new Compiler();
    EnterFunctionAccumulator callback = new EnterFunctionAccumulator();

    String code = LINE_JOINER.join(
        "function foo() {}",
        "function bar() {}",
        "function baz() {}");

    Node tree = parse(compiler, code);
    Node fooFunction = tree.getFirstChild();
    Node barFunction = fooFunction.getNext();
    Node bazFunction = barFunction.getNext();

    NodeTraversal.traverseEs6ScopeRoots(
        compiler,
        null,
        ImmutableList.of(fooFunction, barFunction, bazFunction),
        callback,
        callback, // FunctionCallback
        false);
    assertThat(callback.enteredFunctions).containsExactly(fooFunction, barFunction, bazFunction);
  }

  public void testTraverseEs6ScopeRoots_callsEnterScope() {
    Compiler compiler = new Compiler();

    List<Node> scopesEntered = new ArrayList<>();

    NodeTraversal.Callback callback = new NodeTraversal.ScopedCallback() {
      @Override
      public void visit(NodeTraversal t, Node n, Node parent) {}

      @Override
      public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
        return true;
      }

      @Override
      public void enterScope(NodeTraversal t) {
        scopesEntered.add(t.getScopeRoot());
      }

      @Override
      public void exitScope(NodeTraversal t) {}

    };

    String code = "function foo() { {} }";

    Node tree = parse(compiler, code);
    Node fooFunction = tree.getFirstChild();

    NodeTraversal.traverseEs6ScopeRoots(
        compiler,
        null,
        ImmutableList.of(fooFunction),
        callback,
        true);
    assertThat(scopesEntered).hasSize(3);  // Function, function's body, and the block inside it.
  }

  private static final class EnterFunctionAccumulator extends AbstractPostOrderCallback
      implements ChangeScopeRootCallback {

    List<Node> enteredFunctions = new ArrayList<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {}

    @Override
    public void enterChangeScopeRoot(AbstractCompiler compiler, Node root) {
      enteredFunctions.add(root);
    }
  }

  // Helper class used to collect all the vars from current scope and its parent scopes
  private static final class LexicallyScopedVarsAccumulator extends AbstractPostOrderCallback {

    final Set<String> varNames = new LinkedHashSet<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      Scope firstScope = t.getScope();
      if (firstScope == null) {
        return;
      }

      for (Scope scope = firstScope; scope != null; scope = scope.getParent()) {
        for (Var var : scope.getVarIterable()) {
          varNames.add(var.getName());
        }
      }
    }
  }

  private static final class StringAccumulator extends AbstractPostOrderCallback {

    final List<String> strings = new ArrayList<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isString()) {
        strings.add(n.getString());
      }
    }
  }

  // Helper class used to test getCurrentNode
  private static class ExpectNodeOnEnterScope extends NodeTraversal.AbstractPreOrderCallback
      implements NodeTraversal.ScopedCallback {
    private Node node;
    private Node scopeRoot;
    private boolean entered = false;

    private void expect(Node node, Node scopeRoot) {
      this.node = node;
      this.scopeRoot = scopeRoot;
      entered = false;
    }

    private void assertEntered() {
      assertTrue(entered);
    }

    @Override
    public void enterScope(NodeTraversal t) {
      assertNode(t.getCurrentNode()).isEqualTo(node);
      assertNode(t.getScopeRoot()).isEqualTo(scopeRoot);
      if (t.getScopeCreator().hasBlockScope() && (node.isForIn() || node.isForOf())) {
        node = node.getLastChild();
        scopeRoot = scopeRoot.getLastChild();
      }
      entered = true;
    }

    @Override
    public void exitScope(NodeTraversal t) {}

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      return true;
    }
  }

  // Helper class used to test accessible variables
  private static class AccessibleCallback extends NodeTraversal.AbstractPreOrderCallback
      implements NodeTraversal.ScopedCallback {
    private int numAccessible;

    private void expect(int accessible) {
      this.numAccessible = accessible;
    }

    private void assertAccessible(Scope s) {
      assertThat(s.getAllAccessibleVariables()).hasSize(numAccessible);
    }

    @Override
    public void enterScope(NodeTraversal t) {
    }

    @Override
    public void exitScope(NodeTraversal t) {
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      return true;
    }
  }

  private static Node parse(Compiler compiler, String js) {
    Node n = compiler.parseTestCode(js);
    assertThat(compiler.getErrors()).isEmpty();
    IR.root(n);
    return n;
  }

  private static Node parseRoots(Compiler compiler, String externs, String js) {
    Node extern = parse(compiler, externs).detach();
    Node main = parse(compiler, js).detach();

    return IR.root(IR.root(extern), IR.root(main));
  }
}


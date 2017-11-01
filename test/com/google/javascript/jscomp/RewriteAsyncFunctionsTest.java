/*
 * Copyright 2016 The Closure Compiler Authors.
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

import com.google.javascript.jscomp.CompilerOptions.LanguageMode;

public class RewriteAsyncFunctionsTest extends CompilerTestCase {

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    setAcceptedLanguage(LanguageMode.ECMASCRIPT_NEXT);
    setLanguageOut(LanguageMode.ECMASCRIPT3);
    enableRunTypeCheckAfterProcessing();
  }

  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    return new RewriteAsyncFunctions(compiler);
  }

  // Don't let the compiler actually inject any code.
  // It just makes the expected output hard to read and write.
  @Override
  protected Compiler createCompiler() {
    return new NoninjectingCompiler();
  }

  @Override
  protected NoninjectingCompiler getLastCompiler() {
    return (NoninjectingCompiler) super.getLastCompiler();
  }

  public void testRequiredCodeInjected() {
    test(
        "async function foo() { return 1; }",
        LINE_JOINER.join(
            "function foo() {",
            "  function* $jscomp$async$generator() {",
            "    return 1;",
            "  }",
            "  return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "}"));
    assertThat(getLastCompiler().injected).containsExactly("es6/execute_async_generator");
  }

  public void testAwaitReplacement() {
    test(
        "async function foo(promise) { return await promise; }",
        LINE_JOINER.join(
            "function foo(promise) {",
            "  function* $jscomp$async$generator() {",
            "    return yield promise;",
            "  }",
            "  return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "}"));
  }

  public void testArgumentsReplacement_topLevelCode() {
    testSame("arguments;");
  }

  public void testArgumentsReplacement_normalFunction() {
    testSame("function f(a, b, ...rest) { return arguments.length; }");
  }

  public void testArgumentsReplacement_asyncFunction() {
    test(
        "async function f(a, b, ...rest) { return arguments.length; }",
        LINE_JOINER.join(
            "function f(a, b, ...rest) {",
            "  const $jscomp$async$arguments = arguments;",
            "  function* $jscomp$async$generator() {",
            "    return $jscomp$async$arguments.length;", // arguments replaced
            "  }",
            "  return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "}"));
  }

  public void testArgumentsReplacement_asyncClosure() {
    test(
        LINE_JOINER.join(
            "function outer() {",
            "  async function f() { return arguments.length; }",
            "  return f(arguments)",
            "}"),
        LINE_JOINER.join(
            "function outer() {",
            "  function f() {",
            "    const $jscomp$async$arguments = arguments;",
            "    function* $jscomp$async$generator() {",
            "      return $jscomp$async$arguments.length;", // arguments replaced
            "    }",
            "    return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "  }",
            "  return f(arguments)", // unchanged
            "}"));
  }

  public void testArgumentsReplacement_normalClosureInAsync() {
    test(
        LINE_JOINER.join(
            "async function a() {",
            "  function inner() {",
            "    return arguments.length;",
            "  }",
            "  return inner.apply(undefined, arguments);", // this should get replaced
            "}"),
        LINE_JOINER.join(
            "function a() {",
            "  const $jscomp$async$arguments = arguments;",
            "  function* $jscomp$async$generator() {",
            "    function inner() {",
            "      return arguments.length;", // unchanged
            "    }",
            "    return inner.apply(undefined, $jscomp$async$arguments);", // arguments replaced
            "  }",
            "  return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "}"));
  }

  public void testClassMethod() {
    test(
        "class A { async f() { return this.x; } }",
        LINE_JOINER.join(
            "class A {",
            "  f() {",
            "    const $jscomp$async$this = this;",
            "    function* $jscomp$async$generator() {",
            "      return $jscomp$async$this.x;", // this replaced
            "    }",
            "    return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "  }",
            "}"));
  }

  public void testAsyncClassMethodWithAsyncArrow() {
    test(
        LINE_JOINER.join(
            "class A {",
            "  async f() {",
            "    let g = async () => { console.log(this, arguments); };",
            "    g();",
            "  }",
            "}"),
        LINE_JOINER.join(
            "class A {",
            "  f() {",
            "    const $jscomp$async$this = this;",
            "    const $jscomp$async$arguments = arguments;",
            "    function *$jscomp$async$generator() {",
            "      let g = () => {",
            "        function *$jscomp$async$generator() {",
            "          console.log($jscomp$async$this, $jscomp$async$arguments);",
            "        }",
            "        return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "      };",
            "      g();",
            "    }",
            "    return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "  }",
            "}"));
  }

  public void testNonAsyncClassMethodWithAsyncArrow() {
    test(
        LINE_JOINER.join(
            "class A {",
            "  f() {",
            "    let g = async () => { console.log(this, arguments); };",
            "    g();",
            "  }",
            "}"),
        LINE_JOINER.join(
            "class A {",
            "  f() {",
            "    let g = () => {",
            "      const $jscomp$async$this = this;",
            "      const $jscomp$async$arguments = arguments;",
            "      function *$jscomp$async$generator() {",
            "        console.log($jscomp$async$this, $jscomp$async$arguments);",
            "      }",
            "      return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "    };",
            "    g();",
            "  }",
            "}"));
  }

  public void testArrowFunctionExpressionBody() {
    test(
        "let f = async () => 1;",
        LINE_JOINER.join(
            "let f = () => {",
            "    function* $jscomp$async$generator() {",
            "      return 1;",
            "    }",
            "    return $jscomp.executeAsyncGenerator($jscomp$async$generator());",
            "}"));
  }
}

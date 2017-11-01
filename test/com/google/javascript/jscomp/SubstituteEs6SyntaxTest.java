/*
 * Copyright 2015 The Closure Compiler Authors.
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

/**
 * Tests for {@link SubstituteEs6Syntax} in isolation.
 */
public final class SubstituteEs6SyntaxTest extends CompilerTestCase {

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    setAcceptedLanguage(CompilerOptions.LanguageMode.ECMASCRIPT_2015);
  }

  @Override
  protected CompilerPass getProcessor(final Compiler compiler) {
    return new SubstituteEs6Syntax(compiler);
  }

  public void testArrowFunctions() {
    testSame("function f() {}");
    testSame("(function() { this.x = 5; })");
    testSame("(function() { return arguments[0]; })");
    testSame("(function() { return ()=>this; })");
    testSame("(function() { return ()=>arguments[0]; })");
    testSame("(function() { x++; return 5; })");
    test("()=>{ return 5; }", "()=>5");
    test("()=>{ return; }", "()=>undefined");
    test("(x)=>{ return x+1 }", "(x) => x+1");
    test("(x)=>{ return x++,5; }", "(x) => (x++,5)");
  }

  public void testObjectPattern() {
    test("const {x: x} = y;", "const {x} = y;");
    testSame("const {x: y} = z;");
    testSame("const {[x]: x} = y;");
    testSame("const {['x']: x} = y;");
  }

  public void testObjectLiteral() {
    test("const o = {x: x};", "const o = {x};");
    testSame("const o = {x: y}");
    testSame("const o = {[x]: x};");
    testSame("const o = {['x']: x};");
  }
}

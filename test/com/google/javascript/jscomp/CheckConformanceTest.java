/*
 * Copyright 2014 The Closure Compiler Authors.
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

import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.CheckConformance.InvalidRequirementSpec;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.ConformanceRules.AbstractRule;
import com.google.javascript.jscomp.ConformanceRules.ConformanceResult;
import com.google.javascript.jscomp.testing.BlackHoleErrorManager;
import com.google.javascript.rhino.Node;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import java.util.List;

/**
 * Tests for {@link CheckConformance}.
 *
 */
public final class CheckConformanceTest extends TypeICompilerTestCase {
  private String configuration;

  private static final String EXTERNS =
      LINE_JOINER.join(
          DEFAULT_EXTERNS,
          "/** @constructor */ function Window() {};",
          "/** @type {Window} */ var window;",
          "/** @type {Function} */ Arguments.prototype.callee;",
          "/** @type {Function} */ Arguments.prototype.caller;",
          "/** @type {Arguments} */ var arguments;",
          "/** @constructor ",
          " * @param {*=} opt_message",
          " * @param {*=} opt_file",
          " * @param {*=} opt_line",
          " * @return {!Error}",
          "*/",
          "function Error(opt_message, opt_file, opt_line) {};",
          "function alert(y) {};",
          "/** @constructor */ function ObjectWithNoProps() {};",
          "function eval() {}");

  private static final String DEFAULT_CONFORMANCE =
      LINE_JOINER.join(
          "requirement: {",
          "  type: BANNED_NAME",
          "  value: 'eval'",
          "   error_message: 'eval is not allowed'",
          "}",
          "",
          "requirement: {",
          "  type: BANNED_PROPERTY",
          "  value: 'Arguments.prototype.callee'",
          "  error_message: 'Arguments.prototype.callee is not allowed'",
          "}");

  public CheckConformanceTest() {
    super(EXTERNS);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    enableTranspile();
    enableClosurePass();
    enableClosurePassForExpected();
    enableRewriteClosureCode();
    setLanguage(LanguageMode.ECMASCRIPT_2015, LanguageMode.ECMASCRIPT5_STRICT);
    enableClosurePass();
    configuration = DEFAULT_CONFORMANCE;
    ignoreWarnings(DiagnosticGroups.MISSING_PROPERTIES);
  }

  @Override
  protected CompilerPass getProcessor(final Compiler compiler) {
    ConformanceConfig.Builder builder = ConformanceConfig.newBuilder();
    try {
      TextFormat.merge(configuration, builder);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
    return new CheckConformance(compiler, ImmutableList.of(builder.build()));
  }

  @Override
  protected int getNumRepetitions() {
    // This compiler pass is not idempotent and should only be run over a
    // parse tree once.
    return 1;
  }

  public void testViolation1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "}";

    testWarning("eval()", CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(
        "Function.prototype.name; eval.name.length", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testViolation2() {
    testWarning("function f() { arguments.callee }", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testNotViolation1() {
    testNoWarning(
        "/** @constructor */ function Foo() { this.callee = 'string'; }\n" +
        "/** @constructor */ function Bar() { this.callee = 1; }\n" +
        "\n" +
        "\n" +
        "function f() {\n" +
        "  var x;\n" +
        "  switch(random()) {\n" +
        "    case 1:\n" +
        "      x = new Foo();\n" +
        "      break;\n" +
        "    case 2:\n" +
        "      x = new Bar();\n" +
        "      break;\n" +
        "    default:\n" +
        "      return;\n" +
        "  }\n" +
        "  var z = x.callee;\n" +
        "}");
  }

  public void testNotViolation2() {
    configuration =
        "requirement: {\n"
            + "  type: BANNED_NAME\n"
            + "  value: 'location'\n"
            + "  error_message: 'location is not allowed'\n"
            + "}";
    testNoWarning("function f() { var location = null; }");
  }

  public void testMaybeViolation1() {
    testWarning("function f() { y.callee }", CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testWarning(
        "function f() { new Foo().callee }", CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testWarning(
        "function f() { new Object().callee }", CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    // NTI warns since "callee" doesn't exist on *
    testWarning(
        "/** @suppress {newCheckTypes} */ function f() { /** @type {*} */ var x; x.callee }",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testNoWarning("function f() {/** @const */ var x = {}; x.callee = 1; x.callee}");
  }

  public void testBadWhitelist1() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'placeholder'\n" +
        "  whitelist_regexp: '('\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: invalid regex pattern\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "whitelist_regexp: \"(\"\n"
            + "type: BANNED_NAME\n"
            + "value: \"eval\"\n");
  }

  public void testViolationWhitelisted1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  whitelist: 'testcode'\n " +
        "}";

    testNoWarning(
        "eval()");
  }

  public void testViolationWhitelisted2() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  whitelist_regexp: 'code$'\n " +
        "}";

    testNoWarning(
        "eval()");
  }

  public void testFileOnOnlyApplyToIsChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to: 'foo.js'\n " +
        "}";
    ImmutableList<SourceFile> inputs = ImmutableList.of(
            SourceFile.fromCode("foo.js", "eval()"));
    testWarning(inputs, CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: eval is not allowed");
  }

  public void testFileNotOnOnlyApplyToIsNotChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to: 'foo.js'\n " +
        "}";
    testNoWarning(ImmutableList.of(SourceFile.fromCode("bar.js", "eval()")));
  }

  public void testFileOnOnlyApplyToRegexpIsChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to_regexp: 'test.js$'\n " +
        "}";
    ImmutableList<SourceFile> input = ImmutableList.of(
            SourceFile.fromCode("foo_test.js", "eval()"));
    testWarning(input, CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: eval is not allowed");
  }

  public void testFileNotOnOnlyApplyToRegexpIsNotChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to_regexp: 'test.js$'\n " +
        "}";
    testNoWarning(ImmutableList.of(SourceFile.fromCode("bar.js", "eval()")));
  }

  public void testBannedNameCall() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME_CALL\n" +
        "  value: 'Function'\n" +
        "  error_message: 'Calling Function is not allowed.'\n" +
        "}";

    testNoWarning("f instanceof Function");
    testWarning("new Function(str);", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testInferredConstCheck() {
    configuration =
        LINE_JOINER.join(
            "requirement: {",
            "  type: CUSTOM",
            "  java_class: 'com.google.javascript.jscomp.ConformanceRules$InferredConstCheck'",
            "  error_message: 'Failed to infer type of constant'",
            "}");

    testNoWarning("/** @const */ var x = 0;");

    // We @suppress {newCheckTypes} to suppress NTI uninferred const and global this warnings.

    testWarning(
        LINE_JOINER.join(
            "/** @constructor @suppress {newCheckTypes} */",
            "function f() {",
            "  /** @const */ this.foo = unknown;",
            "}",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "/** @this {f} @suppress {newCheckTypes} */",
            "var init_f = function() {",
            "  /** @const */ this.foo = unknown;",
            "};",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "/** @suppress {newCheckTypes} */",
            "var init_f = function() {",
            "  /** @const */ this.FOO = unknown;",
            "};",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "/** @suppress {newCheckTypes} */",
            "f.prototype.init_f = function() {",
            "  /** @const */ this.FOO = unknown;",
            "};",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testNoWarning(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "f.prototype.init_f = function() {",
            "  /** @const {?} */ this.FOO = unknown;",
            "};",
            "var x = new f();"));

    testNoWarning(
        LINE_JOINER.join(
            "/** @const */",
            "var ns = {};",
            "/** @const */",
            "ns.subns = ns.subns || {};"));

    // We only check @const nodes, not @final nodes.
    testNoWarning(
        LINE_JOINER.join(
            "/** @constructor @suppress {newCheckTypes} */",
            "function f() {",
            "  /** @final */ this.foo = unknown;",
            "}",
            "var x = new f();"));
  }

  public void testBannedCodePattern1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_CODE_PATTERN\n" +
        "  value: '/** @param {string|String} a */" +
                  "function template(a) {a.blink}'\n" +
        "  error_message: 'blink is annoying'\n" +
        "}";

    String externs = EXTERNS + "String.prototype.blink;";

    testNoWarning(
        "/** @constructor */ function Foo() { this.blink = 1; }\n" +
        "var foo = new Foo();\n" +
        "foo.blink();");

    testWarning(
        externs,
        "'foo'.blink;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: blink is annoying");

    testWarning(
        externs,
        "'foo'.blink();",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: blink is annoying");

    testWarning(
        externs,
        "String('foo').blink();",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: blink is annoying");

    testWarning(
        externs,
        "foo.blink();",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: blink is annoying\n"
        + "The type information available for this expression is too loose to ensure conformance.");
  }

  public void testBannedDep1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_DEPENDENCY\n" +
        "  value: 'testcode'\n" +
        "  error_message: 'testcode is not allowed'\n" +
        "}";

    testWarning(
        "anything;", CheckConformance.CONFORMANCE_VIOLATION, "Violation: testcode is not allowed");
  }

  public void testReportLooseTypeViolations() {
    configuration =
        LINE_JOINER.join(
            "requirement: {",
            "  type: BANNED_PROPERTY_WRITE",
            "  value: 'HTMLScriptElement.prototype.textContent'",
            "  error_message: 'Setting content of <script> is dangerous.'",
            "  report_loose_type_violations: false",
            "}");

    String externs =
        LINE_JOINER.join(
            DEFAULT_EXTERNS,
            "/** @constructor */ function Element() {}",
            "/** @type {string} @implicitCast */",
            "Element.prototype.textContent;",
            "/** @constructor @extends {Element} */ function HTMLScriptElement() {}\n");

    testWarning(
        externs,
        "(new HTMLScriptElement).textContent = 'alert(1);'",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: Setting content of <script> is dangerous.");

    testWarning(
        externs,
        "HTMLScriptElement.prototype.textContent = 'alert(1);'",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: Setting content of <script> is dangerous.");

    testNoWarning(externs, "(new Element).textContent = 'safe'");
  }

  public void testDontCrashOnNonConstructorWithPrototype() {
    configuration =
        LINE_JOINER.join(
            "requirement: {",
            "  type: BANNED_PROPERTY_WRITE",
            "  value: 'Bar.prototype.method'",
            "  error_message: 'asdf'",
            "  report_loose_type_violations: false",
            "}");

    testNoWarning(
        DEFAULT_EXTERNS + LINE_JOINER.join(
            "/** @constructor */",
            "function Bar() {}",
            "Bar.prototype.method = function() {};"),
        LINE_JOINER.join(
            "function Foo() {}",
            "Foo.prototype.method = function() {};"));
  }

  private void testConformance(String src1, String src2) {
    ImmutableList<SourceFile> inputs = ImmutableList.of(
        SourceFile.fromCode("SRC1", src1),
        SourceFile.fromCode("SRC2", src2));
    testNoWarning(inputs);
  }

  private void testConformance(String src1, String src2, DiagnosticType warning) {
    ImmutableList<SourceFile> inputs = ImmutableList.of(
            SourceFile.fromCode("SRC1", src1),
            SourceFile.fromCode("SRC2", src2));
    testWarning(inputs, warning);
  }

  public void testBannedProperty0() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cDecl = LINE_JOINER.join(
        "/** @constructor */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;");

    String dDecl = LINE_JOINER.join(
        "/** @constructor */ function D() {}",
        "/** @type {string} */",
        "D.prototype.p;");

    testConformance(cDecl, dDecl);
  }

  public void testBannedProperty1() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cDecl = LINE_JOINER.join(
        "/** @constructor */",
        "function C() {",
        "  this.p = 'str';",
        "}");

    String dDecl = LINE_JOINER.join(
        "/** @constructor */",
        "function D() {",
        "  this.p = 'str';",
        "}");

    testConformance(cDecl, dDecl);
  }

  public void testBannedProperty2() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String declarations = LINE_JOINER.join(
        "/** @constructor */ function SC() {}",
        "/** @constructor @extends {SC} */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;",
        "/** @constructor */ function D() {}",
        "/** @type {string} */",
        "D.prototype.p;");

    testConformance(declarations, "var d = new D(); d.p = 'boo';");

    testConformance(declarations, "var c = new C(); c.p = 'boo';",
        CheckConformance.CONFORMANCE_VIOLATION);

    // Accessing property through a super type is possibly a violation.
    testConformance(declarations, "var sc = new SC(); sc.p = 'boo';",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testConformance(declarations, "var c = new C(); var foo = c.p;",
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(declarations, "var c = new C(); var foo = 'x' + c.p;",
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(declarations, "var c = new C(); c['p'] = 'boo';",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedProperty3() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cdecl = LINE_JOINER.join(
        "/** @constructor */ function SC() {}",
        "/** @constructor @extends {SC} */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;");
    String ddecl = LINE_JOINER.join(
        "/** @constructor @template T */ function D() {}",
        "/** @suppress {newCheckTypes} @param {T} a */",
        "D.prototype.method = function(a) {",
        "  use(a.p);",
        "};");

    testConformance(cdecl, ddecl,
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);
  }

  public void testBannedProperty4() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cdecl = LINE_JOINER.join(
        "/** @constructor */ function SC() {}",
        "/** @constructor @extends {SC} */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;",
        "",
        "/**",
        " * @param {K} key",
        " * @param {V=} opt_value",
        " * @constructor",
        " * @struct",
        " * @template K, V",
        " * @private",
        " */",
        "var Entry_ = function(key, opt_value) {",
        "  /** @const {K} */",
        "  this.key = key;",
        "  /** @type {V|undefined} */",
        "  this.value = opt_value;",
        "};");

    String ddecl = LINE_JOINER.join(
        "/** @constructor @template T */ function D() {}",
        "/** @param {T} a */",
        "D.prototype.method = function(a) {",
        "  var entry = new Entry('key');",
        "  use(entry.value.p);",
        "};");

    testConformance(cdecl, ddecl,
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);
  }

  public void testBannedProperty5() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'Array.prototype.push'",
        "  error_message: 'banned Array.prototype.push'",
        "}");

    testWarning("[1, 2, 3].push(4);\n", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedProperty_recordType() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'Logger.prototype.config'",
        "  error_message: 'Logger.config is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String declaration = "class Logger { config() {} }";

    // Fine, because there is no explicit relationship between Logger & GoodRecord.
    testConformance(declaration, LINE_JOINER.join(
        "/** @record */",
        "class GoodRecord {",
        "  constructor() {",
        "    /** @type {Function} */ this.config;",
        "  }",
        "}"));

    // Bad, because there is a direct relationship.
    testConformance("/** @implements {BadRecord} */ " + declaration, LINE_JOINER.join(
        "/** @record */",
        "class BadRecord {",
        "  constructor() {",
        "    /** @type {Function} */ this.config;",
        "  }",
        "}"),
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);
  }

  public void testBannedProperty_namespacedType() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'ns.C.prototype.p'",
        "  error_message: 'ns.C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String declarations = LINE_JOINER.join(
        "/** @const */",
        "var ns = {};",
        "/** @constructor */ function SC() {}",
        "/** @constructor @extends {SC} */",
        "ns.C = function() {}",
        "/** @type {string} */",
        "ns.C.prototype.p;",
        "/** @constructor */ function D() {}",
        "/** @type {string} */",
        "D.prototype.p;");

    testConformance(declarations, "var d = new D(); d.p = 'boo';");

    testConformance(declarations, "var c = new ns.C(); c.p = 'boo';",
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(declarations, "var c = new SC(); c.p = 'boo';",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);
  }

  public void testBannedPropertyWrite() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_WRITE\n" +
        "  value: 'C.prototype.p'\n" +
        "  error_message: 'Assignment to C.p is not allowed'\n" +
        "}";

    String declarations =
        "/** @constructor */ function C() {}\n" +
        "/** @type {string} */\n" +
        "C.prototype.p;\n" +
        "/** @constructor */ function D() {}\n" +
        "/** @type {string} */\n" +
        "D.prototype.p;\n";

    testNoWarning(
        declarations + "var d = new D(); d.p = 'boo';");

    testWarning(
        declarations + "var c = new C(); c.p = 'boo';", CheckConformance.CONFORMANCE_VIOLATION);

    testNoWarning(
        declarations + "var c = new C(); var foo = c.p;");

    testNoWarning(
        declarations + "var c = new C(); var foo = 'x' + c.p;");

    testWarning(
        declarations + "var c = new C(); c['p'] = 'boo';", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedPropertyWriteExtern() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_WRITE\n" +
        "  value: 'Element.prototype.innerHTML'\n" +
        "  error_message: 'Assignment to Element.innerHTML is not allowed'\n" +
        "}";

    String externs =
        DEFAULT_EXTERNS
        + "/** @constructor */ function Element() {}\n" +
        "/** @type {string} @implicitCast */\n" +
        "Element.prototype.innerHTML;\n";

    testWarning(externs, "var e = new Element(); e.innerHTML = '<boo>';",
        CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(externs, "var e = new Element(); e.innerHTML = 'foo';",
        CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(externs, "var e = new Element(); e['innerHTML'] = 'foo';",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedPropertyNonConstantWrite() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_NON_CONSTANT_WRITE\n" +
        "  value: 'C.prototype.p'\n" +
        "  error_message: 'Assignment of a non-constant value to C.p is not allowed'\n" +
        "}";

    String declarations =
        "/** @constructor */ function C() {}\n" +
        "/** @type {string} */\n" +
        "C.prototype.p;\n";

    testNoWarning(declarations + "var c = new C(); c.p = 'boo';");
    testNoWarning(declarations + "var c = new C(); c.p = 'foo' + 'bar';");

    testWarning(
        declarations + "var boo = 'boo'; var c = new C(); c.p = boo;",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedPropertyRead() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_READ\n" +
        "  value: 'C.prototype.p'\n" +
        "  error_message: 'Use of C.p is not allowed'\n" +
        "}";

    String declarations =
        "/** @constructor */ function C() {}\n" +
        "/** @type {string} */\n" +
        "C.prototype.p;\n" +
        "/** @constructor */ function D() {}\n" +
        "/** @type {string} */\n" +
        "D.prototype.p;\n" +
        "function use(a) {};";

    testNoWarning(
        declarations + "var d = new D(); d.p = 'boo';");

    testNoWarning(
        declarations + "var c = new C(); c.p = 'boo';");

    testWarning(
        declarations + "var c = new C(); use(c.p);", CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(
        declarations + "var c = new C(); var foo = c.p;", CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(
        declarations + "var c = new C(); var foo = 'x' + c.p;",
        CheckConformance.CONFORMANCE_VIOLATION);

    testNoWarning(
        declarations + "var c = new C(); c['p'] = 'boo';");

    testWarning(
        declarations + "var c = new C(); use(c['p']);", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall1() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_METHOD_CALL\n" +
        "  value: 'C.prototype.m:function(number)'\n" +
        "  error_message: 'm method param must be number'\n" +
        "}";

    String code =
        "/** @constructor */ function C() {}\n" +
        "/** @param {*} a */\n" +
        "C.prototype.m = function(a){}\n";

    testNoWarning(
        code + "new C().m(1);");

    testWarning(code + "new C().m('str');", CheckConformance.CONFORMANCE_VIOLATION);

    testNoWarning(
        code + "new C().m.call(new C(), 1);");

    testWarning(
        code + "new C().m.call(new C(), 'str');", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall2() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_NAME_CALL\n" +
        "  value: 'C.m:function(number)'\n" +
        "  error_message: 'C.m method param must be number'\n" +
        "}";

    String code =
        "/** @constructor */ function C() {}\n" +
        "/** @param {*} a */\n" +
        "C.m = function(a){}\n";

    testNoWarning(
        code + "C.m(1);");

    testWarning(code + "C.m('str');", CheckConformance.CONFORMANCE_VIOLATION);

    testNoWarning(
        code + "C.m.call(this, 1);");

    testWarning(code + "C.m.call(this, 'str');", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall3() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_NAME_CALL\n" +
        "  value: 'C:function(number)'\n" +
        "  error_message: 'C method must be number'\n" +
        "}";

    String code =
        "/** @constructor @param {...*} a */ function C(a) {}\n";

    testNoWarning(
        code + "new C(1);");

    testWarning(code + "new C('str');", CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(code + "new C(1, 1);", CheckConformance.CONFORMANCE_VIOLATION);

    testWarning(code + "new C();", CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall4() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_NAME_CALL\n" +
        "  value: 'C:function(number)'\n" +
        "  error_message: 'C method must be number'\n" +
        "}";

    String code =
        "/** @constructor @param {...*} a */ function C(a) {}\n";

    testNoWarning(EXTERNS + "goog.inherits;", code + "goog.inherits(A, C);");
  }

  public void testRestrictedMethodCallThisType() {
    configuration = ""
        + "requirement: {\n"
        + "  type: RESTRICTED_METHOD_CALL\n"
        + "  value: 'Base.prototype.m:function(this:Sub,number)'\n"
        + "  error_message: 'Only call m on the subclass'\n"
        + "}";

    String code =
        "/** @constructor */\n"
        + "function Base() {}; Base.prototype.m;\n"
        + "/** @constructor @extends {Base} */\n"
        + "function Sub() {}\n"
        + "var b = new Base();\n"
        + "var s = new Sub();\n"
        + "var maybeB = cond ? new Base() : null;\n"
        + "var maybeS = cond ? new Sub() : null;\n";

    testWarning(code + "b.m(1)", CheckConformance.CONFORMANCE_VIOLATION);
    testWarning(code + "maybeB.m(1)", CheckConformance.CONFORMANCE_VIOLATION);
    testNoWarning(code + "s.m(1)");
    testNoWarning(code + "maybeS.m(1)");
  }

  public void testRestrictedMethodCallUsingCallThisType() {
    configuration = ""
        + "requirement: {\n"
        + "  type: RESTRICTED_METHOD_CALL\n"
        + "  value: 'Base.prototype.m:function(this:Sub,number)'\n"
        + "  error_message: 'Only call m on the subclass'\n"
        + "}";

    String code =
        "/** @constructor */\n"
        + "function Base() {}; Base.prototype.m;\n"
        + "/** @constructor @extends {Base} */\n"
        + "function Sub() {}\n"
        + "var b = new Base();\n"
        + "var s = new Sub();\n"
        + "var maybeB = cond ? new Base() : null;\n"
        + "var maybeS = cond ? new Sub() : null;";

    testWarning(code + "b.m.call(b, 1)", CheckConformance.CONFORMANCE_VIOLATION);
    testWarning(code + "b.m.call(maybeB, 1)", CheckConformance.CONFORMANCE_VIOLATION);
    testNoWarning(code + "b.m.call(s, 1)");
    testNoWarning(code + "b.m.call(maybeS, 1)");
  }

  public void testCustom1() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: missing java_class\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n");
  }

  public void testCustom2() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'MissingClass'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: JavaClass not found.\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"MissingClass\"\n");
  }

  public void testCustom3() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: JavaClass is not a rule.\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"com.google.javascript.jscomp.CheckConformanceTest\"\n");
  }

  // A custom rule missing a callable constructor.
  public static class CustomRuleMissingPublicConstructor extends AbstractRule {
    CustomRuleMissingPublicConstructor(
        AbstractCompiler compiler, Requirement requirement)
            throws InvalidRequirementSpec {
      super(compiler, requirement);
      if (requirement.getValueCount() == 0) {
        throw new InvalidRequirementSpec("missing value");
      }
    }

    @Override
    protected ConformanceResult checkConformance(NodeTraversal t, Node n) {
      // Everything is ok.
      return ConformanceResult.CONFORMANCE;
    }
  }


  // A valid custom rule.
  public static class CustomRule extends AbstractRule {
    public CustomRule(AbstractCompiler compiler, Requirement requirement)
        throws InvalidRequirementSpec {
      super(compiler, requirement);
      if (requirement.getValueCount() == 0) {
        throw new InvalidRequirementSpec("missing value");
      }
    }

    @Override
    protected ConformanceResult checkConformance(NodeTraversal t, Node n) {
      // Everything is ok.
      return ConformanceResult.CONFORMANCE;
    }
  }

  // A valid custom rule.
  public static class CustomRuleReport extends AbstractRule {
    public CustomRuleReport(AbstractCompiler compiler, Requirement requirement)
        throws InvalidRequirementSpec {
      super(compiler, requirement);
      if (requirement.getValueCount() == 0) {
        throw new InvalidRequirementSpec("missing value");
      }
    }

    @Override
    protected ConformanceResult checkConformance(NodeTraversal t, Node n) {
      // Everything is ok.
      return n.isScript() ? ConformanceResult.VIOLATION
          : ConformanceResult.CONFORMANCE;
    }
  }

  public void testCustom4() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$" +
            "CustomRuleMissingPublicConstructor'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: No valid class constructors found.\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"com.google.javascript.jscomp.CheckConformanceTest$"
            + "CustomRuleMissingPublicConstructor\"\n");
  }


  public void testCustom5() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$CustomRule'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: missing value\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"com.google.javascript.jscomp.CheckConformanceTest$CustomRule\"\n");
  }

  public void testCustom6() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$CustomRule'\n" +
        "  value: 'placeholder'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testNoWarning(
        "anything;");
  }

  public void testCustom7() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$" +
        "CustomRuleReport'\n" +
        "  value: 'placeholder'\n" +
        "  error_message: 'CustomRule Message'\n" +
        "}";

    testWarning(
        "anything;", CheckConformance.CONFORMANCE_VIOLATION, "Violation: CustomRule Message");
  }

  public void testCustomBanExpose() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanExpose'\n" +
        "  error_message: 'BanExpose Message'\n" +
        "}";

    testWarning(
        "/** @expose */ var x;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanExpose Message");
  }

  public void testCustomRestrictThrow1() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanThrowOfNonErrorTypes'\n" +
        "  error_message: 'BanThrowOfNonErrorTypes Message'\n" +
        "}";

    testWarning(
        "throw 'blah';",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanThrowOfNonErrorTypes Message");
  }

  public void testCustomRestrictThrow2() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanThrowOfNonErrorTypes'\n" +
        "  error_message: 'BanThrowOfNonErrorTypes Message'\n" +
        "}";

    testNoWarning("throw new Error('test');");
  }

  public void testCustomRestrictThrow3() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanThrowOfNonErrorTypes'\n" +
        "  error_message: 'BanThrowOfNonErrorTypes Message'\n" +
        "}";

    testNoWarning(LINE_JOINER.join(
        "/** @param {*} x */",
        "function f(x) {",
        "  throw x;",
        "}"));
  }

  public void testCustomRestrictThrow4() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanThrowOfNonErrorTypes'\n" +
        "  error_message: 'BanThrowOfNonErrorTypes Message'\n" +
        "}";

    testNoWarning(LINE_JOINER.join(
        "/** @constructor @extends {Error} */",
        "function MyError() {}",
        "/** @param {*} x */",
        "function f(x) {",
        "  if (x instanceof MyError) {",
        "  } else {",
        "    throw x;",
        "  }",
        "}"));
  }

  public void testCustomBanUnknownThis1() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testWarning(
        "function f() {alert(this);}",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanUnknownThis Message");
  }

  // TODO(johnlenz): add a unit test for templated "this" values.

  public void testCustomBanUnknownThis2() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testNoWarning(
        "/** @constructor */ function C() {alert(this);}");
  }

  public void testCustomBanUnknownThis3() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testNoWarning(
        "function f() {alert(/** @type {Error} */(this));}");
  }

  public void testCustomBanUnknownThis4() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testNoWarning(
        "function f() {goog.asserts.assertInstanceof(this, Error);}");
  }

  private static String config(String rule, String message, String... fields) {
    String result = "requirement: {\n"
        + "  type: CUSTOM\n"
        + "  java_class: '" + rule + "'\n";
    for (String field : fields) {
      result += field;
    }
    result += "  error_message: '" + message + "'\n" + "}";
    return result;
  }

  private static String rule(String rule) {
    return "com.google.javascript.jscomp.ConformanceRules$" + rule;
  }

  private static String value(String value) {
    return "  value: '" + value + "'\n";
  }

  public void testCustomBanUnknownThisProp1() {
    configuration = config(rule("BanUnknownDirectThisPropsReferences"), "My rule message");

    testWarning(
        "/** @constructor */ function f() {}; f.prototype.prop;"
            + "f.prototype.method = function() { alert(this.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");
  }

  public void testCustomBanUnknownThisProp2() {
    configuration = config(rule("BanUnknownDirectThisPropsReferences"), "My rule message");

    testNoWarning(
        "/** @constructor */ function f() {}; f.prototype.prop;"
            + "f.prototype.method = function() { this.prop = foo; };");
  }

  public void testCustomBanUnknownProp1() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testWarning(
        "/** @constructor */ function f() {}; f.prototype.prop;"
            + "f.prototype.method = function() { alert(this.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"prop\" on type \"f\"");
  }

  public void testCustomBanUnknownProp2() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    String js = LINE_JOINER.join(
        "Object.prototype.foobar;",
        " /** @param {ObjectWithNoProps} a */",
        "function f(a) { alert(a.foobar); };");

    testWarning(
        js,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"foobar\" on type \"(ObjectWithNoProps|null)\"");
  }

  public void testCustomBanUnknownProp3() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testNoWarning(
        "/** @constructor */ function f() {}"
            + "f.prototype.method = function() { this.prop = foo; };");
  }

  public void testCustomBanUnknownProp4() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testNoWarning(
        LINE_JOINER.join(
            "/** @constructor */ function f() { /** @type {?} */ this.prop = null; };",
            "f.prototype.method = function() { alert(this.prop); }"));
  }

  public void testCustomBanUnknownProp5() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testWarning(
        LINE_JOINER.join(
            "/** @typedef {?} */ var Unk;",
            "/** @constructor */ function f() { /** @type {?Unk} */ this.prop = null; };",
            "f.prototype.method = function() { alert(this.prop); }"),
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"prop\" on type \"f\"");
  }

  public void testCustomBanUnknownProp6() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testWarning(
        LINE_JOINER.join(
            "goog.module('example');",
            "/** @constructor */ function f() { this.prop; };",
            "f.prototype.method = function() { alert(this.prop); }"),
        CheckConformance.CONFORMANCE_VIOLATION,
        // TODO(tbreisacher): Can we display a more user-friendly name here?
        "Violation: My rule message\nThe property \"prop\" on type \"module$contents$example_f\"");
  }

  public void testCustomBanUnknownProp7() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testNoWarning(LINE_JOINER.join(
        "/** @constructor */",
        "function Foo() {",
        "  /** @type {!Object<number, number>} */",
        "  this.prop;",
        "}",
        "function f(/** !Foo */ x) {",
        "  return x.prop[1] + 123;",
        "}"));
  }

  public void testCustomBanUnknownInterfaceProp1() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    String js =
        LINE_JOINER.join(
            "/** @interface */ function I() {}",
            "I.prototype.method = function() {};",
            "I.prototype.gak;",
            "/** @param {!I} a */",
            "function f(a) {",
            "  a.gak();",
            "}");

    this.mode = TypeInferenceMode.OTI_ONLY;
    testWarning(
        js,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"gak\" on type \"I\"");

    this.mode = TypeInferenceMode.NTI_ONLY;
    testWarning(
        js,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"gak\" on type \"I{gak: TOP_FUNCTION}\"");
  }

  public void testCustomBanUnknownInterfaceProp2() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testNoWarning(
        LINE_JOINER.join(
            "/** @interface */ function I() {}",
            "I.prototype.method = function() {};",
            "/** @param {I} a */ function f(a) {",
            "  a.method();",
            "}"));
  }

  public void testCustomBanGlobalVars1() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanGlobalVars'\n" +
        "  error_message: 'BanGlobalVars Message'\n" +
        "}";

    testWarning(
        "var x;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanGlobalVars Message");

    testWarning(
        "function fn() {}",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanGlobalVars Message");

    testNoWarning("goog.provide('x');");

    // TODO(johnlenz): This might be overly conservative but doing otherwise is more complicated
    // so let see if we can get away with this.
    testWarning(
        "goog.provide('x'); var x;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanGlobalVars Message");
  }

  public void testCustomBanGlobalVars2() {
    configuration =
        "requirement: {\n"
            + "  type: CUSTOM\n"
            + "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanGlobalVars'\n"
            + "  error_message: 'BanGlobalVars Message'\n"
            + "}";

    testNoWarning(
        "goog.scope(function() {\n"
            + "  var x = {y: 'y'}\n"
            + "  var z = {\n"
            + "     [x.y]: 2\n"
            + "  }\n"
            + "});");
  }

  public void testRequireFileoverviewVisibility() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$" +
                       "RequireFileoverviewVisibility'\n" +
        "  error_message: 'RequireFileoverviewVisibility Message'\n" +
        "}";

    testWarning(
        "var foo = function() {};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: RequireFileoverviewVisibility Message");

    testWarning(
        "/**\n" + "  * @fileoverview\n" + "  */\n" + "var foo = function() {};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: RequireFileoverviewVisibility Message");

    testWarning(
        "/**\n" + "  * @package\n" + "  */\n" + "var foo = function() {};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: RequireFileoverviewVisibility Message");

    testNoWarning(
        "/**\n" +
        "  * @fileoverview\n" +
        "  * @package\n" +
        "  */\n" +
        "var foo = function() {};");
  }

  public void testCustomBanUnresolvedType() {
    configuration =
        "requirement: {\n"
        + "  type: CUSTOM\n"
        + "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnresolvedType'\n"
        + "  error_message: 'BanUnresolvedType Message'\n"
        + "}";

    // NOTE(aravindpg): In NTI we annotate the node `a` with its inferred type instead of Unknown,
    // and so this test doesn't recognize `a` as unresolved. Fixing this is undesirable.
    // However, we do intend to add warnings for unfulfilled forward declares, which essentially
    // addresses this use case.
    this.mode = TypeInferenceMode.OTI_ONLY;
    testWarning(
        "goog.forwardDeclare('Foo'); /** @param {Foo} a */ function f(a) {a.foo()};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanUnresolvedType Message");

    this.mode = TypeInferenceMode.BOTH;
    testNoWarning(LINE_JOINER.join(
        "/** @suppress {newCheckTypes}",
        " *  @param {!Object<string, ?>} data",
        " */",
        "function foo(data) {",
        "  data['bar'].baz();",
        "}"));
  }

  public void testCustomStrictBanUnresolvedType() {
    configuration =
        "requirement: {\n"
        + "  type: CUSTOM\n"
        + "  java_class: 'com.google.javascript.jscomp.ConformanceRules$StrictBanUnresolvedType'\n"
        + "  error_message: 'StrictBanUnresolvedType Message'\n"
        + "}";

    // NTI doesn't model unresolved types separately from unknown, so this check always results
    // in conformance.
    // TODO(b/67899666): Implement similar functionality in NTI for preventing uses of forward
    // declared types by implementing support for resolving forward declarations to a new
    // "unusable type" instead of to unknown.
    this.mode = TypeInferenceMode.OTI_ONLY;
    testWarning(
        "goog.forwardDeclare('Foo'); /** @param {Foo} a */ var f = function(a) {}",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: StrictBanUnresolvedType Message");

    testWarning(
        new String[] {
          "goog.forwardDeclare('Foo'); /** @param {!Foo} a */ var f;",
           "f(5);",
        },
        TypeValidator.TYPE_MISMATCH_WARNING);

    testWarning(
        new String[] {
          "goog.forwardDeclare('Foo'); /** @return {!Foo} */ var f;",
          "f();",
        },
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testMergeRequirements() {
    Compiler compiler = createCompiler();
    ConformanceConfig.Builder builder = ConformanceConfig.newBuilder();
    builder.addRequirementBuilder().setRuleId("a").addWhitelist("x").addWhitelistRegexp("m");
    builder.addRequirementBuilder().setExtends("a").addWhitelist("y").addWhitelistRegexp("n");
    List<Requirement> requirements =
        CheckConformance.mergeRequirements(compiler, ImmutableList.of(builder.build()));
    assertThat(requirements).hasSize(1);
    Requirement requirement = requirements.get(0);
    assertEquals(2, requirement.getWhitelistCount());
    assertEquals(2, requirement.getWhitelistRegexpCount());
  }

  public void testMergeRequirements_findsDuplicates() {
    Compiler compiler = createCompiler();
    ErrorManager errorManager = new BlackHoleErrorManager();
    compiler.setErrorManager(errorManager);
    ConformanceConfig.Builder builder = ConformanceConfig.newBuilder();
    builder.addRequirementBuilder().addWhitelist("x").addWhitelist("x");
    List<Requirement> requirements =
        CheckConformance.mergeRequirements(compiler, ImmutableList.of(builder.build()));
    assertEquals(1, requirements.get(0).getWhitelistCount());
    assertEquals(0, errorManager.getErrorCount());
  }

  public void testCustomBanNullDeref1() {
    configuration = config(rule("BanNullDeref"), "My rule message");

    String externs = EXTERNS + "String.prototype.prop;";

    testWarning(
        externs,
        "/** @param {string|null} n */ function f(n) { alert(n.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testWarning(
        externs,
        "/** @param {string|null} n */ function f(n) { alert(n['prop']); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testWarning(
        externs,
        "/** @param {string|null} n  @suppress {newCheckTypes} */"
        + "function f(n) { alert('prop' in n); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testWarning(
        externs,
        "/** @param {string|undefined} n */ function f(n) { alert(n.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testWarning(
        externs,
        "/** @param {?Function} fnOrNull */ function f(fnOrNull) { fnOrNull(); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testWarning(
        externs,
        "/** @param {?Function} fnOrNull */ function f(fnOrNull) { new fnOrNull(); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testNoWarning(
        externs,
        "/** @param {string} n */ function f(n) { alert(n.prop); }");

    testNoWarning(
        externs,
        "/** @param {?} n */ function f(n) { alert(n.prop); }");
  }

  public void testCustomBanNullDeref2() {
    configuration =
        config(rule("BanNullDeref"), "My rule message");

    String externs = EXTERNS + "String.prototype.prop;";

    final String code = "/** @param {?String} n */ function f(n) { alert(n.prop); }";

    testWarning(
        externs,
        code,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    configuration =
        config(rule("BanNullDeref"), "My rule message", value("String"));

    testNoWarning(externs, code);
  }

  public void testCustomBanNullDeref3() {
    configuration =
        config(rule("BanNullDeref"), "My rule message");

    // Test doesn't run without warnings in NTI because of the way it handles typedefs.
    final String typedefExterns = LINE_JOINER.join(
        EXTERNS,
        "/** @fileoverview @suppress {newCheckTypes} */",
        "/** @const */ var ns = {};",
        "/** @enum {number} */ ns.Type.State = {OPEN: 0};",
        "/** @typedef {{a:string}} */ ns.Type;",
        "");

    final String code = LINE_JOINER.join(
        "/** @suppress {newCheckTypes} @return {void} n */",
        "function f() { alert(ns.Type.State.OPEN); }");
    testNoWarning(typedefExterns, code);
  }

  public void testCustomBanNullDeref4() {
    configuration =
        config(rule("BanNullDeref"), "My rule message");

    testNoWarning(
        LINE_JOINER.join(
            "/** @suppress {newCheckTypes} @param {*} x */",
            "function f(x) {",
            "  return x.toString();",
            "}"));
  }

  public void testRequireUseStrict0() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    testWarning("anything;", CheckConformance.CONFORMANCE_VIOLATION, "Violation: My rule message");
  }

  public void testRequireUseStrictScript() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    testNoWarning("'use strict';");
  }

  public void testRequireUseStrictGoogModule() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    testNoWarning("goog.module('foo');");
  }

  public void testRequireUseStrictEs6Module() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    testNoWarning(
        "export var x = 2;");
  }

  public void testBanCreateElement() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanCreateElement'\n" +
        "  error_message: 'BanCreateElement Message'\n" +
        "  value: 'script'\n" +
        "}";

    testWarning(
        "goog.dom.createElement('script');",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateElement Message");

    testWarning(
        "goog.dom.createDom('script', {});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateElement Message");

    String externs =
        LINE_JOINER.join(
            DEFAULT_EXTERNS,
            "/** @constructor */ function Document() {}",
            "/** @const {!Document} */ var document;",
            "/** @const */ var goog = {};",
            "/** @const */ goog.dom = {};",
            "/** @constructor */ goog.dom.DomHelper = function() {};");

    testWarning(
        externs,
        "document.createElement('script');",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateElement Message");

    testWarning(
        externs,
        "new goog.dom.DomHelper().createElement('script');",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateElement Message");

    testWarning(
        externs,
        "function f(/** ?Document */ doc) { doc.createElement('script'); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateElement Message");

    testNoWarning("goog.dom.createElement('iframe');");
    testNoWarning("goog.dom.createElement(goog.dom.TagName.SCRIPT);");
  }

  public void testBanCreateDom() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanCreateDom'\n" +
        "  error_message: 'BanCreateDom Message'\n" +
        "  value: 'iframe.src'\n" +
        "  value: 'div.class'\n" +
        "}";

    testWarning(
        "goog.dom.createDom('iframe', {'src': src});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('iframe', {'src': src, 'name': ''}, '');",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom(goog.dom.TagName.IFRAME, {'src': src});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('div', 'red');",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('div', ['red']);",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('DIV', ['red']);",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    String externs =
        LINE_JOINER.join(
            DEFAULT_EXTERNS,
            "/** @const */ var goog = {};",
            "/** @const */ goog.dom = {};",
            "/** @constructor */ goog.dom.DomHelper = function() {};");

    testWarning(
        externs,
        "new goog.dom.DomHelper().createDom('iframe', {'src': src});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom(tag, {'src': src});",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('iframe', attrs);",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom(tag, attrs);",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('iframe', x ? {'src': src} : 'class');",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: BanCreateDom Message");

    testNoWarning("goog.dom.createDom('iframe');");
    testNoWarning("goog.dom.createDom('iframe', {'src': ''});");
    testNoWarning("goog.dom.createDom('iframe', {'name': name});");
    testNoWarning("goog.dom.createDom('iframe', 'red' + '');");
    testNoWarning("goog.dom.createDom('iframe', ['red']);");
    testNoWarning("goog.dom.createDom('iframe', undefined);");
    testNoWarning("goog.dom.createDom('iframe', null);");
    testNoWarning("goog.dom.createDom('img', {'src': src});");
    testNoWarning("goog.dom.createDom('img', attrs);");
    testNoWarning(LINE_JOINER.join("goog.dom.createDom(",
        "'iframe', /** @type {?string|!Array|undefined} */ (className));"));
    testNoWarning("goog.dom.createDom(tag, {});");
    testNoWarning(
        "/** @enum {string} */ var Classes = {A: ''};\n" +
        "goog.dom.createDom('iframe', Classes.A);");
  }

  public void testBanCreateDomIgnoreLooseType() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanCreateDom'\n" +
        "  error_message: 'BanCreateDom Message'\n" +
        "  report_loose_type_violations: false\n" +
        "  value: 'iframe.src'\n" +
        "}";

    testWarning(
        "goog.dom.createDom('iframe', {'src': src});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testNoWarning("goog.dom.createDom('iframe', attrs);");
    testNoWarning("goog.dom.createDom(tag, {'src': src});");
  }

  public void testBanCreateDomTagNameType() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanCreateDom'\n" +
        "  error_message: 'BanCreateDom Message'\n" +
        "  value: 'div.class'\n" +
        "}";

    String externs =
        LINE_JOINER.join(
            DEFAULT_EXTERNS,
            "/** @const */ var goog = {};",
            "/** @const */ goog.dom = {};",
            "/** @constructor @template T */ goog.dom.TagName = function() {};",
            "/** @type {!goog.dom.TagName<!HTMLDivElement>} */",
            "goog.dom.TagName.DIV = new goog.dom.TagName();",
            "/** @constructor */ function HTMLDivElement() {}\n");

    testWarning(
        externs,
        LINE_JOINER.join(
            "function f(/** !goog.dom.TagName<!HTMLDivElement> */ div) {",
            "  goog.dom.createDom(div, 'red');",
            "}"),
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        externs,
        LINE_JOINER.join(
            "const TagName = goog.dom.TagName;",
            "goog.dom.createDom(TagName.DIV, 'red');"),
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");
  }

  public void testBanCreateDomMultiType() {
    configuration =
        LINE_JOINER.join(
            "requirement: {",
            "  type: CUSTOM",
            "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanCreateDom'",
            "  error_message: 'BanCreateDom Message'",
            "  value: 'h2.class'",
            "}");

    String externs =
        LINE_JOINER.join(
            DEFAULT_EXTERNS,
            "/** @const */ var goog = {};",
            "/** @const */ goog.dom = {};",
            "/** @constructor @template T */ goog.dom.TagName = function() {}",
            "/** @constructor */ function HTMLHeadingElement() {}\n");

    testWarning(
        externs,
        LINE_JOINER.join(
            "function f(/** !goog.dom.TagName<!HTMLHeadingElement> */ heading) {",
            "  goog.dom.createDom(heading, 'red');",
            "}"),
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");
  }

  public void testBanCreateDomAnyTagName() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanCreateDom'\n" +
        "  error_message: 'BanCreateDom Message'\n" +
        "  value: '*.innerHTML'\n" +
        "}";

    testWarning(
        "goog.dom.createDom('span', {'innerHTML': html});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom(tag, {'innerHTML': html});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('span', attrs);",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: BanCreateDom Message");

    testNoWarning("goog.dom.createDom('span', {'innerHTML': ''});");
    testNoWarning("goog.dom.createDom('span', {'innerhtml': html});");
  }

  public void testBanCreateDomTextContent() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanCreateDom'\n" +
        "  error_message: 'BanCreateDom Message'\n" +
        "  value: 'script.textContent'\n" +
        "}";

    testWarning(
        "goog.dom.createDom('script', {}, source);",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom('script', {'textContent': source});",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanCreateDom Message");

    testWarning(
        "goog.dom.createDom(script, {}, source);",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: BanCreateDom Message");

    testNoWarning("goog.dom.createDom('script', {});");
    testNoWarning("goog.dom.createDom('span', {'textContent': text});");
    testNoWarning("goog.dom.createDom('span', {}, text);");
  }
}

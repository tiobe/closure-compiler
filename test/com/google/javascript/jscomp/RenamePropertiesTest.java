/*
 * Copyright 2005 The Closure Compiler Authors.
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

import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;

/**
 * {@link RenameProperties} tests.
 *
 */

public final class RenamePropertiesTest extends CompilerTestCase {

  private static final String EXTERNS =
      "var window;" +
      "prop.toString;" +
      "var google = { gears: { factory: {}, workerPool: {} } };";

  private RenameProperties renameProperties;
  private boolean generatePseudoNames;
  private VariableMap prevUsedPropertyMap;

  public RenamePropertiesTest() {
    super(EXTERNS);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    generatePseudoNames = false;
    prevUsedPropertyMap = null;
    enableNormalize();
    enableGatherExternProperties();
  }

  @Override
  protected int getNumRepetitions() {
    // The RenameProperties pass should only be run once over a parse tree.
    return 1;
  }

  @Override protected CodingConvention getCodingConvention() {
    return new CodingConventions.Proxy(super.getCodingConvention()) {
      @Override public boolean blockRenamingForProperty(String name) {
        return name.endsWith("Exported");
      }
    };
  }

  public void testPrototypeProperties() {
    test("Bar.prototype.getA = function(){}; bar.getA();" +
         "Bar.prototype.getB = function(){};" +
         "Bar.prototype.getC = function(){};",
         "Bar.prototype.a = function(){}; bar.a();" +
         "Bar.prototype.b = function(){};" +
         "Bar.prototype.c = function(){}");
  }

  public void testExportedByConventionPrototypeProperties() {
    test("Bar.prototype.getA = function(){}; bar.getA();" +
         "Bar.prototype.getBExported = function(){}; bar.getBExported()",
         "Bar.prototype.a = function(){}; bar.a();" +
         "Bar.prototype.getBExported = function(){}; bar.getBExported()");
  }

  public void testPrototypePropertiesAsObjLitKeys1() {
    test("Bar.prototype = {2: function(){}, getA: function(){}}; bar[2]();",
         "Bar.prototype = {2: function(){}, a: function(){}}; bar[2]();");
  }

  public void testPrototypePropertiesAsObjLitKeys2() {
    testSame("Bar.prototype = {get 2(){}}; bar[2];");

    testSame("Bar.prototype = {get 'a'(){}}; bar['a'];");

    test("Bar.prototype = {get getA(){}}; bar.getA;",
         "Bar.prototype = {get a(){}}; bar.a;");
  }

  public void testPrototypePropertiesAsObjLitKeys3() {
    testSame("Bar.prototype = {set 2(x){}}; bar[2];");

    testSame("Bar.prototype = {set 'a'(x){}}; bar['a'];");

    test("Bar.prototype = {set getA(x){}}; bar.getA;",
         "Bar.prototype = {set a(x){}}; bar.a;");
  }

  public void testMixedQuotedAndUnquotedObjLitKeys1() {
    test("Bar = {getA: function(){}, 'getB': function(){}}; bar.getA();",
         "Bar = {a: function(){}, 'getB': function(){}}; bar.a();");
  }

  public void testMixedQuotedAndUnquotedObjLitKeys2() {
    test("Bar = {getA: function(){}, 'getB': function(){}}; bar.getA();",
         "Bar = {a: function(){}, 'getB': function(){}}; bar.a();");
  }

  public void testQuotedPrototypeProperty() {
    testSame("Bar.prototype['getA'] = function(){}; bar['getA']();");
  }

  public void testOverlappingOriginalAndGeneratedNames() {
    test("Bar.prototype = {b: function(){}, a: function(){}}; bar.b();",
         "Bar.prototype = {a: function(){}, b: function(){}}; bar.a();");
  }

  public void testRenamePropertiesWithLeadingUnderscores() {
    test("Bar.prototype = {_getA: function(){}, _b: 0}; bar._getA();",
         "Bar.prototype = {a: function(){}, b: 0}; bar.a();");
  }

  public void testPropertyAddedToObject() {
    test("var foo = {}; foo.prop = '';",
         "var foo = {}; foo.a = '';");
  }

  public void testPropertyAddedToFunction() {
    test("var foo = function(){}; foo.prop = '';",
         "var foo = function(){}; foo.a = '';");
  }

  public void testPropertyOfObjectOfUnknownType() {
    test("var foo = x(); foo.prop = '';",
         "var foo = x(); foo.a = '';");
  }

  public void testSetPropertyOfThis() {
    test("this.prop = 'bar'",
         "this.a = 'bar'");
  }

  public void testExportedSetPropertyOfThis() {
    testSame("this.propExported = 'bar'");
  }

  public void testReadPropertyOfThis() {
    test("f(this.prop);",
         "f(this.a);");
  }

  public void testObjectLiteralInLocalScope() {
    test("function x() { var foo = {prop1: 'bar', prop2: 'baz'}; }",
         "function x() { var foo = {a: 'bar', b: 'baz'}; }");
  }

  public void testExportedObjectLiteralInLocalScope() {
    test("function x() { var foo = {prop1: 'bar', prop2Exported: 'baz'};" +
         " foo.prop2Exported; }",
         "function x() { var foo = {a: 'bar', prop2Exported: 'baz'};" +
         " foo.prop2Exported; }");
  }

  public void testIncorrectAttemptToAccessQuotedProperty() {
    // The correct way to call the quoted 'getFoo' method is: bar['getFoo']().
    test("Bar.prototype = {'B': 0, 'getFoo': function(){}}; bar.getFoo();",
         "Bar.prototype = {'B': 0, 'getFoo': function(){}}; bar.a();");
  }

  public void testSetQuotedPropertyOfThis() {
    testSame("this['prop'] = 'bar';");
  }

  public void testExternedPropertyName() {
    test("Bar.prototype = {toString: function(){}, foo: 0}; bar.toString();",
         "Bar.prototype = {toString: function(){}, a: 0}; bar.toString();");
  }

  public void testExternedPropertyNameDefinedByObjectLiteral() {
    testSame("function x() { var foo = google.gears.factory; }");
  }

  public void testAvoidingConflictsBetweenQuotedAndUnquotedPropertyNames() {
    test("Bar.prototype.foo = function(){}; Bar.prototype['a'] = 0; bar.foo();",
         "Bar.prototype.b = function(){}; Bar.prototype['a'] = 0; bar.b();");
  }

  public void testSamePropertyNameQuotedAndUnquoted() {
    test("Bar.prototype.prop = function(){}; y = {'prop': 0};",
         "Bar.prototype.a = function(){}; y = {'prop': 0};");
  }

  public void testStaticAndInstanceMethodWithSameName() {
    test("Bar = function(){}; Bar.getA = function(){}; " +
         "Bar.prototype.getA = function(){}; Bar.getA(); bar.getA();",
         "Bar = function(){}; Bar.a = function(){}; " +
         "Bar.prototype.a = function(){}; Bar.a(); bar.a();");
  }

  public void testRenamePropertiesFunctionCall1() {
    test("var foo = {myProp: 0}; f(foo[JSCompiler_renameProperty('myProp')]);",
         "var foo = {a: 0}; f(foo['a']);");
  }

  public void testRenamePropertiesFunctionCall2() {
    test("var foo = {myProp: 0}; " +
         "f(JSCompiler_renameProperty('otherProp.myProp.someProp')); " +
         "foo.myProp = 1; foo.theirProp = 2; foo.yourProp = 3;",
         "var foo = {a: 0}; f('b.a.c'); " +
         "foo.a = 1; foo.d = 2; foo.e = 3;");
  }

  public void testRemoveRenameFunctionStubs1() {
    test("function JSCompiler_renameProperty(x) { return x; }",
         "");
  }

  public void testRemoveRenameFunctionStubs2() {
    test("function JSCompiler_renameProperty(x) { return x; }" +
         "var foo = {myProp: 0}; f(foo[JSCompiler_renameProperty('myProp')]);",
         "var foo = {a: 0}; f(foo['a']);");
  }

  public void testGeneratePseudoNames() {
    generatePseudoNames = true;
    test("var foo={}; foo.bar=1; foo['abc']=2",
         "var foo={}; foo.$bar$=1; foo['abc']=2");
  }

  public void testModules() {
    String module1Js = "function Bar(){} Bar.prototype.getA=function(x){};" +
                       "var foo;foo.getA(foo);foo.doo=foo;foo.bloo=foo;";

    String module2Js = "function Far(){} Far.prototype.getB=function(x){};" +
                       "var too;too.getB(too);too.woo=too;too.bloo=too;";

    String module3Js = "function Car(){} Car.prototype.getC=function(x){};" +
                       "var noo;noo.getC(noo);noo.zoo=noo;noo.cloo=noo;";

    JSModule module1 = new JSModule("m1");
    module1.add(SourceFile.fromCode("input1", module1Js));

    JSModule module2 = new JSModule("m2");
    module2.add(SourceFile.fromCode("input2", module2Js));

    JSModule module3 = new JSModule("m3");
    module3.add(SourceFile.fromCode("input3", module3Js));

    JSModule[] modules = new JSModule[] { module1, module2, module3 };
    Compiler compiler = compileModules("", modules);

    Result result = compiler.getResult();
    assertTrue(result.success);

    assertEquals("function Bar(){}Bar.prototype.b=function(x){};" +
                 "var foo;foo.b(foo);foo.f=foo;foo.a=foo;",
                 compiler.toSource(module1));

    assertEquals("function Far(){}Far.prototype.c=function(x){};" +
                 "var too;too.c(too);too.g=too;too.a=too;",
                 compiler.toSource(module2));

    // Note that properties that occur most often globally get the earliest
    // names. The "getC" property, which doesn't occur until module 3, is
    // renamed to an earlier name in the alphabet than "woo", which appears
    // in module 2, because "getC" occurs more total times across all modules.
    // Might be better to give early modules the shortest names, but this is
    // how the pass currently works.
    assertEquals("function Car(){}Car.prototype.d=function(x){};" +
                 "var noo;noo.d(noo);noo.h=noo;noo.e=noo;",
                 compiler.toSource(module3));
  }

  public void testPropertyAffinityOff() {
    test("var foo={};foo.x=1;foo.y=2;foo.z=3;" +
         "function f1() { foo.z; foo.z; foo.z; foo.y}" +
         "function f2() {                      foo.x}",


         "var foo={};foo.b=1;foo.c=2;foo.a=3;" +
         "function f1() { foo.a; foo.a; foo.a; foo.c}" +
         "function f2() {                      foo.b}");

    test("var foo={};foo.x=1;foo.y=2;foo.z=3;" +
        "function f1() { foo.z; foo.z; foo.z; foo.y}" +
        "function f2() { foo.z; foo.z; foo.z; foo.x}",


        "var foo={};foo.b=1;foo.c=2;foo.a=3;" +
        "function f1() { foo.a; foo.a; foo.a; foo.c}" +
        "function f2() { foo.a; foo.a; foo.a; foo.b}");
  }

  public void testPrototypePropertiesStable() {
    testStableRenaming(
        "Bar.prototype.getA = function(){}; bar.getA();" +
        "Bar.prototype.getB = function(){};",
        "Bar.prototype.a = function(){}; bar.a();" +
        "Bar.prototype.b = function(){}",
        "Bar.prototype.get = function(){}; bar.get();" +
        "Bar.prototype.getA = function(){}; bar.getA();" +
        "Bar.prototype.getB = function(){};",
        "Bar.prototype.c = function(){}; bar.c();" +
        "Bar.prototype.a = function(){}; bar.a();" +
        "Bar.prototype.b = function(){}");
  }

  public void testPrototypePropertiesAsObjLitKeysStable() {
    testStableRenaming(
        "Bar.prototype = {2: function(){}, getA: function(){}}; bar[2]();",
        "Bar.prototype = {2: function(){}, a: function(){}}; bar[2]();",
        "Bar.prototype = {getB: function(){},getA: function(){}}; bar.getB();",
        "Bar.prototype = {b: function(){},a: function(){}}; bar.b();");
  }

  public void testMixedQuotedAndUnquotedObjLitKeysStable() {
    testStableRenaming(
        "Bar = {getA: function(){}, 'getB': function(){}}; bar.getA();",
        "Bar = {a: function(){}, 'getB': function(){}}; bar.a();",
        "Bar = {get: function(){}, getA: function(){}, 'getB': function(){}};" +
        "bar.getA();bar.get();",
        "Bar = {b: function(){}, a: function(){}, 'getB': function(){}};" +
        "bar.a();bar.b();");
  }

  public void testOverlappingOriginalAndGeneratedNamesStable() {
    testStableRenaming(
        "Bar.prototype = {b: function(){}, a: function(){}}; bar.b();",
        "Bar.prototype = {a: function(){}, b: function(){}}; bar.a();",
        "Bar.prototype = {c: function(){}, b: function(){}, a: function(){}};" +
        "bar.b();",
        "Bar.prototype = {c: function(){}, a: function(){}, b: function(){}};" +
        "bar.a();");
  }

  public void testStableWithTrickyExternsChanges() {
    test("Bar.prototype = {b: function(){}, a: function(){}}; bar.b();",
         "Bar.prototype = {a: function(){}, b: function(){}}; bar.a();");
    prevUsedPropertyMap = renameProperties.getPropertyMap();
    String externs = EXTERNS + "prop.b;";
    test(externs,
         "Bar.prototype = {new_f: function(){}, b: function(){}, " +
         "a: function(){}};bar.b();",
         "Bar.prototype = {c:function(){}, b:function(){}, a:function(){}};" +
         "bar.b();");
  }

  public void testRenamePropertiesWithLeadingUnderscoresStable() {
    testStableRenaming(
        "Bar.prototype = {_getA: function(){}, _b: 0}; bar._getA();",
        "Bar.prototype = {a: function(){}, b: 0}; bar.a();",
        "Bar.prototype = {_getA: function(){}, _c: 1, _b: 0}; bar._getA();",
        "Bar.prototype = {a: function(){}, c: 1, b: 0}; bar.a();");
  }

  public void testPropertyAddedToObjectStable() {
    testStableRenaming("var foo = {}; foo.prop = '';",
                       "var foo = {}; foo.a = '';",
                       "var foo = {}; foo.prop = ''; foo.a='';",
                       "var foo = {}; foo.a = ''; foo.b='';");
  }

  public void testAvoidingConflictsBetQuotedAndUnquotedPropertyNamesStable() {
    testStableRenaming(
        "Bar.prototype.foo = function(){}; Bar.prototype['b'] = 0; bar.foo();",
        "Bar.prototype.a = function(){}; Bar.prototype['b'] = 0; bar.a();",
        "Bar.prototype.foo = function(){}; Bar.prototype['a'] = 0; bar.foo();",
        "Bar.prototype.b = function(){}; Bar.prototype['a'] = 0; bar.b();");
  }

  public void testRenamePropertiesFunctionCallStable() {
    testStableRenaming(
        "var foo = {myProp: 0}; " +
        "f(JSCompiler_renameProperty('otherProp.myProp.someProp')); " +
        "foo.myProp = 1; foo.theirProp = 2; foo.yourProp = 3;",
        "var foo = {a: 0}; f('b.a.c'); " +
        "foo.a = 1; foo.d = 2; foo.e = 3;",
        "var bar = {newProp: 0}; var foo = {myProp: 0}; " +
        "f(JSCompiler_renameProperty('otherProp.myProp.someProp')); " +
        "foo.myProp = 1; foo.theirProp = 2; foo.yourProp = 3;",
        "var bar = {f: 0}; var foo = {a: 0}; f('b.a.c'); " +
        "foo.a = 1; foo.d = 2; foo.e = 3;");
  }

  private void testStableRenaming(String input1, String expected1,
                                  String input2, String expected2) {
    test(input1, expected1);
    prevUsedPropertyMap = renameProperties.getPropertyMap();
    test(input2, expected2);
  }

  // Test cases added for ES6 Features
  public void testPrototypePropertyForArrowFunction() {
    test(
        "Bar.prototype = {2: () => {}, getA: () => {}}; bar[2]();",
        "Bar.prototype = {2: () => {}, a:    () => {}}; bar[2]();");
  }

  public void testArrayDestructuring() {
    testSame("var [first, second] = someArray");
  }

  public void testDestructuredProperties() {
    // using destructuring shorthand
    test("var {   foo,   bar } = { foo: 1, bar: 2 }", "var { b:foo, a:bar } = {    b:1,    a:2 }");

    // without destructuring shorthand
    test(
        "var { foo:foo, bar:bar } = { foo:1, bar:2 }",
        "var {   b:foo,   a:bar } = {   b:1,   a:2 }");

    test(
        "var foo = { bar: 1, baz: 2 }; var foo1 = foo.bar; var foo2 = foo.baz; ",
        "var foo = {   a: 1,   b: 2 }; var foo1 = foo.a;   var foo2 = foo.b;");
  }

  public void testNestedDestructuringProperties() {
    test(
        "var {outer: {inner}} = {outer: {inner: 'value'}};",
        "var {b: {a: inner}} = {b: {a: 'value'}};");
  }

  public void testComputedPropertyNamesInObjectLit() {
    // TODO (simranarora) A restriction of this pass is that quoted and unquoted property
    // references cannot be mixed.
    test(
        LINE_JOINER.join(
            "var a = {",
            "  ['val' + ++i]: i,",
            "  ['val' + ++i]: i",
            "};",
            "a.val1;"),
        LINE_JOINER.join(
            "var a = {",
            "  ['val' + ++i]: i,", // don't rename here
            "  ['val' + ++i]: i",
            "};",
            "a.a;")); // rename here
  }

  public void testComputedMethodPropertyNamesInClass() {
    // TODO (simranarora) A restriction of this pass is that quoted and unquoted property
    // references cannot be mixed.

    // Concatination for computed property
    test(
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(){}",
            "  ['f'+'oo']() {",
            "    return 1",
            "  }",
            "}",
            "var bar = new Bar()",
            "bar.foo();"),
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(){}",
            "  ['f'+'oo']() {", //don't rename here
            "    return 1",
            "  }",
            "}",
            "var bar = new Bar()",
            "bar.a();")); //rename here

    // Without property concatination
    test(
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(){}",
            "  ['foo']() {",
            "    return 1",
            "  }",
            "}",
            "var bar = new Bar()",
            "bar.foo();"),
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(){}",
            "  ['foo']() {", //don't rename here
            "    return 1",
            "  }",
            "}",
            "var bar = new Bar()",
            "bar.a();")); //rename here
  }

  public void testClasses() {
    // Call class method inside class scope - due to the scoping rules of javascript, the "getA()"
    // call inside of getB() refers to a method getA() in the outer scope and not the getA() method
    // inside the Bar class
    test(
        LINE_JOINER.join(
            "function getA() {};",
            "class Bar {",
            "  constructor(){}",
            "  getA() {",
            "    return 1",
            "  }",
            "  getB(x) {",
            "    getA();",
            "  }",
            "}"),
        LINE_JOINER.join(
            "function getA() {};",
            "class Bar {",
            "  constructor(){}",
            "  a() {",
            "    return 1",
            "  }",
            "  b(x) {",
            "    getA();",
            "  }",
            "}"));

    // Call class method inside class scope - due to the scoping rules of javascript,
    // the "this.getA()" call inside of getB() refers to a method getA() in the Bar class and
    // not the getA() method in the outer scope
    test(
        LINE_JOINER.join(
            "function getA() {};",
            "class Bar {",
            "  constructor(){}",
            "  getA() {",
            "    return 1",
            "  }",
            "  getB(x) {",
            "    this.getA();",
            "  }",
            "}"),
        LINE_JOINER.join(
            "function getA() {};",
            "class Bar {",
            "  constructor(){}",
            "  a() {",
            "    return 1",
            "  }",
            "  b(x) {",
            "    this.a();",
            "  }",
            "}"));

    // Call class method outside class scope
    test(
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(){}",
            "  getB(x) {}",
            "}",
            "var too;",
            "var too = new Bar();",
            "too.getB(too);"),
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(){}",
            "  a(x) {}",
            "}",
            "var too;",
            "var too = new Bar();",
            "too.a(too);"));
  }

  public void testGetSetInClass() {
    test(
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(foo){",
            "    this.foo = foo;",
            "  }",
            "  get foo() {",
            "    return this.foo;",
            "  }",
            "  set foo(x) {",
            "    this.foo = x;",
            "  }",
            "}",
            "var barObj = new Bar();",
            "barObj.foo();",
            "barObj.foo(1);"),
        LINE_JOINER.join(
            "class Bar {",
            "  constructor(foo){",
            "    this.a = foo;",
            "  }",
            "  get a() {",
            "    return this.a;",
            "  }",
            "  set a(x) {",
            "    this.a = x;",
            "  }",
            "}",
            "var barObj = new Bar();",
            "barObj.a();",
            "barObj.a(1);"));
  }

  public void testStaticMethodInClass() {

    test(
        LINE_JOINER.join(
            "class Bar {",
            "  static double(n) {",
            "    return n*2",
            "  }",
            "}",
            "Bar.double(1);"),
        LINE_JOINER.join(
            "class Bar {",
            "  static a(n) {",
            "    return n*2",
            "  }",
            "}",
            "Bar.a(1);"));
  }

  public void testObjectMethodProperty() {
    // ES5 version
    setLanguage(LanguageMode.ECMASCRIPT3, LanguageMode.ECMASCRIPT3);
    test(
        LINE_JOINER.join(
            "var foo = { ",
            "  bar: 1, ",
            "  myFunc: function myFunc() {",
            "    return this.bar",
            "  }",
            "};",
            "foo.myFunc();"),
        LINE_JOINER.join(
            "var foo = { ",
            "  a: 1, ",
            "  b: function myFunc() {",
            "    return this.a",
            "  }",
            "};",
            "foo.b();")
        );

    //ES6 version
    setLanguage(LanguageMode.ECMASCRIPT_2015, LanguageMode.ECMASCRIPT_2015);
    test(
        LINE_JOINER.join(
            "var foo = { ",
            "  bar: 1, ",
            "  myFunc() {",
            "    return this.bar",
            "  }",
            "};",
            "foo.myFunc();"),
        LINE_JOINER.join(
            "var foo = { ",
            "  a: 1, ",
            "  b() {",
            "    return this.a",
            "  }",
            "};",
            "foo.b();")
        );
  }

  private Compiler compileModules(String externs, JSModule[] modules) {
    SourceFile externsInput = SourceFile.fromCode("externs", externs);

    CompilerOptions options = new CompilerOptions();
    options.setPropertyRenaming(PropertyRenamingPolicy.ALL_UNQUOTED);

    Compiler compiler = new Compiler();
    compiler.compileModules(
        ImmutableList.of(externsInput), ImmutableList.copyOf(modules), options);
    return compiler;
  }

  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    return renameProperties =
        new RenameProperties(
            compiler,
            generatePseudoNames,
            prevUsedPropertyMap,
            new DefaultNameGenerator());
  }
}

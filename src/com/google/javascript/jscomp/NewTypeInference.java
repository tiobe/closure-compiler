/*
 * Copyright 2013 The Closure Compiler Authors.
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.javascript.jscomp.CodingConvention.AssertionFunctionSpec;
import com.google.javascript.jscomp.CodingConvention.Bind;
import com.google.javascript.jscomp.CodingConvention.ObjectLiteralCast;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphEdge;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphNode;
import com.google.javascript.jscomp.newtypes.DeclaredFunctionType;
import com.google.javascript.jscomp.newtypes.FunctionType;
import com.google.javascript.jscomp.newtypes.FunctionTypeBuilder;
import com.google.javascript.jscomp.newtypes.JSType;
import com.google.javascript.jscomp.newtypes.JSTypes;
import com.google.javascript.jscomp.newtypes.MismatchInfo;
import com.google.javascript.jscomp.newtypes.NominalType;
import com.google.javascript.jscomp.newtypes.QualifiedName;
import com.google.javascript.jscomp.newtypes.ToStringContext;
import com.google.javascript.jscomp.newtypes.TypeEnv;
import com.google.javascript.jscomp.newtypes.UniqueNameGenerator;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.TypeI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * New type inference algorithm.
 *
 * Main differences from the old type checker:
 * - Infers types for unannotated functions
 * - Stricter with missing-property warnings
 * - Stricter when checking the operands of primitive operators
 * - Requires constants to be typed
 * - Tries to warn about misplaced annotations, rather than silently ignore them
 *
 * @author blickly@google.com (Ben Lickly)
 * @author dimvar@google.com (Dimitris Vardoulakis)
 *
 */
final class NewTypeInference implements CompilerPass {

  static final DiagnosticType MISTYPED_ASSIGN_RHS = DiagnosticType.warning(
      "JSC_NTI_MISTYPED_ASSIGN_RHS",
      "The right side in the assignment is not a subtype of the left side.\n"
      + "{0}");

  static final DiagnosticType INVALID_OPERAND_TYPE = DiagnosticType.warning(
      "JSC_NTI_INVALID_OPERAND_TYPE",
      "Invalid type(s) for operator {0}.\n"
      + "{1}");

  static final DiagnosticType RETURN_NONDECLARED_TYPE = DiagnosticType.warning(
      "JSC_NTI_RETURN_NONDECLARED_TYPE",
      "Returned type does not match declared return type.\n"
      + "{0}");

  static final DiagnosticType INVALID_INFERRED_RETURN_TYPE =
      DiagnosticType.warning(
          "JSC_NTI_INVALID_INFERRED_RETURN_TYPE",
          "Function called in context that expects incompatible type.\n"
          + "{0}");

  static final DiagnosticType INVALID_DECLARED_RETURN_TYPE_OF_GENERATOR_FUNCTION =
      DiagnosticType.warning(
          "JSC_NTI_INVALID_DECLARED_RETURN_TYPE_OF_GENERATOR_FUNCTION",
          "A generator function must return a (supertype of) Generator.\n"
          + "{0}.");

  static final DiagnosticType INVALID_ARGUMENT_TYPE = DiagnosticType.warning(
      "JSC_NTI_INVALID_ARGUMENT_TYPE",
      "Invalid type for parameter {0} of function {1}.\n"
      + "{2}");

  static final DiagnosticType TEMPLATE_ARGUMENT_MISMATCH = DiagnosticType.warning(
      "JSC_NTI_TEMPLATE_ARGUMENT_MISMATCH",
      "Invalid type for the first parameter of tag function {0}.\n"
      + "{1}");

  static final DiagnosticType TEMPLATE_ARGUMENT_MISSING = DiagnosticType.warning(
      "JSC_NTI_TEMPLATE_ARGUMENT_MISSING",
      "A tag function must take at least one argument.\n");

  static final DiagnosticType CROSS_SCOPE_GOTCHA = DiagnosticType.warning(
      "JSC_NTI_CROSS_SCOPE_GOTCHA",
      "Variable {0} typed inconsistently across scopes.\n" +
      "In outer scope : {1}\n" +
      "In inner scope : {2}\n");

  static final DiagnosticType POSSIBLY_INEXISTENT_PROPERTY =
      DiagnosticType.warning(
          "JSC_NTI_POSSIBLY_INEXISTENT_PROPERTY",
          "Property {0} may not be present on {1}.");

  static final DiagnosticType PROPERTY_ACCESS_ON_NONOBJECT =
      DiagnosticType.warning(
          "JSC_NTI_PROPERTY_ACCESS_ON_NONOBJECT",
          "Cannot access property {0} of non-object type {1}.");

  static final DiagnosticType NOT_UNIQUE_INSTANTIATION =
      DiagnosticType.warning(
          "JSC_NTI_NOT_UNIQUE_INSTANTIATION",
          "When instantiating a polymorphic function,"
          + " you can only specify one type for each type variable.\n"
          + " Found {0} types for type variable {1}: {2},\n"
          + " when instantiating type: {3}");

  static final DiagnosticType INVALID_INDEX_TYPE =
      DiagnosticType.warning(
          "JSC_NTI_INVALID_INDEX_TYPE",
          "Invalid type for index.\n"
          + "{0}");

  static final DiagnosticType BOTTOM_INDEX_TYPE =
      DiagnosticType.warning(
          "JSC_NTI_BOTTOM_INDEX_TYPE",
          "This IObject {0} cannot be accessed with a valid type.\n"
          + " Usually the result of a bad union type.\n");

  static final DiagnosticType INVALID_OBJLIT_PROPERTY_TYPE =
      DiagnosticType.warning(
          "JSC_NTI_INVALID_OBJLIT_PROPERTY_TYPE",
          "Invalid type for object-literal property.\n"
          + "{0}");

  static final DiagnosticType FORIN_EXPECTS_OBJECT =
      DiagnosticType.warning(
          "JSC_NTI_FORIN_EXPECTS_OBJECT",
          "For/in expects an object, found type {0}.");

  static final DiagnosticType FORIN_EXPECTS_STRING_KEY =
      DiagnosticType.warning(
          "JSC_NTI_FORIN_EXPECTS_STRING_KEY",
          "For/in creates string keys, but variable has declared type {1}.");

  static final DiagnosticType FOROF_EXPECTS_ITERABLE =
      DiagnosticType.warning(
          "JSC_NTI_FOROF_EXPECTS_ITERABLE", "For/of expects an iterable, found type {0}.");

  static final DiagnosticType MISTYPED_FOROF_ELEMENT_TYPE =
      DiagnosticType.warning(
          "JSC_NTI_MISTYPED_FOROF_ELEMENT_TYPE",
          "Invalid type for for/of element.\n"
          + "{0}");

  static final DiagnosticType CONST_REASSIGNED =
      DiagnosticType.warning(
          "JSC_NTI_CONST_REASSIGNED",
          "Cannot change the value of a constant.");

  static final DiagnosticType CONST_PROPERTY_REASSIGNED =
      DiagnosticType.warning(
          "JSC_NTI_CONST_PROPERTY_REASSIGNED",
          "Cannot change the value of a constant property.");

  static final DiagnosticType CONST_PROPERTY_DELETED =
      DiagnosticType.warning(
        "JSC_NTI_CONSTANT_PROPERTY_DELETED",
        "Constant property {0} cannot be deleted");

  static final DiagnosticType NOT_A_CONSTRUCTOR =
      DiagnosticType.warning(
          "JSC_NTI_NOT_A_CONSTRUCTOR",
          "Expected a constructor but found type {0}.");

  static final DiagnosticType CANNOT_INSTANTIATE_ABSTRACT_CLASS =
      DiagnosticType.warning(
          "JSC_NTI_CANNOT_INSTANTIATE_ABSTRACT_CLASS",
          "Cannot instantiate abstract class {0}.");

  static final DiagnosticType UNDEFINED_SUPER_CLASS =
      DiagnosticType.warning(
          "JSC_UNDEFINED_SUPER_CLASS",
          "Undefined super class for {0}.");

  static final DiagnosticType ASSERT_FALSE =
      DiagnosticType.warning(
          "JSC_NTI_ASSERT_FALSE",
          "Assertion is always false. Please use a throw or fail() instead.");

  static final DiagnosticType UNKNOWN_ASSERTION_TYPE =
      DiagnosticType.warning(
          "JSC_NTI_UNKNOWN_ASSERTION_TYPE",
          "Assert with unknown asserted type.");

  static final DiagnosticType INVALID_THIS_TYPE_IN_BIND =
      DiagnosticType.warning(
          "JSC_NTI_INVALID_THIS_TYPE_IN_BIND",
          "Invalid type for the first argument to bind.\n"
          + "{0}");

  static final DiagnosticType CANNOT_BIND_CTOR =
      DiagnosticType.warning(
          "JSC_NTI_CANNOT_BIND_CTOR",
          "We do not support using .bind on constructor functions.");

  static final DiagnosticType GOOG_BIND_EXPECTS_FUNCTION =
      DiagnosticType.warning(
          "JSC_NTI_GOOG_BIND_EXPECTS_FUNCTION",
          "The first argument to goog.bind/goog.partial must be a function,"
          + " found: {0}");

  static final DiagnosticType BOTTOM_PROP =
      DiagnosticType.warning(
          "JSC_NTI_BOTTOM_PROP",
          "Property {0} of {1} cannot have a valid type."
          + "Maybe the result of a union of incompatible types?");

  static final DiagnosticType INVALID_CAST =
      DiagnosticType.warning("JSC_NTI_INVALID_CAST",
          "invalid cast - the types do not have a common subtype\n" +
          "from: {0}\n" +
          "to  : {1}");

  static final DiagnosticType GLOBAL_THIS = DiagnosticType.warning(
      "JSC_NTI_USED_GLOBAL_THIS",
      "Dangerous use of the global THIS object");

  static final DiagnosticType MISSING_RETURN_STATEMENT =
      DiagnosticType.warning(
          "JSC_NTI_MISSING_RETURN_STATEMENT",
          "Missing return statement. Function expected to return {0}.");

  static final DiagnosticType CONSTRUCTOR_NOT_CALLABLE =
      DiagnosticType.warning(
          "JSC_NTI_CONSTRUCTOR_NOT_CALLABLE",
          "Constructor {0} should be called with the \"new\" keyword");

  static final DiagnosticType ILLEGAL_OBJLIT_KEY =
      DiagnosticType.warning(
          "JSC_NTI_ILLEGAL_OBJLIT_KEY",
          "Illegal key, the object literal is a {0}");

  static final DiagnosticType ILLEGAL_PROPERTY_CREATION =
      DiagnosticType.warning(
          "JSC_NTI_ILLEGAL_PROPERTY_CREATION",
          "Cannot add property {0} to a struct instance after it is constructed.");

  static final DiagnosticType IN_USED_WITH_STRUCT =
      DiagnosticType.warning(
          "JSC_NTI_IN_USED_WITH_STRUCT",
          "Cannot use the IN operator with structs");

  static final DiagnosticType ADDING_PROPERTY_TO_NON_OBJECT =
      DiagnosticType.warning(
          "JSC_NTI_ADDING_PROPERTY_TO_NON_OBJECT",
          "Cannot create property {0} on non-object type {1}.");

  public static final DiagnosticType INEXISTENT_PROPERTY =
      DiagnosticType.warning(
          "JSC_NTI_INEXISTENT_PROPERTY",
          "Property {0} never defined on {1}");

  static final DiagnosticType NOT_CALLABLE =
      DiagnosticType.warning(
          "JSC_NTI_NOT_FUNCTION_TYPE",
          "Cannot call non-function type {0}");

  static final DiagnosticType WRONG_ARGUMENT_COUNT =
      DiagnosticType.warning(
          "JSC_NTI_WRONG_ARGUMENT_COUNT",
          "Function {0}: called with {1} argument(s). " +
          "Function requires at least {2} argument(s){3}.");

  static final DiagnosticType ILLEGAL_PROPERTY_ACCESS =
      DiagnosticType.warning(
          "JSC_NTI_ILLEGAL_PROPERTY_ACCESS",
          "Cannot do {0} access on a {1}");

  static final DiagnosticType UNKNOWN_TYPEOF_VALUE =
      DiagnosticType.warning(
          "JSC_NTI_UNKNOWN_TYPEOF_VALUE",
          "unknown type: {0}");

  static final DiagnosticType UNKNOWN_NAMESPACE_PROPERTY =
      DiagnosticType.warning(
          "JSC_NTI_UNKNOWN_NAMESPACE_PROPERTY",
          "Cannot determine the type of namespace property {0}. "
          + "Maybe a prefix of the property name has been redefined?");

  static final DiagnosticType INCOMPATIBLE_STRICT_COMPARISON =
      DiagnosticType.warning(
          "JSC_INCOMPATIBLE_STRICT_COMPARISON",
          "Cannot perform strict equality / inequality comparisons on incompatible types:\n"
          + "left : {0}\n"
          + "right: {1}");

  static final DiagnosticType ABSTRACT_SUPER_METHOD_NOT_CALLABLE =
      DiagnosticType.warning(
          "JSC_NTI_ABSTRACT_SUPER_METHOD_NOT_CALLABLE",
          "Abstract super method {0} cannot be called");

  static final DiagnosticType REFLECT_CONSTRUCTOR_EXPECTED =
      DiagnosticType.warning(
          "JSC_NTI_REFLECT_CONSTRUCTOR_EXPECTED",
          "Constructor expected as first argument");

  // Not part of ALL_DIAGNOSTICS because it should not be enabled with
  // --jscomp_error=newCheckTypes. It should only be enabled explicitly.

  static final DiagnosticType NULLABLE_DEREFERENCE =
      DiagnosticType.disabled(
          "JSC_NTI_NULLABLE_DEREFERENCE",
          "Attempt to use nullable type {0}.");

  static final DiagnosticType UNKNOWN_EXPR_TYPE =
      DiagnosticType.disabled(
          "JSC_NTI_UNKNOWN_EXPR_TYPE",
          "This {0} expression has the unknown type.");

  static final DiagnosticType YIELD_NONDECLARED_TYPE = DiagnosticType.warning(
      "JSC_NTI_YIELD_NONDECLARED_TYPE",
      "Yielded type does not match declared return type.\n"
      + "{0}");

  static final DiagnosticType YIELD_ALL_EXPECTS_ITERABLE = DiagnosticType.warning(
      "JSC_NTI_YIELD_ALL_EXPECTS_ITERABLE",
      "Expression yield* expects an iterable, found type {0}.");

  static final DiagnosticType CANNOT_USE_UNRESOLVED_TYPE = DiagnosticType.warning(
      "JSC_CANNOT_USE_UNRESOLVED_TYPE",
      "Cannot use unresolved type {0}. Please include the type definition in your application.");

  static final DiagnosticGroup COMPATIBLE_DIAGNOSTICS = new DiagnosticGroup(
      ABSTRACT_SUPER_METHOD_NOT_CALLABLE,
      CANNOT_BIND_CTOR,
      CANNOT_INSTANTIATE_ABSTRACT_CLASS,
      CONST_PROPERTY_DELETED,
      CONST_PROPERTY_REASSIGNED,
      CONST_REASSIGNED,
      REFLECT_CONSTRUCTOR_EXPECTED,
      CONSTRUCTOR_NOT_CALLABLE,
      FORIN_EXPECTS_STRING_KEY,
      FOROF_EXPECTS_ITERABLE,
      MISTYPED_FOROF_ELEMENT_TYPE,
      GLOBAL_THIS,
      GOOG_BIND_EXPECTS_FUNCTION,
      ILLEGAL_OBJLIT_KEY,
      ILLEGAL_PROPERTY_ACCESS,
      ILLEGAL_PROPERTY_CREATION,
      IN_USED_WITH_STRUCT,
      INEXISTENT_PROPERTY,
      INVALID_ARGUMENT_TYPE,
      TEMPLATE_ARGUMENT_MISMATCH,
      TEMPLATE_ARGUMENT_MISSING,
      INVALID_CAST,
      INVALID_INDEX_TYPE,
      INVALID_OBJLIT_PROPERTY_TYPE,
      MISSING_RETURN_STATEMENT,
      MISTYPED_ASSIGN_RHS,
      NOT_A_CONSTRUCTOR,
      NOT_CALLABLE,
      POSSIBLY_INEXISTENT_PROPERTY,
      RETURN_NONDECLARED_TYPE,
      UNKNOWN_ASSERTION_TYPE,
      UNKNOWN_TYPEOF_VALUE,
      WRONG_ARGUMENT_COUNT,
      YIELD_ALL_EXPECTS_ITERABLE,
      INVALID_DECLARED_RETURN_TYPE_OF_GENERATOR_FUNCTION);

  // TODO(dimvar): Check for which of these warnings it makes sense to keep
  // going after warning, e.g., for NOT_UNIQUE_INSTANTIATION, we must instantiate
  // to the join of the types.
  static final DiagnosticGroup NEW_DIAGNOSTICS =
      new DiagnosticGroup(
          ADDING_PROPERTY_TO_NON_OBJECT,
          ASSERT_FALSE,
          BOTTOM_INDEX_TYPE,
          BOTTOM_PROP,
          CANNOT_USE_UNRESOLVED_TYPE,
          CROSS_SCOPE_GOTCHA,
          FORIN_EXPECTS_OBJECT,
          INCOMPATIBLE_STRICT_COMPARISON,
          INVALID_INFERRED_RETURN_TYPE,
          INVALID_OPERAND_TYPE,
          INVALID_THIS_TYPE_IN_BIND,
          NOT_UNIQUE_INSTANTIATION,
          PROPERTY_ACCESS_ON_NONOBJECT,
          UNKNOWN_NAMESPACE_PROPERTY,
          YIELD_NONDECLARED_TYPE);

  public static class WarningReporter {
    AbstractCompiler compiler;
    WarningReporter(AbstractCompiler compiler) { this.compiler = compiler; }

    void add(JSError warning) {
      String filename = warning.node.getSourceFileName();
      // Avoid some warnings in code generated by the ES6 transpilation.
      // TODO(dimvar): typecheck that code properly and remove this.
      if ((filename != null && filename.startsWith(" [synthetic")) || JSType.mockToString) {
        return;
      }
      compiler.report(warning);
    }
  }

  private final WarningReporter warnings;
  private List<TypeMismatch> mismatches;
  private List<TypeMismatch> implicitInterfaceUses;
  private final AbstractCompiler compiler;
  private final CodingConvention convention;
  private TypeTransformation ttlObj;
  private final Map<DiGraphEdge<Node, ControlFlowGraph.Branch>, TypeEnv> envs;
  private final Map<NTIScope, JSType> summaries;
  private final Map<Node, DeferredCheck> deferredChecks;
  private ControlFlowGraph<Node> cfg;
  private NTIScope currentScope;
  // This TypeEnv should be computed once per scope
  private TypeEnv typeEnvFromDeclaredTypes = null;
  // Throws are not connected to the implicit return of the CFG. Record type environments here
  // to use when computing the summary of a function.
  private List<TypeEnv> exitEnvs = null;
  private GlobalTypeInfo symbolTable;
  private JSTypes commonTypes;
  // RETVAL_ID is used when we calculate the summary type of a function
  private static final String RETVAL_ID = "%return";
  // YIELDVAL_ID is used when we calculate the summary type of a generator function
  private static final String YIELDVAL_ID = "%yield";
  private static final String THIS_ID = "this";
  @SuppressWarnings("ConstantField")
  private final String ABSTRACT_METHOD_NAME;
  private final Map<String, AssertionFunctionSpec> assertionFunctionsMap;

  // To avoid creating warning objects for disabled warnings
  private final boolean reportUnknownTypes;
  private final boolean reportNullDeref;

  // Fields used in the compatibility mode
  private final boolean joinTypesWhenInstantiatingGenerics;
  private final boolean allowPropertyOnSubtypes;
  private final boolean areTypeVariablesUnknown;
  // Used in per-library type checking
  private final boolean warnForUnresolvedTypes;

  // Used only for development
  private static boolean showDebuggingPrints = false;
  static boolean measureMem = false;
  private static long peakMem = 0;

  // Used to avoid typing this.commonTypes.TYPENAME everywhere.
  @SuppressWarnings("ConstantField")
  private JSType BOOLEAN;
  @SuppressWarnings("ConstantField")
  private JSType BOTTOM;
  @SuppressWarnings("ConstantField")
  private JSType FALSE_TYPE;
  @SuppressWarnings("ConstantField")
  private JSType FALSY;
  @SuppressWarnings("ConstantField")
  private JSType NULL;
  @SuppressWarnings("ConstantField")
  private JSType NULL_OR_UNDEFINED;
  @SuppressWarnings("ConstantField")
  private JSType NUMBER;
  @SuppressWarnings("ConstantField")
  private JSType NUMBER_OR_STRING;
  @SuppressWarnings("ConstantField")
  private JSType STRING;
  @SuppressWarnings("ConstantField")
  private JSType TOP;
  @SuppressWarnings("ConstantField")
  private JSType TOP_OBJECT;
  @SuppressWarnings("ConstantField")
  private JSType TRUE_TYPE;
  @SuppressWarnings("ConstantField")
  private JSType TRUTHY;
  @SuppressWarnings("ConstantField")
  private JSType UNDEFINED;
  @SuppressWarnings("ConstantField")
  private JSType UNKNOWN;

  NewTypeInference(AbstractCompiler compiler) {
    this.warnings = new WarningReporter(compiler);
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.envs = new LinkedHashMap<>();
    this.summaries = new LinkedHashMap<>();
    this.deferredChecks = new LinkedHashMap<>();
    this.ABSTRACT_METHOD_NAME = convention.getAbstractMethodName();
    this.reportUnknownTypes =
        compiler.getOptions().enables(DiagnosticGroups.REPORT_UNKNOWN_TYPES);
    this.reportNullDeref = compiler.getOptions()
        .enables(DiagnosticGroups.NEW_CHECK_TYPES_ALL_CHECKS);
    assertionFunctionsMap = new LinkedHashMap<>();
    for (AssertionFunctionSpec assertionFunction : convention.getAssertionFunctions()) {
      assertionFunctionsMap.put(
          assertionFunction.getFunctionName(),
          assertionFunction);
    }
    boolean inCompatibilityMode =
        compiler.getOptions().disables(DiagnosticGroups.NEW_CHECK_TYPES_EXTRA_CHECKS);
    this.joinTypesWhenInstantiatingGenerics = inCompatibilityMode;
    this.allowPropertyOnSubtypes = inCompatibilityMode;
    this.areTypeVariablesUnknown = inCompatibilityMode;
    this.warnForUnresolvedTypes = compiler.getOptions().inIncrementalCheckMode();
  }

  @VisibleForTesting // Only used from tests
  public NTIScope processForTesting(Node externs, Node root) {
    process(externs, root);
    return symbolTable.getGlobalScope();
  }

  @Override
  public void process(Node externs, Node root) {
    try {
      this.symbolTable = (GlobalTypeInfo) compiler.getGlobalTypeInfo();
      this.commonTypes = this.symbolTable.getCommonTypes();
      this.ttlObj = new TypeTransformation(compiler, this.symbolTable.getGlobalScope());
      this.mismatches = symbolTable.getMismatches();
      this.implicitInterfaceUses = symbolTable.getImplicitInterfaceUses();

      this.BOOLEAN = this.commonTypes.BOOLEAN;
      this.BOTTOM = this.commonTypes.BOTTOM;
      this.FALSE_TYPE = this.commonTypes.FALSE_TYPE;
      this.FALSY = this.commonTypes.FALSY;
      this.NULL = this.commonTypes.NULL;
      this.NULL_OR_UNDEFINED = this.commonTypes.NULL_OR_UNDEFINED;
      this.NUMBER = this.commonTypes.NUMBER;
      this.NUMBER_OR_STRING = this.commonTypes.NUMBER_OR_STRING;
      this.STRING = this.commonTypes.STRING;
      this.TOP = this.commonTypes.TOP;
      this.TOP_OBJECT = this.commonTypes.getTopObject();
      this.TRUE_TYPE = this.commonTypes.TRUE_TYPE;
      this.TRUTHY = this.commonTypes.TRUTHY;
      this.UNDEFINED = this.commonTypes.UNDEFINED;
      this.UNKNOWN = this.commonTypes.UNKNOWN;

      for (NTIScope scope : symbolTable.getScopes()) {
        analyzeFunction(scope);
        envs.clear();
      }
      for (DeferredCheck check : deferredChecks.values()) {
        check.runCheck(summaries, warnings);
      }
      if (measureMem) {
        System.out.println("Peak mem: " + peakMem + "MB");
      }
    } catch (Exception unexpectedException) {
      String message = unexpectedException.getMessage();
      if (currentScope != null) {
        message += "\nIn scope: " + currentScope;
      }
      this.compiler.throwInternalError(message, unexpectedException);
    }
  }

  static void updatePeakMem() {
    Runtime rt = Runtime.getRuntime();
    long currentUsedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    if (currentUsedMem > peakMem) {
      peakMem = currentUsedMem;
    }
  }

  private static void println(Object ... objs) {
    if (showDebuggingPrints) {
      StringBuilder b = new StringBuilder();
      for (Object obj : objs) {
        b.append(obj);
      }
      System.out.println(b);
    }
  }

  private void registerMismatchAndWarn(JSError error, JSType found, JSType required) {
    // In the old type checker, a type variable is considered unknown, so other types can be
    // used as type variables, and vice versa, without warning. NTI correctly warns.
    // However, we don't want to block disambiguation in these cases. So, to avoid types getting
    // invalidated, we don't register the mismatch. Otherwise, to get good disambiguation,
    // we would have to add casts all over the code base.
    // TODO(dimvar): this can be made safe in the distant future where we have bounded generics
    // *and* we have switched all the unsafe uses of type variables in the code base to use
    // bounded generics.
    if (!found.isTypeVariable() && !required.isTypeVariable()) {
      TypeMismatch.registerMismatch(
          this.mismatches, this.implicitInterfaceUses, found, required, error);
    }
    warnings.add(error);
  }

  private void registerImplicitUses(Node src, JSType from, JSType to) {
    TypeMismatch.recordImplicitInterfaceUses(this.implicitInterfaceUses, src, from, to);
    TypeMismatch.recordImplicitUseOfNativeObject(this.mismatches, src, from, to);
  }

  private TypeEnv getInEnv(DiGraphNode<Node, ControlFlowGraph.Branch> dn) {
    List<DiGraphEdge<Node, ControlFlowGraph.Branch>> inEdges = dn.getInEdges();
    // True for code considered dead in the CFG
    if (inEdges.isEmpty()) {
      return getEntryTypeEnv();
    }
    if (inEdges.size() == 1) {
      return envs.get(inEdges.get(0));
    }

    Set<TypeEnv> envSet = new LinkedHashSet<>();
    for (DiGraphEdge<Node, ControlFlowGraph.Branch> de : inEdges) {
      TypeEnv env = envs.get(de);
      if (env != null) {
        envSet.add(env);
      }
    }
    if (envSet.isEmpty()) {
      return null;
    }
    return TypeEnv.join(envSet);
  }

  private TypeEnv getOutEnv(DiGraphNode<Node, ControlFlowGraph.Branch> dn) {
    List<DiGraphEdge<Node, ControlFlowGraph.Branch>> outEdges = dn.getOutEdges();
    if (outEdges.isEmpty()) {
      // This occurs when visiting a throw in the backward direction.
      checkArgument(dn.getValue().isThrow());
      return this.typeEnvFromDeclaredTypes;
    }
    if (outEdges.size() == 1) {
      return envs.get(outEdges.get(0));
    }
    Set<TypeEnv> envSet = new LinkedHashSet<>();
    for (DiGraphEdge<Node, ControlFlowGraph.Branch> de : outEdges) {
      TypeEnv env = envs.get(de);
      if (env != null) {
        envSet.add(env);
      }
    }
    checkState(!envSet.isEmpty());
    return TypeEnv.join(envSet);
  }

  private TypeEnv setOutEnv(
      DiGraphNode<Node, ControlFlowGraph.Branch> dn, TypeEnv e) {
    for (DiGraphEdge<Node, ControlFlowGraph.Branch> de : dn.getOutEdges()) {
      envs.put(de, e);
    }
    return e;
  }

  // Initialize the type environments on the CFG edges before the FWD analysis.
  private void initEdgeEnvsFwd(TypeEnv entryEnv) {
    envs.clear();

    // For function scopes, add the formal parameters and the free variables
    // from outer scopes to the environment.
    Set<String> nonLocals = new LinkedHashSet<>();
    if (this.currentScope.hasThis()) {
      nonLocals.add(THIS_ID);
    }
    if (this.currentScope.isFunction()) {
      if (this.currentScope.getName() != null) {
        nonLocals.add(this.currentScope.getName());
      }
      nonLocals.addAll(this.currentScope.getOuterVars());
      nonLocals.addAll(this.currentScope.getFormals());
      entryEnv = envPutType(entryEnv, RETVAL_ID, UNDEFINED);
    } else {
      nonLocals.addAll(this.currentScope.getExterns());
    }
    for (String name : nonLocals) {
      JSType declType = this.currentScope.getDeclaredTypeOf(name);
      JSType initType = declType;
      if (initType == null) {
        initType = envGetType(entryEnv, name);
      } else if (this.areTypeVariablesUnknown) {
        initType = initType.substituteGenericsWithUnknown();
      }
      println("Adding non-local ", name,
          " with decltype: ", declType,
          " and inittype: ", initType);
      entryEnv = envPutType(entryEnv, name, initType);
    }

    // For all scopes, add local variables and (local) function definitions
    // to the environment.
    for (String local : this.currentScope.getLocals()) {
      if (!this.currentScope.isFunctionNamespace(local)) {
        entryEnv = envPutType(entryEnv, local, UNDEFINED);
      }
    }
    for (String fnName : this.currentScope.getLocalFunDefs()) {
      entryEnv = envPutType(entryEnv, fnName, getSummaryOfLocalFunDef(fnName));
    }
    println("Keeping env: ", entryEnv);
    setOutEnv(this.cfg.getEntry(), entryEnv);
  }

  private TypeEnv getTypeEnvFromDeclaredTypes() {
    TypeEnv env = new TypeEnv();
    Set<String> varNames = this.currentScope.getOuterVars();
    Set<String> locals = this.currentScope.getLocals();
    varNames.addAll(locals);
    varNames.addAll(this.currentScope.getExterns());
    if (this.currentScope.hasThis()) {
      varNames.add(THIS_ID);
    }
    if (this.currentScope.isFunction()) {
      Node fn = this.currentScope.getRoot();
      if (!this.currentScope.hasThis()
          // Can't use NodeUtil.referencesSuper here because that function is correct only
          // on valid ASTs, but here we may have an invalid AST that contains super inside
          // a function.
          && NodeUtil.containsType(fn.getLastChild(), Token.SUPER, NodeUtil.MATCH_NOT_FUNCTION)) {
        // This function is a static method on some class. To do lookups of the
        // class name, we add the root of the qualified name to the environment.
        Node funNameNode = NodeUtil.getBestLValue(fn);
        Node qnameRoot = NodeUtil.getRootOfQualifiedName(funNameNode);
        checkState(qnameRoot.isName());
        varNames.add(qnameRoot.getString());
      }
      if (this.currentScope.getName() != null) {
        varNames.add(this.currentScope.getName());
      }
      varNames.addAll(this.currentScope.getFormals());
      // In the rare case when there is a local variable named "arguments",
      // this entry will be overwritten in the foreach loop below.
      JSType argumentsType;
      DeclaredFunctionType dft = this.currentScope.getDeclaredTypeForOwnBody();
      if (dft.getOptionalArity() == 0 && dft.hasRestFormals()) {
        argumentsType = dft.getRestFormalsType();
      } else {
        argumentsType = UNKNOWN;
      }
      env = envPutType(env, "arguments",
          commonTypes.getArgumentsArrayType(argumentsType));
    }
    for (String varName : varNames) {
      if (!this.currentScope.isLocalFunDef(varName)) {
        JSType declType = this.currentScope.getDeclaredTypeOf(varName);
        if (declType == null) {
          declType = UNKNOWN;
        } else if (areTypeVariablesUnknown) {
          declType = declType.substituteGenericsWithUnknown();
        }
        env = envPutType(env, varName, declType);
      }
    }
    for (String fnName : this.currentScope.getLocalFunDefs()) {
      env = envPutType(env, fnName, getSummaryOfLocalFunDef(fnName));
    }
    return env;
  }

  private JSType getSummaryOfLocalFunDef(String name) {
    NTIScope fnScope = this.currentScope.getScope(name);
    JSType fnType = summaries.get(fnScope);
    if (fnType != null) {
      return fnType;
    }
    // Functions defined in externs have no summary, so use the declared type
    fnType = this.currentScope.getDeclaredTypeOf(name);
    if (fnType.getFunType() == null) {
      // Can happen when a function defined in externs clashes with a variable
      // defined by a catch block.
      // TODO(dimvar): once we fix scoping for catch blocks, uncomment the
      // precondition below.
      checkState(fnType.isUnknown());
      return this.commonTypes.qmarkFunction();
      // Preconditions.checkState(fnType.getFunType() != null,
      //   "Needed function but found %s", fnType);
    }

    return changeTypeIfFunctionNamespace(fnScope, fnType);
  }

  private void analyzeFunction(NTIScope scope) {
    println("=== Analyzing function: ", scope.getReadableName(), " ===");
    currentScope = scope;
    exitEnvs = new ArrayList<>();
    ControlFlowAnalysis cfa = new ControlFlowAnalysis(compiler, false, false);
    cfa.process(null, scope.getRoot());
    this.cfg = cfa.getCfg();
    println(this.cfg);
    // The size is > 1 when multiple files are compiled
    // Preconditions.checkState(cfg.getEntry().getOutEdges().size() == 1);
    NTIWorkset workset = NTIWorkset.create(this.cfg);
    this.typeEnvFromDeclaredTypes = getTypeEnvFromDeclaredTypes();
    if (scope.isFunction() && scope.hasUndeclaredFormalsOrOuters()) {
      // Ideally, we would like to only set the in-edges of the implicit return
      // rather than all edges. However, we cannot do that because of a bug in
      // workset construction. (The test testBadWorksetConstruction would fail.)
      // In buildWorksetHelper, if a loop contains break, we add the FOLLOW node
      // of the loop before adding the loop header twice. So, the 2nd addition
      // of the loop header has no effect. We should fix workset creation
      // (eg, by putting edges instead of nodes in seen, or some other way that
      // correctly waits for all incoming edges).
      for (DiGraphEdge<Node, ControlFlowGraph.Branch> e : this.cfg.getEdges()) {
        envs.put(e, this.typeEnvFromDeclaredTypes);
      }
      analyzeFunctionBwd(workset);
      // TODO(dimvar): Revisit what we throw away after the bwd analysis
      TypeEnv entryEnv = getEntryTypeEnv();
      initEdgeEnvsFwd(entryEnv);
      if (measureMem) {
        updatePeakMem();
      }
    } else {
      TypeEnv entryEnv = this.typeEnvFromDeclaredTypes;
      initEdgeEnvsFwd(entryEnv);
    }
    this.typeEnvFromDeclaredTypes = null;
    analyzeFunctionFwd(workset);
    if (scope.isFunction()) {
      createSummary(scope);
    }
    if (measureMem) {
      updatePeakMem();
    }
  }

  private void analyzeFunctionBwd(NTIWorkset workset) {
    for (DiGraphNode<Node, ControlFlowGraph.Branch> dn : workset.backward()) {
      Node n = dn.getValue();
      TypeEnv outEnv = checkNotNull(getOutEnv(dn));
      TypeEnv inEnv;
      println("\tBWD Statment: ", n);
      println("\t\toutEnv: ", outEnv);
      switch (n.getToken()) {
        case EXPR_RESULT:
          inEnv = analyzeExprBwd(n.getFirstChild(), outEnv, UNKNOWN).env;
          break;
        case RETURN: {
          Node retExp = n.getFirstChild();
          if (retExp == null) {
            inEnv = outEnv;
          } else {
            JSType declRetType = this.currentScope.getDeclaredTypeForOwnBody().getReturnType();
            declRetType = firstNonNull(declRetType, UNKNOWN);
            inEnv = analyzeExprBwd(retExp, outEnv, declRetType).env;
          }
          break;
        }
        case VAR: {
          if (NodeUtil.isTypedefDecl(n)) {
            inEnv = outEnv;
            break;
          }
          inEnv = outEnv;
          for (Node nameNode = n.getFirstChild(); nameNode != null;
               nameNode = nameNode.getNext()) {
            String varName = nameNode.getString();
            Node rhs = nameNode.getFirstChild();
            JSType declType = this.currentScope.getDeclaredTypeOf(varName);
            inEnv = envPutType(inEnv, varName, UNKNOWN);
            if (rhs == null || this.currentScope.isLocalFunDef(varName)) {
              continue;
            }
            JSType inferredType = envGetType(outEnv, varName);
            JSType requiredType;
            if (declType == null) {
              requiredType = inferredType;
            } else {
              // TODO(dimvar): look if the meet is needed
              requiredType = JSType.meet(declType, inferredType);
              requiredType = firstNonBottom(requiredType, UNKNOWN);
            }
            inEnv = analyzeExprBwd(rhs, inEnv, requiredType).env;
          }
          break;
        }
        case BLOCK:
        case ROOT:
        case BREAK:
        case CATCH:
        case CONTINUE:
        case DEFAULT_CASE:
        case DEBUGGER:
        case EMPTY:
        case SCRIPT:
        case TRY:
        case WITH:
          inEnv = outEnv;
          break;
        case DO:
        case FOR:
        case FOR_IN:
        case FOR_OF:
        case IF:
        case WHILE:
          Node expr =
              (n.isForIn() || n.isForOf()) ? n.getFirstChild() : NodeUtil.getConditionExpression(n);
          inEnv = analyzeExprBwd(expr, outEnv).env;
          break;
        case THROW:
        case CASE:
        case SWITCH:
          inEnv = analyzeExprBwd(n.getFirstChild(), outEnv).env;
          break;
        default:
          if (NodeUtil.isStatement(n)) {
            throw new RuntimeException("Unhandled statement type: " + n.getToken());
          } else {
            inEnv = analyzeExprBwd(n, outEnv).env;
            break;
          }
      }
      println("\t\tinEnv: ", inEnv);
      for (DiGraphEdge<Node, ControlFlowGraph.Branch> de : dn.getInEdges()) {
        envs.put(de, inEnv);
      }
    }
  }

  private void analyzeFunctionFwd(NTIWorkset workset) {
    for (DiGraphNode<Node, ControlFlowGraph.Branch> dn : workset.forward()) {
      Node n = dn.getValue();
      Node parent = n.getParent();
      checkState(n != null, "Implicit return should not be in workset.");
      TypeEnv inEnv = getInEnv(dn);
      TypeEnv outEnv = null;
      if (parent.isScript()
          || (parent.isNormalBlock() && parent.getParent().isFunction())) {
        // All joins have merged; forget changes
        inEnv = inEnv.clearChangeLog();
      }

      println("\tFWD Statment: ", n);
      println("\t\tinEnv: ", inEnv);
      boolean conditional = false;
      switch (n.getToken()) {
        case BLOCK:
        case ROOT:
        case BREAK:
        case CONTINUE:
        case DEFAULT_CASE:
        case DEBUGGER:
        case EMPTY:
        case FUNCTION:
        case SCRIPT:
        case TRY:
        case WITH: // We don't typecheck WITH, we just avoid crashing.
          outEnv = inEnv;
          break;
        case CATCH:
          Node catchVar = n.getFirstChild();
          String catchVarname = catchVar.getString();
          outEnv = envPutType(inEnv, catchVarname, UNKNOWN);
          maybeSetTypeI(catchVar, UNKNOWN);
          break;
        case EXPR_RESULT:
          println("\tsemi ", n.getFirstChild().getToken());
          if (n.getBooleanProp(Node.ANALYZED_DURING_GTI)) {
            n.removeProp(Node.ANALYZED_DURING_GTI);
            outEnv = inEnv;
          } else {
            outEnv = analyzeExprFwd(n.getFirstChild(), inEnv, UNKNOWN).env;
          }
          break;
        case RETURN:
          outEnv = analyzeReturnFwd(n, inEnv);
          break;
        case DO:
        case IF:
        case FOR:
        case WHILE:
          conditional = true;
          analyzeConditionalStmFwd(dn, NodeUtil.getConditionExpression(n), inEnv);
          break;
        case FOR_IN:
          outEnv = analyzeForInFwd(n, inEnv);
          break;
        case FOR_OF:
          outEnv = analyzeForOfFwd(n, inEnv);
          break;
        case CASE: {
          conditional = true;
          // See analyzeExprFwd#Token.CASE for how to handle this precisely
          analyzeConditionalStmFwd(dn, n, inEnv);
          break;
        }
        case VAR:
          outEnv = inEnv;
          if (NodeUtil.isTypedefDecl(n)) {
            maybeSetTypeI(n.getFirstChild(), UNDEFINED);
            break;
          }
          for (Node nameNode : n.children()) {
            outEnv = analyzeVarDeclFwd(nameNode, outEnv);
          }
          break;
        case SWITCH:
          outEnv = analyzeExprFwd(n.getFirstChild(), inEnv).env;
          break;
        case THROW: {
          outEnv = analyzeExprFwd(n.getFirstChild(), inEnv).env;
          exitEnvs.add(outEnv);
          break;
        }
        default:
          if (NodeUtil.isStatement(n)) {
            throw new RuntimeException("Unhandled statement type: " + n.getToken());
          } else {
            outEnv = analyzeExprFwd(n, inEnv, UNKNOWN).env;
            break;
          }
      }

      if (!conditional) {
        println("\t\toutEnv: ", outEnv);
        setOutEnv(dn, outEnv);
      }
    }
  }

  private void analyzeConditionalStmFwd(
      DiGraphNode<Node, ControlFlowGraph.Branch> stm, Node cond, TypeEnv inEnv) {
    for (DiGraphEdge<Node, ControlFlowGraph.Branch> outEdge :
        stm.getOutEdges()) {
      JSType specializedType;
      switch (outEdge.getValue()) {
        case ON_TRUE:
          specializedType = TRUTHY;
          break;
        case ON_FALSE:
          specializedType = FALSY;
          break;
        case ON_EX:
          specializedType = UNKNOWN;
          break;
        default:
          throw new RuntimeException(
              "Condition with an unexpected edge type: " + outEdge.getValue());
      }
      envs.put(outEdge,
          analyzeExprFwd(cond, inEnv, UNKNOWN, specializedType).env);
    }
  }

  private TypeEnv analyzeReturnFwd(Node n, TypeEnv inEnv) {
    if (this.currentScope.getRoot().isGeneratorFunction()) {
      JSType declRetType =
          getDeclaredReturnTypeOfCurrentScope(this.commonTypes.getGeneratorInstance(UNKNOWN));
      if (n.hasChildren()) {
        EnvTypePair retPair = analyzeExprFwd(n.getFirstChild(), inEnv, UNKNOWN);
        return envPutType(retPair.env, RETVAL_ID, declRetType);
      }
      return envPutType(inEnv, RETVAL_ID, declRetType);
    }
    TypeEnv outEnv;
    JSType declRetType = getDeclaredReturnTypeOfCurrentScope(UNKNOWN);
    JSType actualRetType;
    Node retExp = n.getFirstChild();
    if (retExp == null) {
      actualRetType = UNDEFINED;
      outEnv = envPutType(inEnv, RETVAL_ID, actualRetType);
    } else {
      EnvTypePair retPair = analyzeExprFwd(retExp, inEnv, declRetType);
      actualRetType = retPair.type;
      outEnv = envPutType(retPair.env, RETVAL_ID, actualRetType);
    }
    if (!actualRetType.isSubtypeOf(declRetType)) {
      registerMismatchAndWarn(JSError.make(
          n, RETURN_NONDECLARED_TYPE, errorMsgWithTypeDiff(declRetType, actualRetType)),
          actualRetType, declRetType);
    }
    return outEnv;
  }

  private TypeEnv analyzeForInFwd(Node n, TypeEnv inEnv) {
    Node obj = n.getSecondChild();
    EnvTypePair pair = analyzeExprFwd(obj, inEnv, pickReqObjType(n));
    pair = mayWarnAboutNullableReferenceAndTighten(n, pair.type, null, inEnv);
    JSType objType = pair.type;
    if (!objType.isSubtypeOf(TOP_OBJECT)) {
      warnings.add(JSError.make(obj, FORIN_EXPECTS_OBJECT, objType.toString()));
    } else if (objType.isStruct()) {
      warnings.add(JSError.make(obj, IN_USED_WITH_STRUCT));
    }
    Node lhs = n.getFirstChild();
    LValueResultFwd lval = analyzeLValueFwd(lhs, inEnv, STRING);
    TypeEnv outEnv;
    if (lval.declType != null && !commonTypes.isStringScalarOrObj(lval.declType)) {
      warnings.add(JSError.make(lhs, FORIN_EXPECTS_STRING_KEY, lval.declType.toString()));
      outEnv = lval.env;
    } else {
      outEnv = updateLvalueTypeInEnv(lval.env, lhs, lval.ptr, STRING);
    }
    return outEnv;
  }

  private TypeEnv analyzeForOfFwd(Node n, TypeEnv inEnv) {
    Node rhs = n.getSecondChild();
    EnvTypePair rhsPair = analyzeExprFwd(rhs, inEnv, pickReqObjType(n));
    rhsPair = mayWarnAboutNullableReferenceAndTighten(n, rhsPair.type, null, inEnv);
    JSType rhsObjType = rhsPair.type;
    JSType boxedType = rhsObjType.autobox();
    JSType lhsExpectedType;
    JSType iterable = this.commonTypes.getIterableInstance(UNKNOWN);
    if (boxedType.isSubtypeOf(iterable)) {
      lhsExpectedType = boxedType.getInstantiatedTypeArgument(iterable);
    } else {
      warnings.add(JSError.make(rhs, FOROF_EXPECTS_ITERABLE, rhsObjType.toString()));
      lhsExpectedType = UNKNOWN;
    }
    Node lhsNode = n.getFirstChild();
    LValueResultFwd lhsLval = analyzeLValueFwd(lhsNode, inEnv, lhsExpectedType);
    TypeEnv outEnv;
    if (lhsLval.declType == null || lhsExpectedType.isSubtypeOf(lhsLval.declType)) {
      outEnv = updateLvalueTypeInEnv(lhsLval.env, lhsNode, lhsLval.ptr, lhsExpectedType);
    } else {
      registerMismatchAndWarn(
          JSError.make(
              n, MISTYPED_FOROF_ELEMENT_TYPE,
              errorMsgWithTypeDiff(lhsLval.declType, lhsExpectedType)),
          lhsExpectedType,
          lhsLval.declType);
      outEnv = updateLvalueTypeInEnv(lhsLval.env, lhsNode, lhsLval.ptr, lhsLval.declType);
    }
    return outEnv;
  }

  private void createSummary(NTIScope fn) {
    Node fnRoot = fn.getRoot();
    checkArgument(!fnRoot.isFromExterns());
    FunctionTypeBuilder builder = new FunctionTypeBuilder(this.commonTypes);
    TypeEnv entryEnv = getEntryTypeEnv();
    TypeEnv exitEnv = getExitTypeEnv();

    DeclaredFunctionType declType = fn.getDeclaredFunctionType();
    int reqArity = declType.getRequiredArity();
    int optArity = declType.getOptionalArity();
    if (declType.isGeneric()) {
      builder.addTypeParameters(declType.getTypeParameters());
    }

    // Every trailing undeclared formal whose inferred type is ?
    // or contains undefined can be marked as optional.
    List<String> formals = fn.getFormals();
    for (int i = reqArity - 1; i >= 0; i--) {
      JSType formalType = declType.getFormalType(i);
      if (formalType != null) {
        break;
      }
      String formalName = formals.get(i);
      formalType = getTypeAfterFwd(formalName, entryEnv, exitEnv);
      if (formalType.isUnknown() || UNDEFINED.isSubtypeOf(formalType)) {
        reqArity--;
      } else {
        break;
      }
    }

    // Collect types of formals in the builder
    int i = 0;
    for (String formalName : formals) {
      JSType formalType = declType.getFormalType(i);
      if (formalType == null) {
        formalType = getTypeAfterFwd(formalName, entryEnv, exitEnv);
      }
      if (i < reqArity) {
        builder.addReqFormal(formalType);
      } else if (i < optArity) {
        builder.addOptFormal(formalType);
      }
      i++;
    }
    if (declType.hasRestFormals()) {
      builder.addRestFormals(declType.getFormalType(i));
    }

    for (String outer : fn.getOuterVars()) {
      println("Free var ", outer, " going in summary");
      builder.addOuterVarPrecondition(outer, envGetType(entryEnv, outer));
    }

    builder.addNominalType(declType.getNominalType());
    builder.addReceiverType(declType.getReceiverType());
    builder.addAbstract(declType.isAbstract());
    addRetTypeAndWarn(fn, exitEnv, declType, builder);

    JSType summary = commonTypes.fromFunctionType(builder.buildFunction());
    println("Function summary for ", fn.getReadableName());
    println("\t", summary);
    summary = changeTypeIfFunctionNamespace(fn, summary);
    summaries.put(fn, summary);
    maybeSetTypeI(fnRoot, summary);
    Node fnNameNode = NodeUtil.getNameNode(fnRoot);
    if (fnNameNode != null) {
      maybeSetTypeI(fnNameNode, summary);
    }
  }

  private void addRetTypeAndWarn(
      NTIScope fn, TypeEnv exitEnv, DeclaredFunctionType declType, FunctionTypeBuilder builder) {
    Node fnRoot = fn.getRoot();
    JSType declRetType = declType.getReturnType();
    JSType actualRetType = checkNotNull(envGetType(exitEnv, RETVAL_ID));

    if (declRetType != null) {
      if (fnRoot.isGeneratorFunction()) {
        JSType generator = this.commonTypes.getGeneratorInstance(UNKNOWN);
        if (!generator.isSubtypeOf(declRetType)) {
          registerMismatchAndWarn(
              JSError.make(fnRoot, INVALID_DECLARED_RETURN_TYPE_OF_GENERATOR_FUNCTION,
                  errorMsgWithTypeDiff(generator, declRetType)),
              declRetType, generator);
          builder.addRetType(UNKNOWN);
        } else {
          builder.addRetType(declRetType);
        }
      } else {
        builder.addRetType(declRetType);
        if (!isAllowedToNotReturn(fn)
            && !UNDEFINED.isSubtypeOf(declRetType)
            && hasPathWithNoReturn(this.cfg)) {
          warnings.add(JSError.make(
              fnRoot, MISSING_RETURN_STATEMENT, declRetType.toString()));
        }
      }
    } else if (fnRoot.isGeneratorFunction()) {
      // No declared return type for Generator. Use inferred type.
      JSType yieldType = envGetType(exitEnv, YIELDVAL_ID);
      builder.addRetType(this.commonTypes.getGeneratorInstance(firstNonNull(yieldType, UNKNOWN)));
    } else if (declType.getNominalType() == null) {
      // If a function doesn't return, make the return type unknown.
      builder.addRetType(firstNonBottom(actualRetType, UNKNOWN));
    } else {
      // Don't infer a return type for constructors. We want to warn for
      // constructors called without new who don't explicitly declare @return.
      builder.addRetType(UNDEFINED);
    }
  }

  private JSType changeTypeIfFunctionNamespace(NTIScope fnScope, JSType fnType) {
    NTIScope enclosingScope = fnScope.getParent();
    Node fnNameNode = NodeUtil.getNameNode(fnScope.getRoot());
    JSType namespaceType = null;
    if (fnNameNode == null) {
      return fnType;
    }
    if (fnNameNode.isName()) {
      String fnName = fnNameNode.getString();
      if (enclosingScope.isFunctionNamespace(fnName)) {
        namespaceType = enclosingScope.getDeclaredTypeOf(fnName);
      }
    } else if (fnNameNode.isQualifiedName()) {
      QualifiedName qname = QualifiedName.fromNode(fnNameNode);
      JSType rootNs = enclosingScope.getDeclaredTypeOf(qname.getLeftmostName());
      if (rootNs != null && rootNs.isSubtypeOf(TOP_OBJECT)) {
        namespaceType = rootNs.getProp(qname.getAllButLeftmost());
      }
    }
    if (namespaceType != null && namespaceType.isNamespace()) {
      // Replace the less-precise declared function type
      // with the new function summary.
      return namespaceType.withFunction(
          fnType.getFunTypeIfSingletonObj(), commonTypes.getFunctionType());
    }
    return fnType;
  }

  // TODO(dimvar): To get the adjusted end-of-fwd type for objs, we must be
  // able to know whether a property was added during the evaluation of the
  // function, or was on the object already.
  private JSType getTypeAfterFwd(
      String varName, TypeEnv entryEnv, TypeEnv exitEnv) {
    JSType typeAfterBwd = envGetType(entryEnv, varName);
    if (!typeAfterBwd.hasNonScalar() || typeAfterBwd.getFunType() != null) {
      // The type of a formal after fwd is more precise than the type after bwd,
      // so we use typeAfterFwd in the summary.
      // Trade-off: If the formal is assigned in the body of a function, and the
      // new value has a different type, we compute a wrong summary.
      // Since this is rare, we prefer typeAfterFwd to typeAfterBwd.
      JSType typeAfterFwd = envGetType(exitEnv, varName);
      if (typeAfterFwd != null) {
        return typeAfterFwd;
      }
    }
    return typeAfterBwd;
  }

  private static boolean isAllowedToNotReturn(NTIScope methodScope) {
    Node fn = methodScope.getRoot();
    if (fn.isFromExterns()) {
      return true;
    }
    DeclaredFunctionType declFn = methodScope.getDeclaredFunctionType();
    if (declFn != null && declFn.isAbstract() && declFn.getReceiverType() != null) {
      return true;
    }
    if (!NodeUtil.isPrototypeMethod(fn)) {
      return false;
    }
    JSType maybeInterface;
    Node ntQnameNode = NodeUtil.getPrototypeClassName(fn.getParent().getFirstChild());
    if (ntQnameNode.isName()) {
      maybeInterface = methodScope.getDeclaredTypeOf(ntQnameNode.getString());
    } else {
      QualifiedName ntQname = QualifiedName.fromNode(ntQnameNode);
      JSType rootNamespace = methodScope.getDeclaredTypeOf(ntQname.getLeftmostName());
      maybeInterface = rootNamespace == null
          ? null : rootNamespace.getProp(ntQname.getAllButLeftmost());
    }
    return maybeInterface != null && maybeInterface.isInterfaceDefinition();
  }

  private static boolean hasPathWithNoReturn(ControlFlowGraph<Node> cfg) {
    for (DiGraphNode<Node, ControlFlowGraph.Branch> dn :
             cfg.getDirectedPredNodes(cfg.getImplicitReturn())) {
      Node stm = dn.getValue();
      if (NodeUtil.isLoopStructure(stm)) {
        Node cond = NodeUtil.getConditionExpression(stm);
        if (!(cond != null && NodeUtil.isImpureTrue(cond))) {
          return true;
        }
      } else if (stm.isBreak()) {
        // Allow break after return in switches.
        if (!cfg.getDirectedPredNodes(dn).isEmpty()) {
          return true;
        }
      } else if (!stm.isReturn()) {
        return true;
      }
    }
    return false;
  }

  private JSType getDeclaredReturnTypeOfCurrentScope(JSType defaultType) {
    JSType declRetType = this.currentScope.getDeclaredTypeForOwnBody().getReturnType();
    if (declRetType == null) {
      declRetType = defaultType;
    } else if (this.areTypeVariablesUnknown) {
      declRetType = declRetType.substituteGenericsWithUnknown();
    }
    return declRetType;
  }

  /**
   * This method processes a single variable declaration in a VAR statement, in the forward
   * phase of the analysis.
   */
  private TypeEnv analyzeVarDeclFwd(Node nameNode, TypeEnv inEnv) {
    String varName = nameNode.getString();
    JSType declType = this.currentScope.getDeclaredTypeOf(varName);

    if (this.currentScope.isLocalFunDef(varName)) {
      return inEnv;
    }
    Node rhs = nameNode.getFirstChild();
    if (NodeUtil.isNamespaceDecl(nameNode)
        || (GlobalTypeInfoCollector.isCtorDefinedByCall(nameNode)
            && !isFunctionBind(rhs.getFirstChild(), inEnv, true))
        || nameNode.getParent().getBooleanProp(Node.ANALYZED_DURING_GTI)) {
      Preconditions.checkNotNull(declType,
          "Can't skip var declaration with undeclared type at: %s", nameNode);
      maybeSetTypeI(nameNode, declType);
      maybeSetTypeI(rhs, declType);
      return envPutType(inEnv, varName, declType);
    }

    TypeEnv outEnv = inEnv;
    JSType rhsType = null;
    if (rhs != null) {
      EnvTypePair pair = analyzeExprFwd(rhs, inEnv, firstNonNull(declType, UNKNOWN));
      outEnv = pair.env;
      rhsType = pair.type;
      if (declType != null) {
        if (rhsType.isSubtypeOf(declType)) {
          registerImplicitUses(rhs, rhsType, declType);
        } else {
          registerMismatchAndWarn(
              JSError.make(rhs, MISTYPED_ASSIGN_RHS, errorMsgWithTypeDiff(declType, rhsType)),
              rhsType, declType);
        }
      }
    }
    JSType varType = rhsType;
    if (rhs == null) {
      varType = UNDEFINED;
    } else if (declType != null) {
      // If the declared type comes from a @const that was inferred during GTI, don't use here.
      JSDocInfo jsdoc = NodeUtil.getBestJSDocInfo(nameNode);
      if (jsdoc != null && (!jsdoc.hasConstAnnotation() || jsdoc.hasType())) {
        varType = declType;
      }
    }
    maybeSetTypeI(nameNode, varType);
    return envPutType(outEnv, varName, varType);
  }

  /**
   * Analyze the expression just to annotate its nodes with types, and give warnings;
   * we don't need the result.
   */
  private void analyzeExprFwdIgnoreResult(Node expr, TypeEnv inEnv) {
    analyzeExprFwd(expr, inEnv);
  }

  private EnvTypePair analyzeExprFwd(Node expr, TypeEnv inEnv) {
    return analyzeExprFwd(expr, inEnv, UNKNOWN, UNKNOWN);
  }

  private EnvTypePair analyzeExprFwd(
      Node expr, TypeEnv inEnv, JSType requiredType) {
    return analyzeExprFwd(expr, inEnv, requiredType, requiredType);
  }

  /**
   * @param requiredType The context requires this type; warn if the expression
   *                     doesn't have this type.
   * @param specializedType Used in boolean contexts to infer types of names.
   *
   * Invariant: specializedType is a subtype of requiredType.
   */
  private EnvTypePair analyzeExprFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    checkArgument(requiredType != null && !requiredType.isBottom());
    EnvTypePair resultPair = null;
    switch (expr.getToken()) {
      case EMPTY: // can be created by a FOR with empty condition
        resultPair = new EnvTypePair(inEnv, UNKNOWN);
        break;
      case FUNCTION: {
        String fnName = symbolTable.getFunInternalName(expr);
        JSType fnType = envGetType(inEnv, fnName);
        Preconditions.checkState(fnType != null, "Could not find type for %s", fnName);
        TypeEnv outEnv = collectTypesForEscapedVarsFwd(expr, inEnv);
        resultPair = new EnvTypePair(outEnv, fnType);
        break;
      }
      case FALSE:
      case NULL:
      case NUMBER:
      case STRING:
      case TRUE:
        resultPair = new EnvTypePair(inEnv, scalarValueToType(expr.getToken()));
        break;
      case OBJECTLIT:
        resultPair = analyzeObjLitFwd(expr, inEnv, requiredType, specializedType);
        break;
      case THIS: {
        resultPair = analyzeThisFwd(expr, inEnv, requiredType, specializedType);
        break;
      }
      case SUPER: {
        resultPair = analyzeSuperFwd(expr, inEnv);
        break;
      }
      case NAME:
        resultPair = analyzeNameFwd(expr, inEnv, requiredType, specializedType);
        break;
      case AND:
      case OR:
        resultPair = analyzeLogicalOpFwd(expr, inEnv, requiredType, specializedType);
        break;
      case INC:
      case DEC:
        resultPair = analyzeIncDecFwd(expr, inEnv, requiredType);
        break;
      case BITNOT:
      case NEG:
        resultPair = analyzeUnaryNumFwd(expr, inEnv);
        break;
      case POS: {
        // We are more permissive with +, because it is used to coerce to number
        resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv);
        resultPair.type = NUMBER;
        break;
      }
      case TYPEOF: {
        resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv);
        resultPair.type = STRING;
        break;
      }
      case INSTANCEOF:
        resultPair = analyzeInstanceofFwd(expr, inEnv, specializedType);
        break;
      case ADD:
        resultPair = analyzeAddFwd(expr, inEnv, requiredType);
        break;
      case BITOR:
      case BITAND:
      case BITXOR:
      case DIV:
      case EXPONENT:
      case LSH:
      case MOD:
      case MUL:
      case RSH:
      case SUB:
      case URSH:
        resultPair = analyzeBinaryNumericOpFwd(expr, inEnv);
        break;
      case ASSIGN:
        resultPair = analyzeAssignFwd(expr, inEnv, requiredType, specializedType);
        break;
      case ASSIGN_ADD:
        resultPair = analyzeAssignAddFwd(expr, inEnv, requiredType);
        break;
      case ASSIGN_BITOR:
      case ASSIGN_BITXOR:
      case ASSIGN_BITAND:
      case ASSIGN_LSH:
      case ASSIGN_RSH:
      case ASSIGN_URSH:
      case ASSIGN_SUB:
      case ASSIGN_MUL:
      case ASSIGN_DIV:
      case ASSIGN_MOD:
      case ASSIGN_EXPONENT:
        resultPair = analyzeAssignNumericOpFwd(expr, inEnv);
        break;
      case SHEQ:
      case SHNE:
        resultPair =
            analyzeStrictComparisonFwd(
                expr.getToken(), expr.getFirstChild(), expr.getLastChild(), inEnv, specializedType);
        break;
      case EQ:
      case NE:
        resultPair = analyzeNonStrictComparisonFwd(expr, inEnv, specializedType);
        break;
      case LT:
      case GT:
      case LE:
      case GE:
        resultPair = analyzeLtGtFwd(expr, inEnv);
        break;
      case GETPROP:
        Preconditions.checkState(
            !NodeUtil.isAssignmentOp(expr.getParent()) ||
            !NodeUtil.isLValue(expr));
        if (expr.getBooleanProp(Node.ANALYZED_DURING_GTI)) {
          if (expr.isQualifiedName() && !NodeUtil.isTypedefDecl(expr)) {
            markAndGetTypeOfPreanalyzedNode(expr, inEnv, true);
          }
          expr.removeProp(Node.ANALYZED_DURING_GTI);
          resultPair = new EnvTypePair(inEnv, requiredType);
        } else {
          resultPair = analyzePropAccessFwd(
              expr.getFirstChild(), expr.getLastChild().getString(),
              inEnv, requiredType, specializedType);
        }
        break;
      case HOOK:
        resultPair = analyzeHookFwd(expr, inEnv, requiredType, specializedType);
        break;
      case CALL:
      case NEW:
      case TAGGED_TEMPLATELIT:
        resultPair = analyzeInvocationFwd(expr, inEnv, requiredType, specializedType);
        break;
      case COMMA:
        resultPair = analyzeExprFwd(
            expr.getLastChild(),
            analyzeExprFwd(expr.getFirstChild(), inEnv).env,
            requiredType,
            specializedType);
        break;
      case NOT: {
        resultPair = analyzeExprFwd(expr.getFirstChild(),
            inEnv, UNKNOWN, specializedType.negate());
        resultPair.type = resultPair.type.negate().toBoolean();
        break;
      }
      case GETELEM:
        resultPair = analyzeGetElemFwd(expr, inEnv, requiredType, specializedType);
        break;
      case VOID: {
        resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv);
        resultPair.type = UNDEFINED;
        break;
      }
      case IN:
        resultPair = analyzeInFwd(expr, inEnv, specializedType);
        break;
      case DELPROP: {
        // IRFactory checks that the operand is a name, getprop or getelem.
        // analyzePropAccessFwd warns if we delete a constant property.
        resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv);
        resultPair.type = BOOLEAN;
        break;
      }
      case REGEXP:
        resultPair = new EnvTypePair(inEnv, commonTypes.getRegexpType());
        break;
      case ARRAYLIT:
        resultPair = analyzeArrayLitFwd(expr, inEnv);
        break;
      case CAST:
        resultPair = analyzeCastFwd(expr, inEnv, specializedType);
        break;
      case CASE:
        // For a statement of the form: switch (exp1) { ... case exp2: ... }
        // we analyze the case as if it were (exp1 === exp2).
        // We analyze the body of the case when the test is true and the stm
        // following the body when the test is false.
        resultPair = analyzeStrictComparisonFwd(Token.SHEQ,
            expr.getParent().getFirstChild(), expr.getFirstChild(),
            inEnv, specializedType);
        break;
      case TEMPLATELIT:
        resultPair = analyzeTemplateLitFwd(expr, inEnv);
        break;
      case TEMPLATELIT_SUB:
        resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv, requiredType);
        break;
      case STRING_KEY:
        if (expr.hasChildren()) {
          resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv, requiredType, specializedType);
        } else {
          resultPair = analyzeNameFwd(expr, inEnv, requiredType, specializedType);
        }
        break;
      case MEMBER_FUNCTION_DEF:
        resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv, requiredType, specializedType);
        break;
      case COMPUTED_PROP:
        resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv, requiredType, specializedType);
        resultPair = analyzeExprFwd(
            expr.getSecondChild(), resultPair.env, requiredType, specializedType);
        break;
      case YIELD:
        resultPair = analyzeYieldFwd(expr, inEnv);
        break;
      default:
        throw new RuntimeException("Unhandled expression type: " + expr.getToken());
    }
    JSType resultType = resultPair.type;
    mayWarnAboutUnknownType(expr, resultType);
    if (resultType.isUnresolved()) {
      if (this.warnForUnresolvedTypes) {
        warnings.add(JSError.make(expr, CANNOT_USE_UNRESOLVED_TYPE, resultType.toString()));
      }
      resultPair.type = UNKNOWN;
    }
    maybeSetTypeI(expr, resultType);
    if (this.currentScope.isFunction()) {
      // In global scope, the env is too big and produces too much output
      println("AnalyzeExprFWD: ", expr,
          " ::reqtype: ", requiredType, " ::spectype: ", specializedType,
          " ::resulttype: ", resultType);
    }
    return resultPair;
  }

  private void mayWarnAboutUnknownType(Node expr, JSType t) {
    boolean isKnownGetElem = expr.isGetElem() && expr.getLastChild().isString();
    if (t.isUnknown()
        && this.reportUnknownTypes
        // Don't warn for expressions whose value isn't used
        && !expr.getParent().isExprResult()
        // The old type checker doesn't warn about unknown getelems.
        // Maybe because we can't do anything about them, so why warn?
        // We mimic that behavior here.
        && (!expr.isGetElem() || isKnownGetElem)) {
      warnings.add(JSError.make(expr, UNKNOWN_EXPR_TYPE, expr.getToken().toString()));
    }
  }

  private EnvTypePair analyzeNameFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    String varName = expr.getString();
    if (varName.equals("undefined")) {
      return new EnvTypePair(inEnv, UNDEFINED);
    }
    JSType inferredType = envGetType(inEnv, varName);
    if (inferredType == null) {
      println("Found global variable ", varName);
      // For now, we don't warn for global variables
      return new EnvTypePair(inEnv, UNKNOWN);
    }
    println(varName, "'s inferredType: ", inferredType,
        " requiredType:  ", requiredType,
        " specializedType:  ", specializedType);
    if (!inferredType.isSubtypeOf(requiredType)) {
      // The inferred type of a variable is always an upper bound, but
      // sometimes it's also a lower bound, eg, if x was the lhs of an =
      // where we know the type of the rhs.
      // We don't track whether the inferred type is a lower bound, so we
      // conservatively assume that it always is.
      // This is why we warn when !inferredType.isSubtypeOf(requiredType).
      // In some rare cases, the inferred type is only an upper bound,
      // and we would falsely warn.
      // (These usually include the polymorphic operators += and <.)
      // We have a heuristic check to avoid the spurious warnings,
      // but we also miss some true warnings.
      JSType declType = this.currentScope.getDeclaredTypeOf(varName);
      if (tightenNameTypeAndDontWarn(varName, expr, declType, inferredType, requiredType)) {
        inferredType = inferredType.specialize(requiredType);
      } else {
        // Propagate incorrect type so that the context catches
        // the mismatch
        return new EnvTypePair(inEnv, inferredType);
      }
    }
    // If preciseType is bottom, there is a condition that can't be true,
    // but that's not necessarily a type error.
    JSType preciseType = inferredType.specialize(specializedType);
    if (preciseType.isBottom()) {
      preciseType = pickFallbackTypeAfterBottom(varName, inferredType, specializedType);
    }
    println(varName, "'s preciseType: ", preciseType);
    if ((this.currentScope.isUndeclaredFormal(varName)
        || this.currentScope.isUndeclaredOuterVar(varName))
        && preciseType.hasNonScalar()) {
      // In the bwd direction, we may infer a loose type and then join w/
      // top and forget it. That's why we also loosen types going fwd.
      preciseType = preciseType.withLoose();
    }
    return EnvTypePair.addBinding(inEnv, varName, preciseType);
  }

  /**
   * Specialization of a name or of THIS may go to bottom, e.g., as a result of an IF test that NTI
   * thinks is always false. When this happens, we pick a different type to flow around,
   * because bottom may cause unintuitive warnings down the line.
   */
  private JSType pickFallbackTypeAfterBottom(
      String name, JSType inferredType, JSType specializedType) {
    JSType declType = this.currentScope.getDeclaredTypeOf(name);
    if (declType == null) {
      return inferredType;
    }
    JSType preciseType = declType.specialize(specializedType);
    return preciseType.isBottom() ? declType : preciseType;
  }

  private EnvTypePair analyzeLogicalOpFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    Token exprKind = expr.getToken();
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    if ((specializedType.isTrueOrTruthy() && exprKind == Token.AND)
        || (specializedType.isFalseOrFalsy() && exprKind == Token.OR)) {
      EnvTypePair lhsPair =
          analyzeExprFwd(lhs, inEnv, UNKNOWN, specializedType);
      EnvTypePair rhsPair =
          analyzeExprFwd(rhs, lhsPair.env, UNKNOWN, specializedType);
      return rhsPair;
    } else if ((specializedType.isFalseOrFalsy() && exprKind == Token.AND)
        || (specializedType.isTrueOrTruthy() && exprKind == Token.OR)) {
      EnvTypePair shortCircuitPair =
          analyzeExprFwd(lhs, inEnv, UNKNOWN, specializedType);
      EnvTypePair lhsPair = analyzeExprFwd(
          lhs, inEnv, UNKNOWN, specializedType.negate());
      EnvTypePair rhsPair =
          analyzeExprFwd(rhs, lhsPair.env, UNKNOWN, specializedType);
      JSType lhsUnspecializedType = JSType.join(shortCircuitPair.type, lhsPair.type);
      return combineLhsAndRhsForLogicalOps(
          exprKind, lhsUnspecializedType, shortCircuitPair, rhsPair);
    } else {
      // Independently of the specializedType, && rhs is only analyzed when
      // lhs is truthy, and || rhs is only analyzed when lhs is falsy.
      JSType stopAfterLhsType = exprKind == Token.AND ? FALSY : TRUTHY;
      EnvTypePair shortCircuitPair =
          analyzeExprFwd(lhs, inEnv, UNKNOWN, stopAfterLhsType);
      EnvTypePair lhsPair = analyzeExprFwd(
          lhs, inEnv, UNKNOWN, stopAfterLhsType.negate());
      EnvTypePair rhsPair =
          analyzeExprFwd(rhs, lhsPair.env, requiredType, specializedType);
      JSType lhsUnspecializedType = JSType.join(shortCircuitPair.type, lhsPair.type);
      return combineLhsAndRhsForLogicalOps(
          exprKind, lhsUnspecializedType, shortCircuitPair, rhsPair);
    }
  }

  private EnvTypePair combineLhsAndRhsForLogicalOps(Token logicalOp,
      JSType lhsUnspecializedType, EnvTypePair lhsPair, EnvTypePair rhsPair) {
    if (logicalOp == Token.OR) {
      if (lhsUnspecializedType.isAnyTruthyType()) {
        return lhsPair;
      }
      if (lhsUnspecializedType.isAnyFalsyType()) {
        return rhsPair;
      }
      lhsPair.type = lhsPair.type.specialize(TRUTHY);
      return EnvTypePair.join(lhsPair, rhsPair);
    }
    checkState(logicalOp == Token.AND);
    if (lhsUnspecializedType.isAnyFalsyType()) {
      return lhsPair;
    }
    if (lhsUnspecializedType.isAnyTruthyType()) {
      return rhsPair;
    }
    lhsPair.type = lhsPair.type.specialize(FALSY);
    return EnvTypePair.join(lhsPair, rhsPair);
  }

  private EnvTypePair analyzeIncDecFwd(
      Node expr, TypeEnv inEnv, JSType requiredType) {
    mayWarnAboutConst(expr);
    Node ch = expr.getFirstChild();
    if (ch.isGetProp() || (ch.isGetElem() && ch.getLastChild().isString())) {
      // We prefer to analyze the child of INC/DEC one extra time here,
      // to putting the @const prop check in analyzePropAccessFwd.
      Node recv = ch.getFirstChild();
      String pname = ch.getLastChild().getString();
      EnvTypePair pair = analyzeExprFwd(recv, inEnv);
      JSType recvType = pair.type;
      if (mayWarnAboutConstProp(ch, recvType, new QualifiedName(pname))) {
        maybeSetTypeI(ch, recvType.getProp(new QualifiedName(pname)));
        pair.type = requiredType;
        return pair;
      }
    }
    return analyzeUnaryNumFwd(expr, inEnv);
  }

  private EnvTypePair analyzeUnaryNumFwd(Node expr, TypeEnv inEnv) {
    // For inc and dec on a getprop, we don't want to create a property on
    // a struct by accident.
    // But we will get an inexistent-property warning, so we don't check
    // for structness separately here.
    Node child = expr.getFirstChild();
    EnvTypePair pair = analyzeExprFwd(child, inEnv, NUMBER);
    if (!commonTypes.isNumberScalarOrObj(pair.type)) {
      warnInvalidOperand(child, expr.getToken(), NUMBER, pair.type);
    }
    pair.type = NUMBER;
    return pair;
  }

  private EnvTypePair analyzeInstanceofFwd(
      Node expr, TypeEnv inEnv, JSType specializedType) {
    Node obj = expr.getFirstChild();
    Node ctor = expr.getLastChild();
    EnvTypePair objPair;
    EnvTypePair ctorPair;

    // First, evaluate ignoring the specialized context
    objPair = analyzeExprFwd(obj, inEnv);
    JSType objType = objPair.type;
    if (!objType.isTop()
        && !objType.isUnknown()
        && !objType.isTrueOrTruthy()
        && !objType.hasNonScalar()
        && !objType.hasTypeVariable()) {
      warnInvalidOperand(
          obj, Token.INSTANCEOF,
          "an object or a union type that includes an object",
          objPair.type);
    }
    ctorPair = analyzeExprFwd(ctor, objPair.env, commonTypes.topFunction());
    JSType ctorType = ctorPair.type;
    FunctionType ctorFunType = ctorType.getFunType();
    boolean mayBeConstructorFunction = ctorFunType != null
        && (ctorFunType.isLoose()
            || ctorFunType.isQmarkFunction()
            || ctorFunType.isSomeConstructorOrInterface());
    if (!(ctorType.isUnknown() || mayBeConstructorFunction)) {
      warnInvalidOperand(
          ctor, Token.INSTANCEOF, "a constructor function", ctorType);
    }
    if (ctorFunType == null
        || !ctorFunType.isUniqueConstructor()
        || (!specializedType.isTrueOrTruthy()
            && !specializedType.isFalseOrFalsy())) {
      ctorPair.type = BOOLEAN;
      return ctorPair;
    }

    // We are in a specialized context *and* we know the constructor type
    JSType instanceType = ctorFunType.getInstanceTypeOfCtor();
    JSType instanceSpecType;
    if (specializedType.isTrueOrTruthy()) {
      instanceSpecType = objType.specialize(instanceType);
    } else if (objType.isTop()) {
      instanceSpecType = objType;
    } else {
      instanceSpecType = objType.removeType(instanceType);
    }
    if (!instanceSpecType.isBottom()) {
      objPair = analyzeExprFwd(obj, inEnv, UNKNOWN, instanceSpecType);
      ctorPair = analyzeExprFwd(ctor, objPair.env, commonTypes.topFunction());
    }
    ctorPair.type = BOOLEAN;
    return ctorPair;
  }

  private EnvTypePair analyzeAddFwd(Node expr, TypeEnv inEnv, JSType requiredType) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    JSType operandType = requiredType.isNumber() ? NUMBER : UNKNOWN;
    EnvTypePair lhsPair = analyzeExprFwd(lhs, inEnv, operandType);
    EnvTypePair rhsPair = analyzeExprFwd(rhs, lhsPair.env, operandType);
    JSType lhsType = lhsPair.type;
    JSType rhsType = rhsPair.type;
    if (lhsType.isString() || rhsType.isString()) {
      // Return early and don't warn, since '' + expr is used for type coercions
      rhsPair.type = STRING;
      return rhsPair;
    }
    if (!commonTypes.isNumStrScalarOrObj(lhsType)) {
      warnInvalidOperand(lhs, expr.getToken(), NUMBER_OR_STRING, lhsType);
    }
    if (!commonTypes.isNumStrScalarOrObj(rhsType)) {
      warnInvalidOperand(rhs, expr.getToken(), NUMBER_OR_STRING, rhsType);
    }
    return new EnvTypePair(rhsPair.env, JSType.plus(lhsType, rhsType));
  }

  private EnvTypePair analyzeBinaryNumericOpFwd(Node expr, TypeEnv inEnv) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    EnvTypePair lhsPair = analyzeExprFwd(lhs, inEnv, NUMBER);
    EnvTypePair rhsPair = analyzeExprFwd(rhs, lhsPair.env, NUMBER);
    if (!commonTypes.isNumberScalarOrObj(lhsPair.type)) {
      warnInvalidOperand(lhs, expr.getToken(), NUMBER, lhsPair.type);
    }
    if (!commonTypes.isNumberScalarOrObj(rhsPair.type)) {
      warnInvalidOperand(rhs, expr.getToken(), NUMBER, rhsPair.type);
    }
    rhsPair.type = NUMBER;
    return rhsPair;
  }

  private EnvTypePair analyzeAssignFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    if (expr.getBooleanProp(Node.ANALYZED_DURING_GTI)) {
      expr.removeProp(Node.ANALYZED_DURING_GTI);
      // If the assignment is an aliasing of a typedef, markAndGetTypeOfPreanalyzedNode won't
      // be able to find a type and we'll get a spurious warning.
      // But during NTI we don't have typedef info anymore, so we back off for all aliasing
      // definitions, not just ones defining typedefs.
      if (!NodeUtil.isAliasedConstDefinition(expr.getFirstChild())) {
        markAndGetTypeOfPreanalyzedNode(expr.getFirstChild(), inEnv, true);
        markAndGetTypeOfPreanalyzedNode(expr.getLastChild(), inEnv, true);
      }
      return new EnvTypePair(inEnv, requiredType);
    }
    mayWarnAboutConst(expr);
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    if (lhs.getBooleanProp(Node.ANALYZED_DURING_GTI)) {
      lhs.removeProp(Node.ANALYZED_DURING_GTI);
      JSType declType = markAndGetTypeOfPreanalyzedNode(lhs, inEnv, true);
      if (rhs.matchesQualifiedName(ABSTRACT_METHOD_NAME)
          || (GlobalTypeInfoCollector.isCtorDefinedByCall(lhs)
              && !isFunctionBind(rhs.getFirstChild(), inEnv, true))) {
        return new EnvTypePair(inEnv, requiredType);
      }
      EnvTypePair rhsPair = analyzeExprFwd(rhs, inEnv, declType);
      if (rhsPair.type.isSubtypeOf(declType)) {
        registerImplicitUses(expr, rhsPair.type, declType);
      } else if (!NodeUtil.isPrototypeAssignment(lhs)) {
        registerMismatchAndWarn(
            JSError.make(expr, MISTYPED_ASSIGN_RHS, errorMsgWithTypeDiff(declType, rhsPair.type)),
            rhsPair.type, declType);
      }
      return rhsPair;
    }
    LValueResultFwd lvalue = analyzeLValueFwd(lhs, inEnv, requiredType);
    JSType declType = lvalue.declType;
    EnvTypePair rhsPair = analyzeExprFwd(rhs, lvalue.env, requiredType, specializedType);
    if (declType == null) {
      if (!isGlobalVariable(lhs, inEnv)) {
        rhsPair.env = updateLvalueTypeInEnv(rhsPair.env, lhs, lvalue.ptr, rhsPair.type);
      }
    } else if (rhsPair.type.isSubtypeOf(declType)) {
      registerImplicitUses(expr, rhsPair.type, declType);
      rhsPair.env = updateLvalueTypeInEnv(rhsPair.env, lhs, lvalue.ptr, rhsPair.type);
    } else {
      registerMismatchAndWarn(
          JSError.make(expr, MISTYPED_ASSIGN_RHS, errorMsgWithTypeDiff(declType, rhsPair.type)),
          rhsPair.type, declType);
    }
    return rhsPair;
  }

  private EnvTypePair analyzeAssignAddFwd(
      Node expr, TypeEnv inEnv, JSType requiredType) {
    mayWarnAboutConst(expr);
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    JSType lhsReqType =
        specializeKeep2ndWhenBottom(requiredType, NUMBER_OR_STRING);
    LValueResultFwd lvalue = analyzeLValueFwd(lhs, inEnv, lhsReqType);
    JSType lhsType = lvalue.type;
    if (!lhsType.isSubtypeOf(NUMBER_OR_STRING)) {
      warnInvalidOperand(lhs, Token.ASSIGN_ADD, NUMBER_OR_STRING, lhsType);
    }
    // if lhs is a string, rhs can still be a number
    JSType rhsReqType = lhsType.isNumber() ? NUMBER : NUMBER_OR_STRING;
    EnvTypePair pair = analyzeExprFwd(rhs, lvalue.env, rhsReqType);
    if (!pair.type.isSubtypeOf(rhsReqType)) {
      warnInvalidOperand(rhs, Token.ASSIGN_ADD, rhsReqType, pair.type);
    }
    return pair;
  }

  private EnvTypePair analyzeAssignNumericOpFwd(Node expr, TypeEnv inEnv) {
    mayWarnAboutConst(expr);
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    LValueResultFwd lvalue = analyzeLValueFwd(lhs, inEnv, NUMBER);
    JSType lhsType = lvalue.type;
    boolean lhsWarned = false;
    if (!commonTypes.isNumberScalarOrObj(lhsType)) {
      warnInvalidOperand(lhs, expr.getToken(), NUMBER, lhsType);
      lhsWarned = true;
    }
    EnvTypePair pair = analyzeExprFwd(rhs, lvalue.env, NUMBER);
    if (!commonTypes.isNumberScalarOrObj(pair.type)) {
      warnInvalidOperand(rhs, expr.getToken(), NUMBER, pair.type);
    }
    if (!lhsWarned) {
      pair.env = updateLvalueTypeInEnv(pair.env, lhs, lvalue.ptr, NUMBER);
    }
    pair.type = NUMBER;
    return pair;
  }

  private EnvTypePair analyzeLtGtFwd(Node expr, TypeEnv inEnv) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    EnvTypePair lhsPair = analyzeExprFwd(lhs, inEnv);
    EnvTypePair rhsPair = analyzeExprFwd(rhs, lhsPair.env);
    // The type of either side can be specialized based on the other side
    if (lhsPair.type.isScalar() && !rhsPair.type.isScalar()) {
      rhsPair = analyzeExprFwd(rhs, lhsPair.env, lhsPair.type);
    } else if (rhsPair.type.isScalar()) {
      lhsPair = analyzeExprFwd(lhs, inEnv, rhsPair.type);
      rhsPair = analyzeExprFwd(rhs, lhsPair.env, rhsPair.type);
    }
    JSType lhsType = lhsPair.type;
    JSType rhsType = rhsPair.type;
    if (!lhsType.isSubtypeOf(rhsType) && !rhsType.isSubtypeOf(lhsType)
        && !(lhsType.isBoolean() && rhsType.isBoolean())) {
      warnInvalidOperand(expr, expr.getToken(), "matching types", lhsType + ", " + rhsType);
    }
    rhsPair.type = BOOLEAN;
    return rhsPair;
  }

  private EnvTypePair analyzeHookFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    Node cond = expr.getFirstChild();
    Node thenBranch = cond.getNext();
    Node elseBranch = thenBranch.getNext();
    TypeEnv trueEnv = analyzeExprFwd(cond, inEnv, UNKNOWN, TRUTHY).env;
    TypeEnv falseEnv = analyzeExprFwd(cond, inEnv, UNKNOWN, FALSY).env;
    EnvTypePair thenPair = analyzeExprFwd(thenBranch, trueEnv, requiredType, specializedType);
    EnvTypePair elsePair = analyzeExprFwd(elseBranch, falseEnv, requiredType, specializedType);
    return EnvTypePair.join(thenPair, elsePair);
  }

  private EnvTypePair analyzeObjLitCastFwd(ObjectLiteralCast cast, Node call, TypeEnv inEnv) {
    if (cast.objectNode == null) {
      warnings.add(JSError.make(call, ClosureCodingConvention.OBJECTLIT_EXPECTED));
      return new EnvTypePair(inEnv, TOP_OBJECT);
    }
    EnvTypePair pair = analyzeExprFwd(cast.objectNode, inEnv);
    if (!pair.type.isPrototypeObject()) {
      warnings.add(JSError.make(call, REFLECT_CONSTRUCTOR_EXPECTED));
    }
    return new EnvTypePair(pair.env, TOP_OBJECT);
  }

  /**
   * TTL ASTs include type variables with their names as they appear in the program source,
   * not with their NTI-internal names. But type maps use the NTI-internal names.
   * To evaluate TTL ASTs using the type map, we need to reconcile the two.
   * The most bulletproof way would be to clone the TTL AST and substitute-in the internal names
   * in JSTypeCreatorFromJSDoc. Instead, we take a shortcut here and use the original names.
   * 1) TTL ASTs are currently not scoped; they can only use symbols from the top level, so there
   *    is no ambiguity when we use the original names.
   * 2) Cloning the TTL AST can be costly.
   * If in the future we start evaluating TTL ASTs in non-global scopes, we can revisit this.
   */
  private ImmutableMap<String, TypeI> getTypemapWithOriginalNames(
      ImmutableMap<String, JSType> typeMap) {
    ImmutableMap.Builder<String, TypeI> builder = ImmutableMap.builder();
    for (Map.Entry<String, JSType> entry : typeMap.entrySet()) {
      String originalName = UniqueNameGenerator.getOriginalName(entry.getKey());
      builder.put(originalName, entry.getValue());
    }
    return builder.build();
  }

  /**
   * Instantiate the generic function using the appropriate type map.
   *
   * If the generic function uses TTL, we need to map the TTL variables to types.
   * In this method, we find the TTL expressions, call TypeTransformation#eval to evaluate them,
   * and update the type map.
   */
  private FunctionType instantiateCalleeMaybeWithTTL(
      FunctionType calleeType, ImmutableMap<String, JSType> typeMap) {
    Map<String, Node> typeTransformations = calleeType.getTypeTransformations();
    if (typeTransformations.isEmpty()) {
      return calleeType.instantiateGenerics(typeMap);
    }
    ImmutableMap<String, TypeI> mapWithOriginalNames = getTypemapWithOriginalNames(typeMap);
    LinkedHashMap<String, JSType> newTypeMap = new LinkedHashMap<>();
    newTypeMap.putAll(typeMap);
    for (Map.Entry<String, Node> entry : typeTransformations.entrySet()) {
      String ttlVar = entry.getKey();
      Node transform = entry.getValue();
      @SuppressWarnings({"unchecked", "rawtypes"})
      JSType t = (JSType) this.ttlObj.eval(transform, mapWithOriginalNames);
      newTypeMap.put(ttlVar, t);
    }
    return calleeType.instantiateGenerics(ImmutableMap.copyOf(newTypeMap));
  }

  private EnvTypePair analyzeInvocationFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    if (isPropertyTestCall(expr)) {
      return analyzePropertyTestCallFwd(expr, inEnv, specializedType);
    }
    if (expr.isCall() && this.convention.getObjectLiteralCast(expr) != null) {
      return analyzeObjLitCastFwd(this.convention.getObjectLiteralCast(expr), expr, inEnv);
    }
    Node callee = expr.getFirstChild();
    if (isFunctionBind(callee, inEnv, true)) {
      return analyzeFunctionBindFwd(expr, inEnv);
    }
    AssertionFunctionSpec assertionFunctionSpec =
        assertionFunctionsMap.get(callee.getQualifiedName());
    if (assertionFunctionSpec != null) {
      return analyzeAssertionCall(expr, inEnv, assertionFunctionSpec);
    }
    EnvTypePair calleePair = analyzeExprFwd(callee, inEnv, commonTypes.topFunction());
    TypeEnv envAfterCallee = calleePair.env;
    calleePair = mayWarnAboutNullableReferenceAndTighten(
        callee, calleePair.type, null, envAfterCallee);
    JSType calleeType = calleePair.type;
    if (calleeType.isBottom() || !calleeType.isSubtypeOf(commonTypes.topFunction())) {
      warnings.add(JSError.make(expr, NOT_CALLABLE, calleeType.toString()));
    }
    FunctionType funType = calleeType.getFunTypeIfSingletonObj();
    if (funType == null
        || funType.isTopFunction() || funType.isQmarkFunction()) {
      return analyzeInvocationArgsFwdWhenError(expr, envAfterCallee);
    } else if (funType.isLoose()) {
      return analyzeLooseCallNodeFwd(expr, envAfterCallee, requiredType);
    } else if (!isConstructorCall(expr)
        && funType.isSomeConstructorOrInterface()
        && (funType.getReturnType().isUnknown()
            || funType.getReturnType().isUndefined())) {
      warnings.add(JSError.make(expr, CONSTRUCTOR_NOT_CALLABLE, funType.toString()));
      return analyzeInvocationArgsFwdWhenError(expr, envAfterCallee);
    } else if (expr.isNew()) {
      if (!funType.isSomeConstructorOrInterface() || funType.isInterfaceDefinition()) {
        // When Foo is an interface type, we don't want to warn when someone passes around
        // a first-class function of type function(new:Foo); we only want to warn when someone
        // directly calls: new Foo.
        // But there is no distinction between these two types in the type system, so we use
        // a heuristic here: if the function was passed as a formal parameter (either directly
        // or as an arbitrarily nested property), don't warn.
        if (callee.isQualifiedName()) {
          String qnameRoot = QualifiedName.fromNode(callee).getLeftmostName();
          if (!this.currentScope.isFormalParamInAnyAncestorScope(qnameRoot)) {
            warnings.add(JSError.make(expr, NOT_A_CONSTRUCTOR, funType.toString()));
          }
        }
        return analyzeInvocationArgsFwdWhenError(expr, envAfterCallee);
      } else if (funType.isConstructorOfAbstractClass()) {
        warnings.add(JSError.make(expr, CANNOT_INSTANTIATE_ABSTRACT_CLASS, funType.toString()));
        return analyzeInvocationArgsFwdWhenError(expr, envAfterCallee);
      }
    } else if (expr.isTaggedTemplateLit()) {
      funType = checkTaggedFunctionFirstParam(expr.getLastChild(), expr.getFirstChild(), funType);
    }

    if (!isInvocationArgCountCorrectAndWarn(funType, expr, callee)) {
      return analyzeInvocationArgsFwdWhenError(expr, envAfterCallee);
    }

    FunctionType originalFunType = funType; // save for later
    if (funType.isGeneric()) {
      Node receiver = callee.isGetProp() ? callee.getFirstChild() : null;
      Node firstArg = expr.getSecondChild();
      ImmutableMap<String, JSType> typeMap =
          calcTypeInstantiationFwd(expr, receiver, firstArg, funType, envAfterCallee);
      funType = instantiateCalleeMaybeWithTTL(funType, typeMap);
      println("Instantiated function type: ", funType);
    }
    // argTypes collects types of actuals for deferred checks.
    List<JSType> argTypes = new ArrayList<>();
    Node invocationNode = expr.isTaggedTemplateLit() ? expr.getLastChild() : expr;
    Iterable<Node> argIterable = NodeUtil.getInvocationArgsAsIterable(expr);
    TypeEnv tmpEnv = analyzeInvocationArgumentsFwd(
          invocationNode, argIterable, funType, argTypes, envAfterCallee);
    if (callee.isName()) {
      String calleeName = callee.getString();
      if (this.currentScope.isKnownFunction(calleeName)
          && !this.currentScope.isExternalFunction(calleeName)) {
        // Local function definitions will be type-checked more
        // exactly using their summaries, and don't need deferred checks
        if (this.currentScope.isLocalFunDef(calleeName)) {
          tmpEnv = collectTypesForEscapedVarsFwd(callee, tmpEnv);
        } else if (!originalFunType.isGeneric()) {
          JSType expectedRetType = requiredType;
          println("Updating deferred check with ret: ", expectedRetType, " and args: ", argTypes);
          DeferredCheck dc;
          if (isConstructorCall(expr)) {
            dc = new DeferredCheck(expr, null,
                this.currentScope, this.currentScope.getScope(calleeName));
            deferredChecks.put(expr, dc);
          } else {
            dc = deferredChecks.get(expr);
            if (dc != null) {
              dc.updateReturn(expectedRetType);
            } else {
              // The backward analysis of a function is skipped when all
              // variables, including outer vars, are declared.
              // So, we check that dc is null iff bwd was skipped.
              Preconditions.checkState(
                  !this.currentScope.hasUndeclaredFormalsOrOuters(),
                  "No deferred check created in backward direction for %s",
                  expr);
            }
          }
          if (dc != null) {
            dc.updateArgTypes(argTypes);
          }
        }
      }
    }
    JSType retType = expr.isNew() ? funType.getThisType() : funType.getReturnType();
    if (retType.isSubtypeOf(requiredType)) {
      retType = retType.specialize(specializedType);
    }
    return new EnvTypePair(tmpEnv, retType);
  }

  /**
   * Check that the first argument of a tagged function is a ITemplateArray.
   * If the argument is missing, return Function, to avoid giving two warnings for the same issue.
   */
  private FunctionType checkTaggedFunctionFirstParam(
      Node taggedLit, Node funcName, FunctionType funType) {
    JSType firstArgType = funType.getFormalType(0);
    JSType templateArray = this.commonTypes.getITemplateArrayType();
    if (firstArgType == null) {
      warnings.add(JSError.make(taggedLit, TEMPLATE_ARGUMENT_MISSING));
      return this.commonTypes.qmarkFunction().getFunTypeIfSingletonObj();
    } else if (!templateArray.isSubtypeOf(firstArgType)) {
      JSError error = JSError.make(taggedLit, TEMPLATE_ARGUMENT_MISMATCH,
          getReadableCalleeName(funcName), errorMsgWithTypeDiff(templateArray, firstArgType));
      registerMismatchAndWarn(error, firstArgType, templateArray);
    }
    return funType;
  }

  private boolean isInvocationArgCountCorrectAndWarn(
      FunctionType funType, Node expr, Node funcName) {
    int numArgs = NodeUtil.getInvocationArgsCount(expr);
    int maxArity = funType.getMaxArity();
    int minArity = funType.getMinArity();
    if (numArgs < minArity || numArgs > maxArity) {
      warnings.add(JSError.make(
          expr, WRONG_ARGUMENT_COUNT,
          getReadableCalleeName(funcName),
          Integer.toString(numArgs), Integer.toString(minArity),
          " and at most " + maxArity));
      return false;
    }
    return true;
  }

  private boolean isConstructorCall(Node expr) {
    return expr.isNew()
        || (expr.isCall() && this.currentScope.isConstructor() && expr.getFirstChild().isSuper());
  }

  private EnvTypePair analyzeFunctionBindFwd(Node call, TypeEnv inEnv) {
    checkArgument(call.isCall());
    Bind bindComponents = this.convention.describeFunctionBind(call, true, false);
    Node boundFunNode = bindComponents.target;
    EnvTypePair pair = analyzeExprFwd(boundFunNode, inEnv);
    TypeEnv env = pair.env;
    FunctionType boundFunType = pair.type.getFunTypeIfSingletonObj();
    if (!pair.type.isSubtypeOf(commonTypes.topFunction())) {
      warnings.add(JSError.make(boundFunNode, GOOG_BIND_EXPECTS_FUNCTION, pair.type.toString()));
    }
    // For some function types, we don't know enough to handle .bind specially.
    if (boundFunType == null
        || boundFunType.isTopFunction()
        || boundFunType.isQmarkFunction()
        || boundFunType.isLoose()) {
      return analyzeInvocationArgsFwdWhenError(call, env);
    }
    if (boundFunType.isSomeConstructorOrInterface()) {
      warnings.add(JSError.make(call, CANNOT_BIND_CTOR));
      return new EnvTypePair(env, UNKNOWN);
    }
    // Check if the receiver argument is there
    int callChildCount = call.getChildCount();
    if ((NodeUtil.isGoogBind(call.getFirstChild()) && callChildCount <= 2)
        || (!NodeUtil.isGoogPartial(call.getFirstChild()) && callChildCount == 1)) {
      warnings.add(JSError.make(
          call, WRONG_ARGUMENT_COUNT,
          getReadableCalleeName(call.getFirstChild()),
          "0", "1", ""));
    }
    // Check that there are not too many of the other arguments
    int maxArity = boundFunType.hasRestFormals()
        ? Integer.MAX_VALUE : boundFunType.getMaxArity();
    int numArgs = bindComponents.getBoundParameterCount();
    if (numArgs > maxArity) {
      warnings.add(JSError.make(
          call, WRONG_ARGUMENT_COUNT,
          getReadableCalleeName(call.getFirstChild()),
          Integer.toString(numArgs), "0",
          " and at most " + maxArity));
      return analyzeInvocationArgsFwdWhenError(call, inEnv);
    }

    // If the bound function is polymorphic, we only support the case where we
    // can completely calculate the type instantiation at the .bind call site.
    // We don't support splitting the instantiation between call sites.
    //
    Node receiver = bindComponents.thisValue;
    if (boundFunType.isGeneric()) {
      Map<String, JSType> typeMap = calcTypeInstantiationFwd(
          call, receiver, bindComponents.parameters, boundFunType, env);
      boundFunType = boundFunType.instantiateGenerics(typeMap);
    }
    FunctionTypeBuilder builder = new FunctionTypeBuilder(this.commonTypes);
    if (receiver != null) {// receiver is null for goog.partial
      JSType reqThisType = boundFunType.getThisType();
      if (reqThisType == null || boundFunType.isSomeConstructorOrInterface()) {
        reqThisType = JSType.join(NULL, TOP_OBJECT);
      }
      pair = analyzeExprFwd(receiver, env, reqThisType);
      env = pair.env;
      if (!pair.type.isSubtypeOf(reqThisType)) {
        warnings.add(JSError.make(call, INVALID_THIS_TYPE_IN_BIND,
                errorMsgWithTypeDiff(reqThisType, pair.type)));
      }
    }

    Iterable<Node> parametersIterable =
        bindComponents.parameters == null
        ? ImmutableList.<Node>of()
        : bindComponents.parameters.siblings();
    // We're passing an arraylist but don't do deferred checks for bind.
    env = analyzeInvocationArgumentsFwd(
        call, parametersIterable, boundFunType, new ArrayList<JSType>(), env);
    // For any formal not bound here, add it to the resulting function type.
    for (int j = numArgs; j < boundFunType.getMaxArityWithoutRestFormals(); j++) {
      JSType formalType = boundFunType.getFormalType(j);
      if (boundFunType.isRequiredArg(j)) {
        builder.addReqFormal(formalType);
      } else {
        builder.addOptFormal(formalType);
      }
    }
    if (boundFunType.hasRestFormals()) {
      builder.addRestFormals(boundFunType.getRestFormalsType());
    }
    return new EnvTypePair(env, commonTypes.fromFunctionType(
        builder.addRetType(boundFunType.getReturnType()).buildFunction()));
  }

  private TypeEnv analyzeInvocationArgumentsFwd(Node node, Iterable<Node> args,
      FunctionType funType, List<JSType> argTypesForDeferredCheck, TypeEnv inEnv) {
    checkState(NodeUtil.isCallOrNew(node) || node.isTemplateLit());
    TypeEnv env = inEnv;
    int i = node.isTemplateLit() ? 1 : 0;
    for (Node arg : args) {
      JSType formalType = funType.getFormalType(i);
      checkState(!formalType.isBottom());
      EnvTypePair pair = analyzeExprFwd(arg, env, formalType);
      JSType argTypeForDeferredCheck = pair.type;
      // Allow passing undefined for an optional argument.
      if (funType.isOptionalArg(i) && pair.type.equals(UNDEFINED)) {
        argTypeForDeferredCheck = null; // No deferred check needed.
      } else if (!pair.type.isSubtypeOf(formalType)) {
        String fnName = getReadableCalleeName(node.getFirstChild());
        JSError error = JSError.make(arg, INVALID_ARGUMENT_TYPE, Integer.toString(i + 1), fnName,
            errorMsgWithTypeDiff(formalType, pair.type));
        registerMismatchAndWarn(error, pair.type, formalType);
        argTypeForDeferredCheck = null; // No deferred check needed.
      } else {
        registerImplicitUses(arg, pair.type, formalType);
      }
      argTypesForDeferredCheck.add(argTypeForDeferredCheck);
      env = pair.env;
      i++;
    }
    return env;
  }

  private EnvTypePair analyzeAssertionCall(
      Node callNode, TypeEnv env, AssertionFunctionSpec assertionFunctionSpec) {
    analyzeExprFwdIgnoreResult(callNode.getFirstChild(), env);
    Node firstParam = callNode.getSecondChild();
    if (firstParam == null) {
      return new EnvTypePair(env, UNKNOWN);
    }
    for (Node assertionArgument : firstParam.siblings()) {
      analyzeExprFwdIgnoreResult(assertionArgument, env);
    }
    Node assertedNode = assertionFunctionSpec.getAssertedParam(firstParam);
    if (assertedNode == null) {
      return new EnvTypePair(env, UNKNOWN);
    }
    JSType assertedType = assertionFunctionSpec.getAssertedNewType(callNode, currentScope);
    if (assertedType.isUnknown()) {
      warnings.add(JSError.make(callNode, UNKNOWN_ASSERTION_TYPE));
    }
    EnvTypePair pair = analyzeExprFwd(assertedNode, env, UNKNOWN, assertedType);
    boolean haveCommonSubtype = JSType.haveCommonSubtype(assertedType, pair.type);
    if (!pair.type.isSubtypeOf(assertedType) && haveCommonSubtype) {
      // We do this because the assertion needs to return a subtype of the
      // asserted type to its context, but sometimes the asserted expression
      // can't be specialized.
      pair.type = assertedType;
    }
    if (!haveCommonSubtype) {
      JSType t = analyzeExprFwd(assertedNode, env).type.substituteGenericsWithUnknown();
      if (t.isSubtypeOf(assertedType)) {
        pair.type = t;
      } else {
        if (!firstParam.isFalse()) { // Don't warn for an explicit: assert(false);
          warnings.add(JSError.make(assertedNode, ASSERT_FALSE));
        }
        pair.type = UNKNOWN;
        pair.env = env;
      }
    }
    return pair;
  }

  private EnvTypePair analyzeGetElemFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    Node receiver = expr.getFirstChild();
    Node index = expr.getLastChild();
    JSType reqObjType = pickReqObjType(expr);
    EnvTypePair pair = analyzeExprFwd(receiver, inEnv, reqObjType);
    pair = mayWarnAboutNullableReferenceAndTighten(receiver, pair.type, null, pair.env);
    JSType recvType = pair.type.autobox();
    if (!mayWarnAboutNonObject(receiver, recvType, specializedType)
        && !mayWarnAboutStructPropAccess(receiver, recvType)) {
      JSType indexType = recvType.getIndexType();
      if (indexType != null) {
        pair = analyzeExprFwd(
            index, pair.env, firstNonBottom(indexType, UNKNOWN));
        mayWarnAboutBadIObjectIndex(index, recvType, pair.type, indexType);
        pair.type = getIndexedTypeOrUnknown(recvType);
        return pair;
      } else if (index.isString()) {
        return analyzePropAccessFwd(receiver, index.getString(), inEnv,
            requiredType, specializedType);
      }
    }
    pair = analyzeExprFwd(index, pair.env);
    pair.type = UNKNOWN;
    return pair;
  }

  private EnvTypePair analyzeInFwd(
      Node expr, TypeEnv inEnv, JSType specializedType) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    JSType reqObjType = pickReqObjType(expr);
    EnvTypePair pair;

    pair = analyzeExprFwd(lhs, inEnv, NUMBER_OR_STRING);
    if (!pair.type.isSubtypeOf(NUMBER_OR_STRING)) {
      warnInvalidOperand(lhs, Token.IN, NUMBER_OR_STRING, pair.type);
    }
    pair = analyzeExprFwd(rhs, pair.env, reqObjType);
    if (!pair.type.isSubtypeOf(TOP_OBJECT)) {
      warnInvalidOperand(rhs, Token.IN, "Object", pair.type);
      pair.type = BOOLEAN;
      return pair;
    }
    if (pair.type.isStruct()) {
      warnings.add(JSError.make(rhs, IN_USED_WITH_STRUCT));
      pair.type = BOOLEAN;
      return pair;
    }

    JSType resultType = BOOLEAN;
    if (lhs.isString()) {
      QualifiedName pname = new QualifiedName(lhs.getString());
      if (specializedType.isTrueOrTruthy()) {
        pair = analyzeExprFwd(rhs, inEnv, reqObjType,
            reqObjType.withPropertyRequired(pname.getLeftmostName()));
        resultType = TRUE_TYPE;
      } else if (specializedType.isFalseOrFalsy()) {
        pair = analyzeExprFwd(rhs, inEnv, reqObjType);
        // If the rhs is a loose object, we won't warn about missing
        // properties, despite removing the type here.
        // The only way to have that warning would be to keep track of props
        // that a loose object *cannot* have; but the implementation cost
        // is probably not worth it.
        pair = analyzeExprFwd(
            rhs, inEnv, reqObjType, pair.type.withoutProperty(pname));
        resultType = FALSE_TYPE;
      }
    }
    pair.type = resultType;
    return pair;
  }

  private EnvTypePair analyzeArrayLitFwd(Node expr, TypeEnv inEnv) {
    TypeEnv env = inEnv;
    JSType elementType = BOTTOM;
    for (Node arrayElm = expr.getFirstChild(); arrayElm != null;
         arrayElm = arrayElm.getNext()) {
      EnvTypePair pair = analyzeExprFwd(arrayElm, env);
      env = pair.env;
      elementType = JSType.join(elementType, pair.type);
    }
    elementType = firstNonBottom(elementType, UNKNOWN);
    return new EnvTypePair(env, commonTypes.getArrayInstance(elementType));
  }

  // Because of the cast, expr doesn't need to have the required type of the context.
  // However, we still pass along the specialized type, to specialize types when using
  // logical operators.
  private EnvTypePair analyzeCastFwd(Node expr, TypeEnv inEnv, JSType specializedType) {
    Node parent = expr.getParent();
    JSType newSpecType = this.commonTypes.UNKNOWN;
    if ((parent.isOr() || parent.isAnd()) && expr == parent.getFirstChild()) {
      newSpecType = specializedType;
    }
    Node insideCast = expr.getFirstChild();
    EnvTypePair pair = analyzeExprFwd(insideCast, inEnv, this.commonTypes.UNKNOWN, newSpecType);
    JSType fromType = pair.type;
    JSType toType = symbolTable.getCastType(expr);
    if (!fromType.isInterfaceInstance()
        && !toType.isInterfaceInstance()
        && !JSType.haveCommonSubtype(fromType, toType)
        && !fromType.hasTypeVariable()) {
      JSError error = JSError.make(expr, INVALID_CAST, fromType.toString(), toType.toString());
      registerMismatchAndWarn(error, fromType, toType);
    } else {
      registerImplicitUses(expr, fromType, toType);
    }
    insideCast.putProp(Node.TYPE_BEFORE_CAST, fromType);
    insideCast.setTypeI(toType);
    pair.type = toType;
    return pair;
  }

  private EnvTypePair analyzeInvocationArgsFwdWhenError(Node call, TypeEnv env) {
    return analyzeInvocationArgsFwdWhenError(NodeUtil.getInvocationArgsAsIterable(call), env);
  }

  private EnvTypePair analyzeInvocationArgsFwdWhenError(Iterable<Node> args, TypeEnv inEnv) {
    TypeEnv env = inEnv;
    for (Node arg : args) {
      env = analyzeExprFwd(arg, env).env;
    }
    return new EnvTypePair(env, UNKNOWN);
  }

  private EnvTypePair analyzeTemplateLitFwd(Node expr, TypeEnv inEnv) {
    TypeEnv env = inEnv;
    for (Node child : expr.children()) {
      env = analyzeExprFwd(child, env).env;
    }
    return new EnvTypePair(env, STRING);
  }

  private EnvTypePair analyzeStrictComparisonFwd(Token comparisonOp,
      Node lhs, Node rhs, TypeEnv inEnv, JSType specializedType) {
    if (specializedType.isTrueOrTruthy() || specializedType.isFalseOrFalsy()) {
      if (lhs.isTypeOf()) {
        return analyzeSpecializedTypeof(
            lhs, rhs, comparisonOp, inEnv, specializedType);
      } else if (rhs.isTypeOf()) {
        return analyzeSpecializedTypeof(
            rhs, lhs, comparisonOp, inEnv, specializedType);
      } else if (isGoogTypeof(lhs)) {
        return analyzeGoogTypeof(lhs, rhs, inEnv, specializedType);
      } else if (isGoogTypeof(rhs)) {
        return analyzeGoogTypeof(rhs, lhs, inEnv, specializedType);
      }
    }

    EnvTypePair lhsPair = analyzeExprFwd(lhs, inEnv);
    EnvTypePair rhsPair = analyzeExprFwd(rhs, lhsPair.env);
    JSType rhstype = rhsPair.type;
    JSType lhstype = lhsPair.type;
    if (!rhstype.isNullOrUndef()) {
      if (JSType.haveCommonSubtype(lhstype, rhstype)) {
        registerImplicitUses(lhs, lhstype, rhstype);
      } else {
        JSError error = JSError.make(
            lhs, INCOMPATIBLE_STRICT_COMPARISON, lhstype.toString(), rhstype.toString());
        registerMismatchAndWarn(error, lhstype, rhstype);
      }
    }

    // This env may contain types that have been tightened after nullable deref.
    TypeEnv preciseEnv = rhsPair.env;

    if ((comparisonOp == Token.SHEQ && specializedType.isTrueOrTruthy())
        || (comparisonOp == Token.SHNE && specializedType.isFalseOrFalsy())) {
      lhsPair = analyzeExprFwd(lhs, preciseEnv,
          UNKNOWN, lhsPair.type.specialize(rhsPair.type));
      rhsPair = analyzeExprFwd(rhs, lhsPair.env,
          UNKNOWN, rhsPair.type.specialize(lhsPair.type));
    } else if ((comparisonOp == Token.SHEQ && specializedType.isFalseOrFalsy()) ||
        (comparisonOp == Token.SHNE && specializedType.isTrueOrTruthy())) {
      JSType lhsType = lhsPair.type;
      JSType rhsType = rhsPair.type;
      if (lhsType.isNullOrUndef()) {
        rhsType = rhsType.removeType(lhsType);
      } else if (rhsType.isNullOrUndef()) {
        lhsType = lhsType.removeType(rhsType);
      }
      lhsPair = analyzeExprFwd(lhs, preciseEnv, UNKNOWN, lhsType);
      rhsPair = analyzeExprFwd(rhs, lhsPair.env, UNKNOWN, rhsType);
    }
    rhsPair.type = BOOLEAN;
    return rhsPair;
  }

  private EnvTypePair analyzeSpecializedTypeof(Node typeof, Node typeString,
      Token comparisonOp, TypeEnv inEnv, JSType specializedType) {
    EnvTypePair pair;
    Node typeofRand = typeof.getFirstChild();
    JSType comparedType = getTypeFromString(typeString);
    checkInvalidTypename(typeString);
    if (comparedType.isUnknown()) {
      pair = analyzeExprFwd(typeofRand, inEnv);
      pair = analyzeExprFwd(typeString, pair.env);
    } else if ((specializedType.isTrueOrTruthy()
            && (comparisonOp == Token.SHEQ || comparisonOp == Token.EQ))
        || (specializedType.isFalseOrFalsy()
            && (comparisonOp == Token.SHNE || comparisonOp == Token.NE))) {
      pair = analyzeExprFwd(typeofRand, inEnv, UNKNOWN, comparedType);
    } else {
      pair = analyzeExprFwd(typeofRand, inEnv);
      JSType rmType = pair.type.removeType(comparedType);
      if (!rmType.isBottom()) {
        pair = analyzeExprFwd(typeofRand, inEnv, UNKNOWN, rmType);
      }
    }
    pair.type = specializedType.toBoolean();
    return pair;
  }

  private EnvTypePair analyzeThisFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    mayWarnAboutGlobalThis(expr);
    if (!this.currentScope.hasThis()) {
      return new EnvTypePair(inEnv, UNKNOWN);
    }
    // A trimmed-down version of analyzeNameFwd.
    JSType inferredType = envGetType(inEnv, THIS_ID);
    if (!inferredType.isSubtypeOf(requiredType)) {
      return new EnvTypePair(inEnv, inferredType);
    }
    JSType preciseType = inferredType.specialize(specializedType);
    if (preciseType.isBottom()) {
      preciseType = pickFallbackTypeAfterBottom(THIS_ID, inferredType, specializedType);
    }
    return EnvTypePair.addBinding(inEnv, THIS_ID, preciseType);
  }

  private EnvTypePair analyzeSuperFwd(Node expr, TypeEnv inEnv) {
    checkArgument(expr.isSuper());
    if (this.currentScope.hasThis()) {
      NominalType thisClass =
          checkNotNull(envGetType(inEnv, THIS_ID).getNominalTypeIfSingletonObj());
      NominalType superClass = thisClass.getInstantiatedSuperclass();
      if (superClass == null) {
        // This indicates bad code and there will probably be other errors reported.
        // In particular JSC_NTI_INHERITANCE_CYCLE for `class Foo extends Foo ...`.
        warnings.add(JSError.make(expr, UNDEFINED_SUPER_CLASS, thisClass.toString()));
        return new EnvTypePair(inEnv, UNKNOWN);
      }
      if (this.currentScope.isConstructor()) {
        JSType superCtor = commonTypes.fromFunctionType(superClass.getConstructorFunction());
        return new EnvTypePair(inEnv, superCtor);
      }
      return new EnvTypePair(inEnv, superClass.getInstanceAsJSType());
    }
    // Use of super in a static method.
    Node funName = NodeUtil.getBestLValue(this.currentScope.getRoot());
    Node classNameNode = funName.getFirstChild();
    JSType thisClassAsJstype = analyzeExprFwd(classNameNode, inEnv).type;
    FunctionType thisCtor = thisClassAsJstype.getFunTypeIfSingletonObj();
    NominalType thisClass = thisCtor.getThisType().getNominalTypeIfSingletonObj();
    NominalType superClass = thisClass.getInstantiatedSuperclass();
    if (superClass == null) {
      // This indicates bad code and there will probably be other errors reported.
      // In particular JSC_NTI_INHERITANCE_CYCLE for `class Foo extends Foo ...`.
      warnings.add(JSError.make(expr, UNDEFINED_SUPER_CLASS, funName.toString()));
      return new EnvTypePair(inEnv, UNKNOWN);
    }
    return new EnvTypePair(inEnv, superClass.getNamespaceType());
  }

  private EnvTypePair analyzeYieldFwd(Node expr, TypeEnv inEnv) {
    if (!expr.hasChildren()) {
      return new EnvTypePair(envPutType(inEnv, YIELDVAL_ID, UNDEFINED), UNKNOWN);
    }
    EnvTypePair resultPair = analyzeExprFwd(expr.getFirstChild(), inEnv);

    // Getting the instantiated declared return type
    JSType iterable = this.commonTypes.getIterableInstance(UNKNOWN);
    JSType iterator = this.commonTypes.getIteratorInstance(UNKNOWN);
    JSType generator = this.commonTypes.getGeneratorInstance(UNKNOWN);
    JSType declRetType = getDeclaredReturnTypeOfCurrentScope(generator);
    JSType yieldType;
    if (!generator.isSubtypeOf(declRetType)) {
      // Return early due to unexpected declared return type, but do not warn
      // Warning will be generated in createSummary of the function
      resultPair.type = UNKNOWN;
      return resultPair;
    } else if (declRetType.isSubtypeOf(iterable)) {
      // This check and implementation is in lieu of calling a hypothetical unifyWithSupertype()
      // method on generator.
      yieldType = declRetType.getInstantiatedTypeArgument(iterable);
    } else if (declRetType.isSubtypeOf(iterator))  {
      yieldType = declRetType.getInstantiatedTypeArgument(iterator);
    } else {
      // declRetType is neither subtype of iterable nor iterator. This means we do not know
      // anything about the yield type.
      yieldType = UNKNOWN;
    }

    // Getting the actual ret type
    JSType actualRetType;
    if (expr.isYieldAll()) {
      JSType boxedType = resultPair.type.autobox();
      if (boxedType.isSubtypeOf(iterable)) {
        actualRetType = boxedType.getInstantiatedTypeArgument(iterable);
      } else {
        warnings.add(JSError.make(expr, YIELD_ALL_EXPECTS_ITERABLE, resultPair.type.toString()));
        resultPair.type = UNKNOWN;
        return resultPair;
      }
    } else {
      actualRetType = resultPair.type;
    }

    if (!yieldType.isBottom() && !actualRetType.isSubtypeOf(yieldType)) {
      // Do not warn if yieldType is bottom because this only happens when unification returns
      // an empty list, which means the declRetType is Generator<?>
      registerMismatchAndWarn(
          JSError.make(
              expr, YIELD_NONDECLARED_TYPE, errorMsgWithTypeDiff(yieldType, actualRetType)),
          actualRetType, yieldType);
      resultPair.type = UNKNOWN;
      return resultPair;
    }

    if (yieldType.isBottom() || yieldType.isUnknown()) {
      // Infer the instantiated yield type of the function if there is no declared type.
      JSType oldType = envGetType(resultPair.env, YIELDVAL_ID);
      if (oldType == null) {
        resultPair.env = envPutType(resultPair.env, YIELDVAL_ID, actualRetType);
      } else {
        resultPair.env = envPutType(
            resultPair.env, YIELDVAL_ID, JSType.join(oldType, actualRetType));
      }
    }
    resultPair.type = UNKNOWN;
    return resultPair;
  }

  private JSType getTypeFromString(Node typeString) {
    if (!typeString.isString()) {
      return UNKNOWN;
    }
    switch (typeString.getString()) {
      case "number":
        return NUMBER;
      case "string":
        return STRING;
      case "boolean":
        return BOOLEAN;
      case "undefined":
        return UNDEFINED;
      case "function":
        return commonTypes.looseTopFunction();
      case "object":
        return JSType.join(NULL, TOP_OBJECT);
      default:
        return UNKNOWN;
    }
  }

  private void checkInvalidTypename(Node typeString) {
    if (!typeString.isString()) {
      return;
    }
    String typeName = typeString.getString();
    switch (typeName) {
      case "number":
      case "string":
      case "boolean":
      case "undefined":
      case "function":
      case "object":
      case "symbol":
      case "unknown": // IE-specific type name
        break;
      default:
        warnings.add(JSError.make(typeString, UNKNOWN_TYPEOF_VALUE, typeName));
    }
  }

  private ImmutableMap<String, JSType> calcTypeInstantiationFwd(
      Node callNode, Node receiver, Node firstArg, FunctionType funType, TypeEnv typeEnv) {
    return calcTypeInstantiation(callNode, receiver, firstArg, funType, typeEnv, true);
  }

  private ImmutableMap<String, JSType> calcTypeInstantiationBwd(
      Node callNode, FunctionType funType, TypeEnv typeEnv) {
    return calcTypeInstantiation(
        callNode, null, callNode.getSecondChild(), funType, typeEnv, false);
  }

  /**
   * We don't use the requiredType of the context to unify with the return
   * type. There are several difficulties:
   * 1) A polymorphic function is allowed to return ANY subtype of the
   *    requiredType, so we would need to use a heuristic to determine the type
   *    to unify with.
   * 2) It's hard to give good error messages in cases like: id('str') - 5
   *    We want an invalid-operand-type, not a not-unique-instantiation.
   *
   *
   * We don't take the arg evaluation order into account during instantiation.
   *
   *
   * When calculating the instantiation, when do we use the receiver type?
   * See the following snippet:
   * /**
   *  * @constructor
   *  * @template T
   *  * @param {T} x
   *  * /
   * function Foo(x) {}
   * /**
   *  * @template T
   *  * @param {T} x
   *  * /
   * Foo.prototype.f = function(x) {};
   * Foo.prototype.f.bind(new Foo(123), 'asdf');
   *
   * Here, the receiver type of f is Foo<T>, but the T is the class's T,
   * not the T of f's template declaration.
   * OTOH, if f had a @this annotation that contained T, T would refer to
   * f's T. We have no way of knowing whether THIS comes from the class or from @this.
   * However, we just go ahead and unify anyway; it is safe to do because we give a unique ID
   * to each type variable. We end up doing redundant work when THIS comes from the class.
   * In the past, before switching to unique IDs for type variables, we used to have a heuristic
   * to decide whether to use the receiver type for unification.
   */
  private ImmutableMap<String, JSType> calcTypeInstantiation(
      Node callNode, Node receiver, Node firstArg,
      FunctionType funType, TypeEnv typeEnv, boolean isFwd) {
    checkState(receiver == null || isFwd);
    List<String> typeParameters = funType.getTypeParameters();
    Multimap<String, JSType> typeMultimap = LinkedHashMultimap.create();
    JSType funRecvType = funType.getThisType();
    if (receiver != null && funRecvType != null) {
      JSType recvType = (JSType) receiver.getTypeI();
      if (recvType == null) {
        EnvTypePair pair = analyzeExprFwd(receiver, typeEnv);
        recvType = pair.type;
        typeEnv = pair.env;
      }
      funRecvType.unifyWith(recvType, typeParameters, typeMultimap);
    }
    Node arg = firstArg;
    int i = 0;
    while (arg != null) {
      EnvTypePair pair = isFwd ? analyzeExprFwd(arg, typeEnv) : analyzeExprBwd(arg, typeEnv);
      funType.getFormalType(i).unifyWith(pair.type, typeParameters, typeMultimap);
      arg = arg.getNext();
      typeEnv = pair.env;
      i++;
    }
    ImmutableMap.Builder<String, JSType> builder = ImmutableMap.builder();
    for (String typeParam : typeParameters) {
      Collection<JSType> types = typeMultimap.get(typeParam);
      if (types.size() > 1) {
        if (isFwd) {
          warnings.add(JSError.make(
              callNode, NOT_UNIQUE_INSTANTIATION,
              Integer.toString(types.size()),
              UniqueNameGenerator.getOriginalName(typeParam),
              types.toString(),
              funType.toString()));
        }
        if (joinTypesWhenInstantiatingGenerics) {
          JSType joinedType = BOTTOM;
          for (JSType t : types) {
            joinedType = JSType.join(joinedType, t);
          }
          builder.put(typeParam, joinedType);
        } else {
          builder.put(typeParam, UNKNOWN);
        }
      } else if (types.size() == 1) {
        JSType t = Iterables.getOnlyElement(types);
        builder.put(typeParam, firstNonBottom(t, UNKNOWN));
      } else {
        // Put ? for any uninstantiated type variables
        builder.put(typeParam, UNKNOWN);
      }
    }
    return builder.build();
  }

  private EnvTypePair analyzeNonStrictComparisonFwd(
      Node expr, TypeEnv inEnv, JSType specializedType) {
    Token tokenType = expr.getToken();
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();

    if (specializedType.isTrueOrTruthy() || specializedType.isFalseOrFalsy()) {
      if (lhs.isTypeOf()) {
        return analyzeSpecializedTypeof(
            lhs, rhs, tokenType, inEnv, specializedType);
      } else if (rhs.isTypeOf()) {
        return analyzeSpecializedTypeof(
            rhs, lhs, tokenType, inEnv, specializedType);
      } else if (isGoogTypeof(lhs)) {
        return analyzeGoogTypeof(lhs, rhs, inEnv, specializedType);
      } else if (isGoogTypeof(rhs)) {
        return analyzeGoogTypeof(rhs, lhs, inEnv, specializedType);
      }
    }

    EnvTypePair lhsPair = analyzeExprFwd(lhs, inEnv);
    EnvTypePair rhsPair = analyzeExprFwd(rhs, lhsPair.env);
    // This env may contain types that have been tightened after nullable deref.
    TypeEnv preciseEnv = rhsPair.env;
    JSType lhsType = lhsPair.type;
    JSType rhsType = rhsPair.type;

    if ((tokenType == Token.EQ && specializedType.isTrueOrTruthy())
        || (tokenType == Token.NE && specializedType.isFalseOrFalsy())) {
      if (lhsType.isNullOrUndef()) {
        rhsPair = analyzeExprFwd(
            rhs, preciseEnv, UNKNOWN, NULL_OR_UNDEFINED);
      } else if (rhsType.isNullOrUndef()) {
        lhsPair = analyzeExprFwd(
            lhs, preciseEnv, UNKNOWN, NULL_OR_UNDEFINED);
        rhsPair = analyzeExprFwd(rhs, lhsPair.env);
      } else if (!NULL.isSubtypeOf(lhsType) && !UNDEFINED.isSubtypeOf(lhsType)) {
        rhsType = rhsType.removeType(NULL_OR_UNDEFINED);
        rhsPair = analyzeExprFwd(rhs, preciseEnv, UNKNOWN, rhsType);
      } else if (!NULL.isSubtypeOf(rhsType) && !UNDEFINED.isSubtypeOf(rhsType)) {
        lhsType = lhsType.removeType(NULL_OR_UNDEFINED);
        lhsPair = analyzeExprFwd(lhs, preciseEnv, UNKNOWN, lhsType);
        rhsPair = analyzeExprFwd(rhs, lhsPair.env);
      }
    } else if ((tokenType == Token.EQ && specializedType.isFalseOrFalsy())
        || (tokenType == Token.NE && specializedType.isTrueOrTruthy())) {
      if (lhsType.isNullOrUndef()) {
        rhsType = rhsType.removeType(NULL_OR_UNDEFINED);
        rhsPair = analyzeExprFwd(rhs, preciseEnv, UNKNOWN, rhsType);
      } else if (rhsType.isNullOrUndef()) {
        lhsType = lhsType.removeType(NULL_OR_UNDEFINED);
        lhsPair = analyzeExprFwd(lhs, preciseEnv, UNKNOWN, lhsType);
        rhsPair = analyzeExprFwd(rhs, lhsPair.env);
      }
    }
    rhsPair.type = BOOLEAN;
    return rhsPair;
  }

  private EnvTypePair analyzeObjLitFwd(
      Node objLit, TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    if (NodeUtil.isEnumDecl(objLit.getParent())) {
      return analyzeEnumObjLitFwd(objLit, inEnv, requiredType);
    }
    JSDocInfo jsdoc = objLit.getJSDocInfo();
    boolean isStruct = jsdoc != null && jsdoc.makesStructs();
    boolean isDict = jsdoc != null && jsdoc.makesDicts();
    TypeEnv env = inEnv;
    JSType result = pickReqObjType(objLit);
    for (Node prop : objLit.children()) {
      if (isStruct && prop.isQuotedString()) {
        warnings.add(JSError.make(prop, ILLEGAL_OBJLIT_KEY, "struct"));
      } else if (isDict && !prop.isQuotedString()) {
        warnings.add(JSError.make(prop, ILLEGAL_OBJLIT_KEY, "dict"));
      }
      // We can't assign to a getter to change its value.
      // We can't do a prop access on a setter.
      // So, we don't associate pname with a getter/setter.
      // We add a property with a name that's weird enough to hopefully avoid
      // an accidental clash.
      if (prop.isGetterDef() || prop.isSetterDef()) {
        String pname = NodeUtil.getObjectLitKeyName(prop);
        EnvTypePair pair = analyzeExprFwd(prop.getFirstChild(), env);
        FunctionType funType = pair.type.getFunType();
        checkNotNull(funType);
        String specialPropName;
        JSType propType;
        if (prop.isGetterDef()) {
          specialPropName = commonTypes.createGetterPropName(pname);
          propType = funType.getReturnType();
        } else {
          specialPropName = commonTypes.createSetterPropName(pname);
          propType = pair.type;
        }
        result = result.withProperty(new QualifiedName(specialPropName), propType);
        env = pair.env;
      } else {
        Node pnameNode = NodeUtil.getObjectLitKeyNode(prop);
        if (pnameNode == null) {
          // pnameNode is null when prop is a computed prop does not have a String node key.
          // Just type-check the prop, then move on to the next property.
          env = analyzeExprFwd(prop, env).env;
          continue;
        }
        QualifiedName qname = new QualifiedName(pnameNode.getString());
        JSType jsdocType = symbolTable.getPropDeclaredType(prop);
        JSType reqPtype;
        JSType specPtype;
        if (jsdocType != null) {
          reqPtype = specPtype = jsdocType;
        } else if (requiredType.mayHaveProp(qname)) {
          reqPtype = specPtype = requiredType.getProp(qname);
          if (specializedType.mayHaveProp(qname)) {
            specPtype = specializedType.getProp(qname);
          }
        } else {
          reqPtype = specPtype = UNKNOWN;
        }
        EnvTypePair pair = analyzeExprFwd(prop, env, reqPtype, specPtype);
        if (jsdocType != null) {
          // First declare it; then set the maybe more precise inferred type
          result = result.withDeclaredProperty(qname, jsdocType, false);
          if (!pair.type.isSubtypeOf(jsdocType)) {
            warnings.add(JSError.make(
                prop, INVALID_OBJLIT_PROPERTY_TYPE,
                errorMsgWithTypeDiff(jsdocType, pair.type)));
            pair.type = jsdocType;
          }
        }
        result = result.withProperty(qname, pair.type);
        env = pair.env;
      }
    }
    result = mayAdjustObjLitType(objLit, jsdoc, inEnv, result);
    return new EnvTypePair(env, result);
  }

  /**
   * If the object literal is lended, or assigned to a prototype, find a better
   * type for it than the object-literal type.
   */
  private JSType mayAdjustObjLitType(
      Node objLit, JSDocInfo jsdoc, TypeEnv env, JSType originalType) {
    Node parent = objLit.getParent();
    QualifiedName classqname = null;
    if (parent.isAssign() && NodeUtil.isPrototypeAssignment(parent.getFirstChild())) {
      classqname = QualifiedName.fromNode(parent.getFirstFirstChild());
    } else if (jsdoc != null && jsdoc.getLendsName() != null) {
      QualifiedName lendsQname = QualifiedName.fromQualifiedString(jsdoc.getLendsName());
      if (lendsQname.getRightmostName().equals("prototype")) {
        classqname = lendsQname.getAllButRightmost();
      }
    } else if (parent.isCall() && this.convention.getObjectLiteralCast(parent) != null) {
      ObjectLiteralCast cast = this.convention.getObjectLiteralCast(parent);
      if (cast.typeName != null) {
        classqname = QualifiedName.fromQualifiedString(cast.typeName);
      }
    }
    if (classqname != null) {
      JSType classType = envGetTypeOfQname(env, classqname);
      if (classType != null) {
        FunctionType clazz = classType.getFunTypeIfSingletonObj();
        JSType instance = clazz == null ? null : clazz.getInstanceTypeOfCtor();
        if (instance != null) {
          return instance.getPrototypeObject();
        }
      }
    }
    return originalType;
  }

  private EnvTypePair analyzeEnumObjLitFwd(
      Node objLit, TypeEnv inEnv, JSType requiredType) {
    // We warn about malformed enum declarations in GlobalTypeInfo,
    // so we ignore them here.
    if (objLit.getFirstChild() == null) {
      return new EnvTypePair(inEnv, requiredType);
    }
    String pname = NodeUtil.getObjectLitKeyName(objLit.getFirstChild());
    JSType enumeratedType =
        requiredType.getProp(new QualifiedName(pname)).getEnumeratedTypeOfEnumElement();
    if (enumeratedType == null) {
      // enumeratedType is null only if there is some other type error
      return new EnvTypePair(inEnv, requiredType);
    }
    TypeEnv env = inEnv;
    for (Node prop : objLit.children()) {
      EnvTypePair pair = analyzeExprFwd(prop, env, enumeratedType);
      if (!pair.type.isSubtypeOf(enumeratedType)) {
        warnings.add(JSError.make(
            prop, INVALID_OBJLIT_PROPERTY_TYPE,
            errorMsgWithTypeDiff(enumeratedType, pair.type)));
      }
      env = pair.env;
    }
    return new EnvTypePair(env, requiredType);
  }

  private EnvTypePair analyzeTypePredicate(
      Node call, String typeHint, TypeEnv inEnv, JSType specializedType) {
    analyzeExprFwdIgnoreResult(call.getFirstChild(), inEnv);
    int numArgs = call.getChildCount() - 1;
    if (numArgs != 1) {
      warnings.add(JSError.make(call, WRONG_ARGUMENT_COUNT,
              call.getFirstChild().getQualifiedName(),
              Integer.toString(numArgs), "1", "1"));
      return analyzeInvocationArgsFwdWhenError(call, inEnv);
    }
    EnvTypePair pair = analyzeExprFwd(call.getLastChild(), inEnv);
    if (specializedType.isTrueOrTruthy() || specializedType.isFalseOrFalsy()) {
      pair = analyzeExprFwd(call.getLastChild(), inEnv, UNKNOWN,
          predicateTransformType(typeHint, specializedType, pair.type));
    }
    pair.type = BOOLEAN;
    return pair;
  }

  private EnvTypePair analyzeGoogTypeof(
      Node typeof, Node typeString, TypeEnv inEnv, JSType specializedType) {
    analyzeExprFwdIgnoreResult(typeString, inEnv);
    return analyzeTypePredicate(typeof,
        typeString.isString() ? typeString.getString() : "",
        inEnv, specializedType);
  }

  private EnvTypePair analyzePropertyTestCallFwd(
      Node call, TypeEnv inEnv, JSType specializedType) {
    analyzeExprFwdIgnoreResult(call.getFirstChild(), inEnv);
    return analyzeTypePredicate(call,
        call.getFirstChild().getLastChild().getString(),
        inEnv, specializedType);
  }

  // typeHint can come from goog.typeOf or from any function
  // in CodingConvention's isPropertyTestFunction.
  private JSType predicateTransformType(
      String typeHint, JSType booleanContext, JSType beforeType) {
    switch (typeHint) {
      case "array":
      case "isArray": {
        JSType arrayType = commonTypes.getArrayInstance();
        if (arrayType.isUnknown()) {
          return UNKNOWN;
        }
        return booleanContext.isTrueOrTruthy()
            ? arrayType : beforeType.removeType(arrayType);
      }
      case "isArrayLike":
        return TOP_OBJECT.withProperty(new QualifiedName("length"), NUMBER);
      case "boolean":
      case "isBoolean":
        return booleanContext.isTrueOrTruthy()
          ? BOOLEAN : beforeType.removeType(BOOLEAN);
      case "function":
      case "isFunction":
        return booleanContext.isTrueOrTruthy()
          ? commonTypes.looseTopFunction()
          : beforeType.removeType(commonTypes.topFunction());
      case "null":
      case "isNull":
        return booleanContext.isTrueOrTruthy()
          ? NULL : beforeType.removeType(NULL);
      case "number":
      case "isNumber":
        return booleanContext.isTrueOrTruthy()
          ? NUMBER : beforeType.removeType(NUMBER);
      case "string":
      case "isString":
        return booleanContext.isTrueOrTruthy()
          ? STRING : beforeType.removeType(STRING);
      case "isDef":
        return booleanContext.isTrueOrTruthy()
            ? beforeType.removeType(UNDEFINED) : UNDEFINED;
      case "isDefAndNotNull":
        return booleanContext.isTrueOrTruthy()
            ? beforeType.removeType(NULL_OR_UNDEFINED) : NULL_OR_UNDEFINED;
      case "isObject":
        // typeof(null) === 'object', but goog.isObject(null) is false
        return booleanContext.isTrueOrTruthy()
            ? TOP_OBJECT : beforeType.removeType(TOP_OBJECT);
      case "object":
        // goog.typeOf(expr) === 'object' is true only for non-function objects.
        // Just do sth simple here.
        return UNKNOWN;
      case "undefined":
        return booleanContext.isTrueOrTruthy()
            ? UNDEFINED : beforeType.removeType(UNDEFINED);
      default:
        // For when we can't figure out the type name used with goog.typeOf.
        return UNKNOWN;
    }
  }

  /**
   * In certain cases, tightens the type of a variable to avoid warning.
   *
   * The intent is to be looser about warnings in the case when a value is passed to a function
   * (as opposed to being created locally), because then we have less information about the value.
   * The accurate way to do this is with taint tracking: mark types so that we can track where a
   * type comes from (local or not).
   * Without tainting (e.g., we used to have locations on types), we approximate this by checking
   * the variable name.
   */
  private boolean tightenNameTypeAndDontWarn(
      String varName, Node n, JSType declared, JSType inferred, JSType required) {
    boolean isSpecializableTop = declared != null && declared.isTop()
        && (!inferred.isTop() || NodeUtil.isPropertyTest(compiler, n.getParent()));
    boolean fuzzyDeclaration = declared == null || declared.isUnknown();
    return (fuzzyDeclaration || isSpecializableTop)
        && (varName == null || this.currentScope.isFormalParam(varName)
            || this.currentScope.isOuterVar(varName))
        // If required is loose, it's easier for it to be a subtype of inferred.
        // We only tighten the type if the non-loose required is also a subtype.
        && required.isNonLooseSubtypeOf(inferred);
  }

  /**
   * In certain cases, tightens the type of a property to avoid warning.
   *
   * Don't do it if the receiver is a namespace or a prototype object: these are more "static"
   * than some instance type, so we can be stricter.
   * The heuristics for tightening the type are similar to @see #tightenNameTypeAndDontWarn.
   */
  private boolean tightenPropertyTypeAndDontWarn(String recvName, Node propAccessNode,
      JSType recvType, JSType propDeclType, JSType propInferredType, JSType propRequiredType) {
    if (recvType != null && (recvType.isNamespace() || recvType.isPrototypeObject())) {
      return false;
    }
    boolean isSpecializableTop = propDeclType != null && propDeclType.isTop()
        && (!propInferredType.isTop() || NodeUtil.isPropertyTest(compiler, propAccessNode));
    boolean fuzzyDeclaration = propDeclType == null || propDeclType.isUnknown();
    return (fuzzyDeclaration || isSpecializableTop)
        && (recvName == null || currentScope.isFormalParam(recvName)
            || currentScope.isOuterVar(recvName))
        // If required is loose, it's easier for it to be a subtype of inferred.
        // We only tighten the type if the non-loose required is also a subtype.
        // Otherwise, we would be skipping warnings too often.
        // This is important b/c analyzePropAccess & analyzePropLvalue introduce
        // loose objects, even if there are no undeclared formals.
        && propRequiredType.isNonLooseSubtypeOf(propInferredType);
  }

  private static String errorMsgWithTypeDiff(JSType expected, JSType found) {
    MismatchInfo mismatch = JSType.whyNotSubtypeOf(found, expected);
    ToStringContext ctx = ToStringContext.disambiguateTypeVars(expected, found);
    if (mismatch == null) {
      return "Expected : " + expected.toString(ctx) + "\n"
          + "Found    : " + found.toString(ctx) + "\n";
    }
    StringBuilder builder =
        new StringBuilder("Expected : ")
            .append(expected.toString(ctx))
            .append("\n" + "Found    : ")
            .append(found.toString(ctx))
            .append("\n" + "More details:\n");
    if (mismatch.isPropMismatch()) {
      builder
          .append("Incompatible types for property ")
          .append(mismatch.getPropName())
          .append(".\n" + "Expected : ")
          .append(mismatch.getExpectedType().toString(ctx))
          .append("\n" + "Found    : ")
          .append(mismatch.getFoundType().toString(ctx));
    } else if (mismatch.isMissingProp()) {
      builder.append("The found type is missing property ").append(mismatch.getPropName());
    } else if (mismatch.wantedRequiredFoundOptional()) {
      builder
          .append("In found type, property ")
          .append(mismatch.getPropName())
          .append(" is optional but should be required.");
    } else if (mismatch.isArgTypeMismatch()) {
      builder
          .append(
              "The expected and found types are functions which have"
                  + " incompatible types for argument ")
          .append(mismatch.getArgIndex() + 1)
          .append(".\n" + "Expected a supertype of : ")
          .append(mismatch.getExpectedType().toString(ctx))
          .append("\n" + "but found               : ")
          .append(mismatch.getFoundType().toString(ctx));
    } else if (mismatch.isRetTypeMismatch()) {
      builder
          .append(
              "The expected and found types are functions which have"
                  + " incompatible return types.\n"
                  + "Expected a subtype of : ")
          .append(mismatch.getExpectedType().toString(ctx))
          .append("\n" + "but found             : ")
          .append(mismatch.getFoundType().toString(ctx));
    } else if (mismatch.isUnionTypeMismatch()) {
      builder
          .append("The found type is a union that includes an unexpected type: ")
          .append(mismatch.getFoundType().toString(ctx));
    }
    return builder.toString();
  }

  //////////////////////////////////////////////////////////////////////////////
  // These functions return true iff they produce a warning

  private boolean mayWarnAboutNonObject(
      Node receiver, JSType recvType, JSType specializedType) {
    // Can happen for IF tests that are never true
    if (recvType.isBottom()) {
      return true;
    }
    // The warning depends on whether we are testing for the existence of a
    // property.
    boolean isNotAnObject = !JSType.haveCommonSubtype(recvType, TOP_OBJECT);
    boolean mayNotBeAnObject = !recvType.isSubtypeOf(TOP_OBJECT);
    if (isNotAnObject
        || (!specializedType.isTrueOrTruthy() && !specializedType.isFalseOrFalsy()
            && mayNotBeAnObject)) {
      warnings.add(JSError.make(receiver, PROPERTY_ACCESS_ON_NONOBJECT,
              getPropNameForErrorMsg(receiver.getParent()),
              recvType.toString()));
      return true;
    }
    return false;
  }

  private String getPropNameForErrorMsg(Node propAccessNode) {
    checkArgument(propAccessNode.isGetProp() || propAccessNode.isGetElem());
    Node propNode = propAccessNode.getLastChild();
    if (propNode.isString()) {
      return propNode.getString();
    } else if (propNode.isQualifiedName()) {
      return "[" + propNode.getQualifiedName() + "]";
    }
    return "[unknown property]";
  }

  private boolean mayWarnAboutStructPropAccess(Node obj, JSType type) {
    if (type.mayBeStruct()) {
      warnings.add(JSError.make(obj, ILLEGAL_PROPERTY_ACCESS, "'[]'", "struct"));
      return true;
    }
    return false;
  }

  private boolean mayWarnAboutDictPropAccess(Node obj, JSType type) {
    if (type.mayBeDict()) {
      warnings.add(JSError.make(obj, ILLEGAL_PROPERTY_ACCESS, "'.'", "dict"));
      return true;
    }
    return false;
  }

  private boolean mayWarnAboutPropCreation(
      QualifiedName pname, Node getProp, JSType recvType) {
    checkArgument(getProp.isGetProp());
    // For inferred formals used as objects, we don't warn about property
    // creation. Consider:
    //   function f(obj) { obj.prop = 123; }
    // f should accept objects without prop, so we don't require that obj
    // already have prop.
    if (recvType.isStructWithoutProp(pname)) {
      warnings.add(JSError.make(getProp, ILLEGAL_PROPERTY_CREATION, pname.toString()));
      return true;
    }
    return false;
  }

  private boolean mayWarnAboutInexistentProp(Node propAccessNode,
      JSType recvType, QualifiedName propQname) {
    checkState(propAccessNode.isGetProp() || propAccessNode.isGetElem());
    String pname = propQname.toString();

    if (propAccessNode.isGetElem()
        // Loose types always "have" the properties accessed on them, because of
        // type inference. If the property is not defined anywhere though,
        // we still want to warn in that case.
        || (!recvType.isLoose() && recvType.hasProp(propQname))) {
      return false;
    }

    if (recvType.isUnknown() || recvType.isTrueOrTruthy() || recvType.isLoose()
        || (allowPropertyOnSubtypes
            && (recvType.mayContainUnknownObject() || recvType.isIObject()))) {
      if (symbolTable.isPropertyDefined(pname)) {
        return false;
      }
      warnings.add(JSError.make(
          propAccessNode, INEXISTENT_PROPERTY, pname, "any type in the program"));
      return true;
    }

    if (allowPropertyOnSubtypes && !recvType.isStruct()
        && recvType.isPropDefinedOnSubtype(propQname)) {
      return false;
    }

    // To avoid giant types in the error message, we use a heuristic:
    // if the receiver is a qualified name whose type is too long, we print
    // the qualified name instead.
    String recvTypeAsString = recvType.toString();
    Node recv = propAccessNode.getFirstChild();
    String errorMsg;
    if (!recv.isQualifiedName()) {
      errorMsg = recvTypeAsString;
    } else if (recvTypeAsString.length() > 100) {
      errorMsg = recv.getQualifiedName();
    } else {
      errorMsg = recv.getQualifiedName() + " of type " + recvTypeAsString;
    }
    DiagnosticType warningType = recvType.mayHaveProp(propQname)
        ? POSSIBLY_INEXISTENT_PROPERTY : INEXISTENT_PROPERTY;
    warnings.add(JSError.make(propAccessNode, warningType, pname, errorMsg));
    return true;
  }

  private boolean mayWarnAboutConst(Node n) {
    Node lhs = n.getFirstChild();
    if (lhs.isName() && this.currentScope.isConstVar(lhs.getString())) {
      warnings.add(JSError.make(n, CONST_REASSIGNED));
      return true;
    }
    return false;
  }

  private boolean mayWarnAboutConstProp(
      Node propAccess, JSType recvType, QualifiedName pname) {
    if (recvType.hasConstantProp(pname) &&
        !propAccess.getBooleanProp(Node.CONSTANT_PROPERTY_DEF)) {
      warnings.add(JSError.make(propAccess, CONST_PROPERTY_REASSIGNED));
      return true;
    }
    return false;
  }

  private void mayWarnAboutGlobalThis(Node thisExpr) {
    checkArgument(thisExpr.isThis());
    if (this.currentScope.isTopLevel() || !this.currentScope.hasThis()) {
      Node parent = thisExpr.getParent();
      if ((parent.isGetProp() || parent.isGetElem())
          // Don't warn for callbacks. Most of them are not annotated but THIS is
          // bound to a legitimate object at runtime. They do lose typechecking
          // for THIS however, but we won't warn.
          && !NodeUtil.isCallOrNewArgument(this.currentScope.getRoot())) {
        warnings.add(JSError.make(thisExpr, GLOBAL_THIS));
      }
    }
  }

  private boolean mayWarnAboutBadIObjectIndex(Node n, JSType iobjectType,
      JSType foundIndexType, JSType requiredIndexType) {
    if (requiredIndexType.isBottom()) {
      warnings.add(JSError.make(
          n, BOTTOM_INDEX_TYPE, iobjectType.toString()));
      return true;
    }
    if (!foundIndexType.isSubtypeOf(requiredIndexType)) {
      warnings.add(JSError.make(
          n, INVALID_INDEX_TYPE,
          errorMsgWithTypeDiff(requiredIndexType, foundIndexType)));
      return true;
    }
    return false;
  }

  // Don't always wrap getIndexedType calls with this function. Only do it when
  // you want to pass around the result type and it has to be non-null.
  private JSType getIndexedTypeOrUnknown(JSType t) {
    JSType tmp = t.getIndexedType();
    return firstNonNull(tmp, UNKNOWN);
  }

  private EnvTypePair analyzePropAccessFwd(Node receiver, String pname,
      TypeEnv inEnv, JSType requiredType, JSType specializedType) {
    QualifiedName propQname = new QualifiedName(pname);
    Node propAccessNode = receiver.getParent();
    EnvTypePair pair;
    JSType reqObjType = pickReqObjType(propAccessNode);
    JSType recvReqType;
    JSType recvSpecType;

    // First, analyze the receiver object.
    if ((NodeUtil.isPropertyTest(compiler, propAccessNode) && !specializedType.isFalseOrFalsy())
        || (NodeUtil.isPropertyAbsenceTest(propAccessNode) && !specializedType.isTrueOrTruthy())
        // The NodeUtil method doesn't use types, so it can't see that the
        // else branch of "if (!x.prop)" is a property test.
        || specializedType.isTrueOrTruthy()) {
      recvReqType = reqObjType;
      pair = analyzeExprFwd(receiver, inEnv, recvReqType);
      JSType subtypeWithProp = pair.type.findSubtypeWithProp(propQname);
      if (subtypeWithProp.isBottom()) {
        recvSpecType = reqObjType;
      } else {
        recvSpecType = subtypeWithProp;
      }
      if (specializedType.isTrueOrTruthy()) {
        // This handles cases like: if (x.prop1 && x.prop1.prop2) { ... }
        // In the THEN branch, the only thing we know about x.prop1 is that it
        // has a truthy property, so x.prop1 should be a loose object to avoid
        // spurious warnings.
        recvSpecType = recvSpecType.withLoose().withProperty(propQname, specializedType);
      } else {
        recvSpecType = recvSpecType.withProperty(propQname, specializedType);
      }
    } else if (specializedType.isFalseOrFalsy()) {
      recvReqType = recvSpecType = reqObjType;
    } else {
      recvReqType = reqObjType.withProperty(propQname, requiredType);
      recvSpecType = reqObjType.withProperty(propQname, specializedType);
    }
    pair = analyzeExprFwd(receiver, inEnv, recvReqType, recvSpecType);
    pair = mayWarnAboutNullableReferenceAndTighten(receiver, pair.type, recvSpecType, pair.env);
    JSType recvType = pair.type.autobox();
    if (recvType.isUnknown() || recvType.isTrueOrTruthy()) {
      mayWarnAboutInexistentProp(propAccessNode, recvType, propQname);
      return new EnvTypePair(pair.env, requiredType);
    }
    if (mayWarnAboutNonObject(receiver, recvType, specializedType)) {
      return new EnvTypePair(pair.env, requiredType);
    }
    FunctionType ft = recvType.getFunTypeIfSingletonObj();
    if (ft != null && (pname.equals("call") || pname.equals("apply"))) {
      if (ft.isAbstract()) {
        // We don't check if the parent of the property access is a call node.
        // This catches calls that are a few nodes away, and also warns on .call/.apply
        // accesses that do not result in calls (these should be very rare).
        String funName = receiver.isQualifiedName() ? receiver.getQualifiedName() : "";
        warnings.add(JSError.make(propAccessNode, ABSTRACT_SUPER_METHOD_NOT_CALLABLE, funName));
      }
      return new EnvTypePair(pair.env,
          pname.equals("call")
          ? commonTypes.fromFunctionType(ft.transformByCallProperty())
          : commonTypes.fromFunctionType(ft.transformByApplyProperty()));
    }
    if (this.convention.isSuperClassReference(pname)) {
      if (ft != null && ft.isUniqueConstructor()) {
        JSType result = ft.getSuperPrototype();
        pair.type = firstNonNull(result, UNDEFINED);
        return pair;
      }
    }
    if (propAccessNode.isGetProp() &&
        mayWarnAboutDictPropAccess(receiver, recvType)) {
      return new EnvTypePair(pair.env, requiredType);
    }
    if (recvType.isTop()) {
      recvType = TOP_OBJECT;
    }
    if (propAccessNode.getParent().isDelProp()
        && recvType.hasConstantProp(propQname)) {
      warnings.add(JSError.make(
          propAccessNode.getParent(), CONST_PROPERTY_DELETED, pname));
    }
    // Then, analyze the property access.
    QualifiedName getterPname = new QualifiedName(commonTypes.createGetterPropName(pname));
    if (recvType.hasProp(getterPname)) {
      return new EnvTypePair(pair.env, recvType.getProp(getterPname));
    }
    JSType resultType = recvType.getProp(propQname);
    if (resultType != null && resultType.isBottom()) {
      warnings.add(JSError.make(propAccessNode, BOTTOM_PROP,
              pname, recvType.toString()));
      return new EnvTypePair(pair.env, UNKNOWN);
    }
    if (!propAccessNode.getParent().isExprResult()
        && !specializedType.isTrueOrTruthy()
        && !specializedType.isFalseOrFalsy()
        && !recvType.mayBeDict()
        && !mayWarnAboutInexistentProp(propAccessNode, recvType, propQname)
        && recvType.hasProp(propQname)
        && !resultType.isSubtypeOf(requiredType)
        && tightenPropertyTypeAndDontWarn(
            receiver.isName() ? receiver.getString() : null,
            propAccessNode,
            recvType,
            recvType.getDeclaredProp(propQname),
            resultType, requiredType)) {
      // Tighten the inferred type and don't warn.
      // See analyzeNameFwd for explanation about types as lower/upper bounds.
      resultType = resultType.specialize(requiredType);
      LValueResultFwd lvr = analyzeLValueFwd(propAccessNode, inEnv, resultType);
      TypeEnv updatedEnv = updateLvalueTypeInEnv(lvr.env, propAccessNode, lvr.ptr, resultType);
      return new EnvTypePair(updatedEnv, resultType);
    }
    // We've already warned about missing props, and never want to return null.
    if (resultType == null) {
      resultType = UNKNOWN;
    }
    // Any potential type mismatch will be caught by the context
    return new EnvTypePair(pair.env, resultType);
  }

  private TypeEnv updateLvalueTypeInEnv(
      TypeEnv env, Node lvalue, QualifiedName qname, JSType type) {
    checkNotNull(type);
    switch (lvalue.getToken()) {
      case NAME:
        return envPutType(env, lvalue.getString(), type);
      case THIS: {
        JSType t = envGetType(env, THIS_ID);
        // Don't specialize THIS in functions where it is unknown.
        return t == null ? env : envPutType(env, THIS_ID, type);
      }
      case VAR: // Can happen iff its parent is a for/in or for/of.
        Preconditions.checkState(lvalue.getParent().isForIn() || lvalue.getParent().isForOf());
        return envPutType(env, lvalue.getFirstChild().getString(), type);
      case GETPROP:
      case GETELEM: {
        if (qname != null) {
          String objName = qname.getLeftmostName();
          QualifiedName props = qname.getAllButLeftmost();
          JSType objType = envGetType(env, objName);
          if (objType == null) {
            // Don't specialize THIS properties in functions where THIS is unknown.
            checkState(objName.equals("this"));
            return env;
          }
          // TODO(dimvar): In analyzeNameFwd/Bwd, we are careful to not
          // specialize namespaces, and we need the same check here. But
          // currently, stopping specialization here causes tests to fail,
          // because specializing the namespace is our way of updating its
          // functions after computing summaries. The better solution is to
          // retain Namespace instances after GTI instead of turning them into
          // ObjectTypes, and then update those with the summaries and stop
          // specializing here.
          env = envPutType(env, objName, objType.withProperty(props, type));
        }
        return env;
      }
      default:
        return env;
    }
  }

  /**
   * Used when analyzing a scope that defines variables used in inner scopes.
   * Returns a type environment that combines the types from all uses of a variable.
   */
  private TypeEnv collectTypesForEscapedVarsFwd(Node n, TypeEnv env) {
    checkArgument(n.isFunction() || (n.isName() && NodeUtil.isInvocationTarget(n)));
    String fnName = n.isFunction() ? symbolTable.getFunInternalName(n) : n.getString();
    NTIScope innerScope = this.currentScope.getScope(fnName);
    FunctionType summary = summaries.get(innerScope).getFunType();
    for (String freeVar : innerScope.getOuterVars()) {
      if (innerScope.getDeclaredTypeOf(freeVar) == null) {
        JSType outerType = envGetType(env, freeVar);
        if (outerType == null) {
          outerType = UNKNOWN;
        }
        JSType innerType = summary.getOuterVarPrecondition(freeVar);
        if (// We don't warn if the innerType is a loose object, because we
            // haven't found an easy way to avoid false positives.
            !innerType.isLoose()
            // If n is a function expression, we don't know where it's called,
            // so we don't warn for uninitialized variables.
            && (n.isName() || (n.isFunction() && !outerType.isUndefined()))
            && !JSType.haveCommonSubtype(outerType, innerType)) {
          warnings.add(JSError.make(n, CROSS_SCOPE_GOTCHA,
                  freeVar, outerType.toString(), innerType.toString()));
        }
        // If n is a callee node, we only want to keep the type in the callee.
        // If n is a function expression, we don't know if it will get called, so we take the
        // types from both scopes into account.
        JSType freeVarType;
        if (n.isFunction()) {
          // If the type in our current scope is more precise, then trust it. The variable is
          // defined in this scope, and it's more likely that this type is correct.
          if (!outerType.isNullOrUndef() // only keep outerType for initialized variables
              && !outerType.isUnknown() && !innerType.isUnknown()
              && outerType.isSubtypeOf(innerType)) {
            freeVarType = outerType;
          } else {
            freeVarType = JSType.join(innerType, outerType);
          }
        } else {
          freeVarType = innerType;
        }
        env = envPutType(env, freeVar, freeVarType);
      }
    }
    return env;
  }

  private EnvTypePair analyzeLooseCallNodeFwd(
      Node callNode, TypeEnv inEnv, JSType requiredType) {
    checkArgument(callNode.isCall() || callNode.isNew());
    Node callee = callNode.getFirstChild();
    FunctionTypeBuilder builder = new FunctionTypeBuilder(this.commonTypes);
    TypeEnv tmpEnv = inEnv;
    for (Node arg = callee.getNext(); arg != null; arg = arg.getNext()) {
      EnvTypePair pair = analyzeExprFwd(arg, tmpEnv);
      tmpEnv = pair.env;
      builder.addReqFormal(pair.type);
    }
    JSType looseRetType = requiredType.isUnknown() ? BOTTOM : requiredType;
    JSType looseFunctionType = commonTypes.fromFunctionType(
        builder.addRetType(looseRetType).addLoose().buildFunction());
    // Unsound if the arguments and callee have interacting side effects
    EnvTypePair calleePair = analyzeExprFwd(
        callee, tmpEnv, commonTypes.topFunction(), looseFunctionType);
    FunctionType calleeType = calleePair.type.getFunType();
    JSType result = calleeType.getReturnType();
    return new EnvTypePair(calleePair.env,
        isImpreciseType(result) ? requiredType : result);
  }

  private static boolean isImpreciseType(JSType t) {
    return t.isBottom() || t.isTop() || t.isUnknown() || t.isUnion()
        || t.isTrueOrTruthy() || t.isFalseOrFalsy()
        || t.isLoose() || t.isNonClassyObject();
  }

  private EnvTypePair analyzeLooseCallNodeBwd(
      Node callNode, TypeEnv outEnv, JSType retType) {
    checkArgument(callNode.isCall() || callNode.isNew());
    checkNotNull(retType);

    Node callee = callNode.getFirstChild();
    TypeEnv tmpEnv = outEnv;
    FunctionTypeBuilder builder = new FunctionTypeBuilder(this.commonTypes);
    Node target = callNode.getFirstChild();
    for (Node arg = callNode.getLastChild(); arg != target; arg = arg.getPrevious()) {
      EnvTypePair pair = analyzeExprBwd(arg, tmpEnv);
      JSType argType = pair.type;
      tmpEnv = pair.env;
      // May wait until FWD to get more precise argument types.
      builder.addReqFormalToFront(isImpreciseType(argType) ? BOTTOM : argType);
    }

    JSType looseRetType = retType.isUnknown() ? BOTTOM : retType;
    JSType looseFunctionType = commonTypes.fromFunctionType(
        builder.addRetType(looseRetType).addLoose().buildFunction());
    println("loose function type is ", looseFunctionType);
    EnvTypePair calleePair = analyzeExprBwd(callee, tmpEnv, looseFunctionType);
    return new EnvTypePair(calleePair.env, retType);
  }

  private EnvTypePair analyzeExprBwd(Node expr, TypeEnv outEnv) {
    return analyzeExprBwd(expr, outEnv, UNKNOWN);
  }

  /**
   * For now, we won't emit any warnings bwd.
   */
  private EnvTypePair analyzeExprBwd(
      Node expr, TypeEnv outEnv, JSType requiredType) {
    Preconditions.checkArgument(requiredType != null, "Required type null at: %s", expr);
    checkArgument(!requiredType.isBottom());
    switch (expr.getToken()) {
      case EMPTY: // can be created by a FOR with empty condition
        return new EnvTypePair(outEnv, UNKNOWN);
      case FUNCTION: {
        String fnName = symbolTable.getFunInternalName(expr);
        return new EnvTypePair(outEnv, envGetType(outEnv, fnName));
      }
      case FALSE:
      case NULL:
      case NUMBER:
      case STRING:
      case TRUE:
        return new EnvTypePair(outEnv, scalarValueToType(expr.getToken()));
      case OBJECTLIT:
        return analyzeObjLitBwd(expr, outEnv, requiredType);
      case THIS: {
        // TODO(blickly): Infer a loose type for THIS if we're in a function.
        if (!this.currentScope.hasThis()) {
          return new EnvTypePair(outEnv, UNKNOWN);
        }
        JSType thisType = this.currentScope.getDeclaredTypeOf(THIS_ID);
        return new EnvTypePair(outEnv, thisType);
      }
      case SUPER:
        // NOTE(dimvar): analyzing SUPER in the backward direction doesn't give
        // us anything useful at the moment.
        return new EnvTypePair(outEnv, UNKNOWN);
      case NAME:
        return analyzeNameBwd(expr, outEnv, requiredType);
      case INC:
      case DEC:
      case BITNOT:
      case NEG: // Unary operations on numbers
        return analyzeExprBwd(expr.getFirstChild(), outEnv, NUMBER);
      case POS: {
        EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), outEnv);
        pair.type = NUMBER;
        return pair;
      }
      case TYPEOF: {
        EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), outEnv);
        pair.type = STRING;
        return pair;
      }
      case INSTANCEOF: {
        TypeEnv env = analyzeExprBwd(expr.getLastChild(), outEnv, commonTypes.topFunction()).env;
        EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), env);
        pair.type = BOOLEAN;
        return pair;
      }
      case BITOR:
      case BITAND:
      case BITXOR:
      case DIV:
      case EXPONENT:
      case LSH:
      case MOD:
      case MUL:
      case RSH:
      case SUB:
      case URSH:
        return analyzeBinaryNumericOpBwd(expr, outEnv);
      case ADD:
        return analyzeAddBwd(expr, outEnv, requiredType);
      case OR:
      case AND:
        return analyzeLogicalOpBwd(expr, outEnv);
      case SHEQ:
      case SHNE:
      case EQ:
      case NE:
        return analyzeEqNeBwd(expr, outEnv);
      case LT:
      case GT:
      case LE:
      case GE:
        return analyzeLtGtBwd(expr, outEnv);
      case ASSIGN:
        return analyzeAssignBwd(expr, outEnv, requiredType);
      case ASSIGN_ADD:
        return analyzeAssignAddBwd(expr, outEnv, requiredType);
      case ASSIGN_BITOR:
      case ASSIGN_BITXOR:
      case ASSIGN_BITAND:
      case ASSIGN_LSH:
      case ASSIGN_RSH:
      case ASSIGN_URSH:
      case ASSIGN_SUB:
      case ASSIGN_MUL:
      case ASSIGN_DIV:
      case ASSIGN_MOD:
      case ASSIGN_EXPONENT:
        return analyzeAssignNumericOpBwd(expr, outEnv);
      case GETPROP: {
          checkState(!NodeUtil.isAssignmentOp(expr.getParent()) || !NodeUtil.isLValue(expr));
          if (expr.getBooleanProp(Node.ANALYZED_DURING_GTI)) {
            return new EnvTypePair(outEnv, requiredType);
          }
          return analyzePropAccessBwd(
              expr.getFirstChild(), expr.getLastChild().getString(), outEnv, requiredType);
        }
      case HOOK:
        return analyzeHookBwd(expr, outEnv, requiredType);
      case CALL:
      case NEW:
      case TAGGED_TEMPLATELIT:
        return analyzeInvocationBwd(expr, outEnv, requiredType);
      case COMMA: {
        EnvTypePair pair = analyzeExprBwd(
            expr.getLastChild(), outEnv, requiredType);
        pair.env = analyzeExprBwd(expr.getFirstChild(), pair.env).env;
        return pair;
      }
      case NOT: {
        EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), outEnv);
        pair.type = pair.type.negate();
        return pair;
      }
      case GETELEM:
        return analyzeGetElemBwd(expr, outEnv, requiredType);
      case VOID: {
        EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), outEnv);
        pair.type = UNDEFINED;
        return pair;
      }
      case IN:
        return analyzeInBwd(expr, outEnv);
      case DELPROP: {
        EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), outEnv);
        pair.type = BOOLEAN;
        return pair;
      }
      case VAR: { // Can happen iff its parent is a for/in or for/of.
        Node vdecl = expr.getFirstChild();
        String name = vdecl.getString();
        // For/in and for/of can never have rhs of its VAR
        checkState(!vdecl.hasChildren());
        return new EnvTypePair(envPutType(outEnv, name, UNKNOWN), UNKNOWN);
      }
      case REGEXP:
        return new EnvTypePair(outEnv, commonTypes.getRegexpType());
      case ARRAYLIT:
        return analyzeArrayLitBwd(expr, outEnv);
      case CAST: {
        EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), outEnv);
        pair.type = symbolTable.getCastType(expr);
        return pair;
      }
      case TEMPLATELIT:
        return analyzeTemplateLitBwd(expr, outEnv);
      case TEMPLATELIT_SUB:
        return analyzeExprBwd(expr.getFirstChild(), outEnv, requiredType);
      case STRING_KEY:
        if (expr.hasChildren()) {
          return analyzeExprBwd(expr.getFirstChild(), outEnv, requiredType);
        } else {
          return analyzeNameBwd(expr, outEnv, requiredType);
        }
      case MEMBER_FUNCTION_DEF:
        return analyzeExprBwd(expr.getFirstChild(), outEnv, requiredType);
      case COMPUTED_PROP:
        TypeEnv env = analyzeExprBwd(expr.getSecondChild(), outEnv).env;
        return analyzeExprBwd(expr.getFirstChild(), env);
      case YIELD: {
        if (expr.hasChildren()) {
          EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), outEnv);
          pair.type = UNKNOWN;
          return pair;
        } else {
          return new EnvTypePair(outEnv, UNKNOWN);
        }
      }
      default:
        throw new RuntimeException(
            "BWD: Unhandled expression type: "
                + expr.getToken()
                + " with parent: "
                + expr.getParent());
    }
  }

  private EnvTypePair analyzeNameBwd(
      Node expr, TypeEnv outEnv, JSType requiredType) {
    String varName = expr.getString();
    if (varName.equals("undefined")) {
      return new EnvTypePair(outEnv, UNDEFINED);
    }
    JSType inferredType = envGetType(outEnv, varName);
    println(varName, "'s inferredType: ", inferredType,
        " requiredType:  ", requiredType);
    if (inferredType == null) {
      return new EnvTypePair(outEnv, UNKNOWN);
    }
    JSType preciseType = inferredType.specialize(requiredType);
    if ((this.currentScope.isUndeclaredFormal(varName)
        || this.currentScope.isUndeclaredOuterVar(varName))
        && preciseType.hasNonScalar()) {
      preciseType = preciseType.withLoose();
    }
    println(varName, "'s preciseType: ", preciseType);
    if (preciseType.isBottom()) {
      // If there is a type mismatch, we can propagate the previously
      // inferred type or the required type.
      // Propagating the already inferred type means that the type of the
      // variable is stable throughout the function body.
      // Propagating the required type means that the type chosen for a
      // formal is the one closest to the function header, which helps
      // generate more intuitive warnings in the fwd direction.
      // But there is a small chance that the different types of the same
      // variable flow to other variables and this can also be a source of
      // unintuitive warnings.
      // It's a trade-off.
      JSType declType = this.currentScope.getDeclaredTypeOf(varName);
      preciseType = firstNonNull(declType, requiredType);
    }
    return EnvTypePair.addBinding(outEnv, varName, preciseType);
  }

  private EnvTypePair analyzeBinaryNumericOpBwd(Node expr, TypeEnv outEnv) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    TypeEnv rhsEnv = analyzeExprBwd(rhs, outEnv, NUMBER).env;
    EnvTypePair pair = analyzeExprBwd(lhs, rhsEnv, NUMBER);
    pair.type = NUMBER;
    return pair;
  }

  private EnvTypePair analyzeAddBwd(Node expr, TypeEnv outEnv, JSType requiredType) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    JSType operandType = requiredType.isNumber() ? NUMBER : UNKNOWN;
    EnvTypePair rhsPair = analyzeExprBwd(rhs, outEnv, operandType);
    EnvTypePair lhsPair = analyzeExprBwd(lhs, rhsPair.env, operandType);
    lhsPair.type = JSType.plus(lhsPair.type, rhsPair.type);
    return lhsPair;
  }

  private EnvTypePair analyzeLogicalOpBwd(Node expr, TypeEnv outEnv) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    EnvTypePair rhsPair = analyzeExprBwd(rhs, outEnv);
    // The types inferred for variables in the rhs are not always
    // true for the rest of the function. Eg, in (!x || x.foo(123))
    // x is non-null only in the rhs, not everywhere.
    // For this reason, we don't reuse rhsPair.env when analyzing the lhs,
    // to avoid incorrect propagation of these types.
    EnvTypePair lhsPair = analyzeExprBwd(lhs, outEnv);
    lhsPair.type = JSType.join(rhsPair.type, lhsPair.type);
    return lhsPair;
  }

  private EnvTypePair analyzeEqNeBwd(Node expr, TypeEnv outEnv) {
    TypeEnv rhsEnv = analyzeExprBwd(expr.getLastChild(), outEnv).env;
    EnvTypePair pair = analyzeExprBwd(expr.getFirstChild(), rhsEnv);
    pair.type = BOOLEAN;
    return pair;
  }

  private EnvTypePair analyzeLtGtBwd(Node expr, TypeEnv outEnv) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    EnvTypePair rhsPair = analyzeExprBwd(rhs, outEnv);
    EnvTypePair lhsPair = analyzeExprBwd(lhs, rhsPair.env);
    JSType meetType = JSType.meet(lhsPair.type, rhsPair.type);
    if (meetType.isBottom()) {
      // Type mismatch, the fwd direction will warn; don't reanalyze
      lhsPair.type = BOOLEAN;
      return lhsPair;
    }
    rhsPair = analyzeExprBwd(rhs, outEnv, meetType);
    lhsPair = analyzeExprBwd(lhs, rhsPair.env, meetType);
    lhsPair.type = BOOLEAN;
    return lhsPair;
  }

  private EnvTypePair analyzeAssignBwd(
      Node expr, TypeEnv outEnv, JSType requiredType) {
    if (expr.getBooleanProp(Node.ANALYZED_DURING_GTI)) {
      return new EnvTypePair(outEnv, requiredType);
    }
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    if (lhs.getBooleanProp(Node.ANALYZED_DURING_GTI)) {
      return analyzeExprBwd(rhs, outEnv,
          markAndGetTypeOfPreanalyzedNode(lhs, outEnv, false));
    }
    // Here we analyze the LHS twice:
    // Once to find out what should be removed for the slicedEnv,
    // and again to take into account the side effects of the LHS itself.
    LValueResultBwd lvalue = analyzeLValueBwd(lhs, outEnv, requiredType, true);
    TypeEnv slicedEnv = lvalue.env;
    JSType rhsReqType = specializeKeep2ndWhenBottom(lvalue.type, requiredType);
    EnvTypePair pair = analyzeExprBwd(rhs, slicedEnv, rhsReqType);
    pair.env = analyzeLValueBwd(lhs, pair.env, requiredType, true).env;
    return pair;
  }

  private EnvTypePair analyzeAssignAddBwd(
      Node expr, TypeEnv outEnv, JSType requiredType) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    JSType lhsReqType = specializeKeep2ndWhenBottom(requiredType, NUMBER_OR_STRING);
    LValueResultBwd lvalue = analyzeLValueBwd(lhs, outEnv, lhsReqType, false);
    // if lhs is a string, rhs can still be a number
    JSType rhsReqType = lvalue.type.isNumber() ? NUMBER : NUMBER_OR_STRING;
    EnvTypePair pair = analyzeExprBwd(rhs, outEnv, rhsReqType);
    pair.env = analyzeLValueBwd(lhs, pair.env, lhsReqType, false).env;
    return pair;
  }

  private EnvTypePair analyzeAssignNumericOpBwd(Node expr, TypeEnv outEnv) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    EnvTypePair pair = analyzeExprBwd(rhs, outEnv, NUMBER);
    LValueResultBwd lvalue =
        analyzeLValueBwd(lhs, pair.env, NUMBER, false);
    return new EnvTypePair(lvalue.env, NUMBER);
  }

  private EnvTypePair analyzeHookBwd(
      Node expr, TypeEnv outEnv, JSType requiredType) {
    Node cond = expr.getFirstChild();
    Node thenBranch = cond.getNext();
    Node elseBranch = thenBranch.getNext();
    EnvTypePair thenPair = analyzeExprBwd(thenBranch, outEnv, requiredType);
    EnvTypePair elsePair = analyzeExprBwd(elseBranch, outEnv, requiredType);
    return analyzeExprBwd(cond, TypeEnv.join(thenPair.env, elsePair.env));
  }

  private EnvTypePair analyzeInvocationBwd(
      Node expr, TypeEnv outEnv, JSType requiredType) {
    checkArgument(expr.isNew() || expr.isCall() || expr.isTaggedTemplateLit());
    Node callee = expr.getFirstChild();
    EnvTypePair pair = analyzeExprBwd(callee, outEnv, commonTypes.topFunction());
    TypeEnv envAfterCallee = pair.env;
    FunctionType funType = pair.type.getFunType();
    if (funType == null) {
      return analyzeInvocationArgumentsBwd(expr, expr.getFirstChild(), envAfterCallee);
    } else if (funType.isLoose()) {
      return analyzeLooseCallNodeBwd(expr, envAfterCallee, requiredType);
    } else if ((expr.isCall() && funType.isSomeConstructorOrInterface())
        || (expr.isNew() && !funType.isSomeConstructorOrInterface())) {
      return analyzeInvocationArgumentsBwd(expr, expr.getFirstChild(), envAfterCallee);
    } else if (funType.isTopFunction()) {
      return analyzeInvocationArgumentsBwd(expr, expr.getFirstChild(), envAfterCallee);
    }
    if (callee.isName() && !funType.isGeneric() && (expr.isCall() || expr.isTaggedTemplateLit())) {
      createDeferredCheckBwd(expr, requiredType);
    }
    int numArgs = NodeUtil.getInvocationArgsCount(expr);
    if (numArgs < funType.getMinArity() || numArgs > funType.getMaxArity()) {
      if (expr.isTaggedTemplateLit()) {
        return analyzeInvocationArgumentsBwd(expr.getLastChild(), null, envAfterCallee);
      } else {
        return analyzeInvocationArgumentsBwd(expr, expr.getFirstChild(), envAfterCallee);
      }
    }
    if (funType.isGeneric()) {
      Map<String, JSType> typeMap = calcTypeInstantiationBwd(expr, funType, envAfterCallee);
      funType = funType.instantiateGenerics(typeMap);
    }
    TypeEnv tmpEnv = envAfterCallee;
    // In bwd direction, analyze arguments in reverse
    Node target = expr.isTaggedTemplateLit() ? null : expr.getFirstChild();
    Node start =
        expr.isTaggedTemplateLit() ? expr.getLastChild().getLastChild() : expr.getLastChild();
    int i = numArgs;
    for (Node arg = start; arg != target; arg = arg.getPrevious()) {
      if (expr.isTaggedTemplateLit() && !arg.isTemplateLitSub()) {
        // To correctly match the non-string parts of the template literal
        // with the formal types of the tag function, i needs to stay unchanged here.
        continue;
      }
      i--;
      JSType formalType = funType.getFormalType(i);
      // The type of a formal can be BOTTOM as the result of a join.
      // Don't use this as a requiredType.
      formalType = firstNonBottom(formalType, UNKNOWN);
      tmpEnv = analyzeExprBwd(arg, tmpEnv, formalType).env;
      // We don't need deferred checks for args in BWD
    }
    JSType retType =
        expr.isNew() ? funType.getThisType() : funType.getReturnType();
    return new EnvTypePair(tmpEnv, retType);
  }

  private EnvTypePair analyzeGetElemBwd(
      Node expr, TypeEnv outEnv, JSType requiredType) {
    Node receiver = expr.getFirstChild();
    Node index = expr.getLastChild();
    JSType reqObjType = pickReqObjType(expr);
    EnvTypePair pair = analyzeExprBwd(receiver, outEnv, reqObjType);
    JSType recvType = pair.type;
    JSType indexType = recvType.getIndexType();
    if (indexType != null) {
      indexType = firstNonBottom(indexType, UNKNOWN);
      pair = analyzeExprBwd(index, pair.env, indexType);
      pair.type = getIndexedTypeOrUnknown(recvType);
      return pair;
    }
    if (index.isString()) {
      return analyzePropAccessBwd(receiver, index.getString(), outEnv, requiredType);
    }
    pair = analyzeExprBwd(index, outEnv);
    pair = analyzeExprBwd(receiver, pair.env, reqObjType);
    pair.type = requiredType;
    return pair;
  }

  private EnvTypePair analyzeInBwd(Node expr, TypeEnv outEnv) {
    Node lhs = expr.getFirstChild();
    Node rhs = expr.getLastChild();
    EnvTypePair pair = analyzeExprBwd(rhs, outEnv, pickReqObjType(expr));
    pair = analyzeExprBwd(lhs, pair.env, NUMBER_OR_STRING);
    pair.type = BOOLEAN;
    return pair;
  }

  private EnvTypePair analyzeArrayLitBwd(Node expr, TypeEnv outEnv) {
    TypeEnv env = outEnv;
    JSType elementType = BOTTOM;
    for (Node elm = expr.getLastChild(); elm != null; elm = elm.getPrevious()) {
      EnvTypePair pair = analyzeExprBwd(elm, env);
      env = pair.env;
      elementType = JSType.join(elementType, pair.type);
    }
    elementType = firstNonBottom(elementType, UNKNOWN);
    return new EnvTypePair(env, commonTypes.getArrayInstance(elementType));
  }

  private EnvTypePair analyzeInvocationArgumentsBwd(
      Node callNode, Node target, TypeEnv outEnv) {
    TypeEnv env = outEnv;
    for (Node arg = callNode.getLastChild(); arg != target; arg = arg.getPrevious()) {
      env = analyzeExprBwd(arg, env).env;
    }
    return new EnvTypePair(env, UNKNOWN);
  }

  private EnvTypePair analyzeTemplateLitBwd(Node expr, TypeEnv outEnv) {
    TypeEnv env = outEnv;
    for (Node elm = expr.getLastChild(); elm != null; elm = elm.getPrevious()) {
      env = analyzeExprBwd(elm, env).env;
    }
    return new EnvTypePair(env, STRING);
  }

  private void createDeferredCheckBwd(Node expr, JSType requiredType) {
    checkArgument(expr.isCall() || expr.isTaggedTemplateLit());
    checkArgument(expr.getFirstChild().isName());
    String calleeName = expr.getFirstChild().getString();
    // Local function definitions will be type-checked more
    // exactly using their summaries, and don't need deferred checks
    if (this.currentScope.isKnownFunction(calleeName)
        && !this.currentScope.isLocalFunDef(calleeName)
        && !this.currentScope.isExternalFunction(calleeName)) {
      NTIScope s = this.currentScope.getScope(calleeName);
      JSType expectedRetType;
      if (s.getDeclaredFunctionType().getReturnType() == null) {
        expectedRetType = requiredType;
      } else {
        // No deferred check if the return type is declared
        expectedRetType = null;
      }
      println("Putting deferred check of function: ", calleeName, " with ret: ", expectedRetType);
      DeferredCheck dc = new DeferredCheck(expr, expectedRetType, currentScope, s);
      deferredChecks.put(expr, dc);
    }
  }

  private EnvTypePair analyzePropAccessBwd(
      Node receiver, String pname, TypeEnv outEnv, JSType requiredType) {
    Node propAccessNode = receiver.getParent();
    QualifiedName qname = new QualifiedName(pname);
    JSType reqObjType = pickReqObjType(propAccessNode);
    if (!NodeUtil.isPropertyTest(compiler, propAccessNode)) {
      reqObjType = reqObjType.withProperty(qname, requiredType);
    }
    EnvTypePair pair = analyzeExprBwd(receiver, outEnv, reqObjType);
    JSType receiverType = pair.type;
    JSType propAccessType = receiverType.mayHaveProp(qname) ?
        receiverType.getProp(qname) : requiredType;
    pair.type = propAccessType;
    return pair;
  }

  private EnvTypePair analyzeObjLitBwd(
      Node objLit, TypeEnv outEnv, JSType requiredType) {
    if (NodeUtil.isEnumDecl(objLit.getParent())) {
      return analyzeEnumObjLitBwd(objLit, outEnv, requiredType);
    }
    TypeEnv env = outEnv;
    JSType result = pickReqObjType(objLit);
    for (Node prop = objLit.getLastChild();
         prop != null;
         prop = prop.getPrevious()) {
      if (prop.isGetterDef() || prop.isSetterDef()) {
        env = analyzeExprBwd(prop.getFirstChild(), env).env;
      } else if (prop.isComputedProp() && !prop.getFirstChild().isString()){
        env = analyzeExprBwd(prop, env).env;
      } else {
        QualifiedName pname = new QualifiedName(NodeUtil.getObjectLitKeyName(prop));
        JSType jsdocType = symbolTable.getPropDeclaredType(prop);
        JSType reqPtype;
        if (jsdocType != null) {
          reqPtype = jsdocType;
        } else if (requiredType.mayHaveProp(pname)) {
          reqPtype = requiredType.getProp(pname);
        } else {
          reqPtype = UNKNOWN;
        }
        EnvTypePair pair = analyzeExprBwd(prop, env, reqPtype);
        result = result.withProperty(pname, pair.type);
        env = pair.env;
      }
    }
    return new EnvTypePair(env, result);
  }

  private EnvTypePair analyzeEnumObjLitBwd(
      Node objLit, TypeEnv outEnv, JSType requiredType) {
    if (objLit.getFirstChild() == null) {
      return new EnvTypePair(outEnv, requiredType);
    }
    String pname = NodeUtil.getObjectLitKeyName(objLit.getFirstChild());
    JSType enumeratedType =
        requiredType.getProp(new QualifiedName(pname)).getEnumeratedTypeOfEnumElement();
    if (enumeratedType == null) {
      return new EnvTypePair(outEnv, requiredType);
    }
    TypeEnv env = outEnv;
    for (Node prop = objLit.getLastChild();
         prop != null;
         prop = prop.getPrevious()) {
      env = analyzeExprBwd(prop, env, enumeratedType).env;
    }
    return new EnvTypePair(env, requiredType);
  }

  private boolean isPropertyTestCall(Node expr) {
    if (!expr.isCall()) {
      return false;
    }
    return expr.getFirstChild().isQualifiedName()
        && this.convention.isPropertyTestFunction(expr);
  }

  private boolean isFunctionBind(Node callee, TypeEnv env, boolean isFwd) {
    if (NodeUtil.isFunctionBind(callee)) {
      if (isFwd) {
        analyzeExprFwdIgnoreResult(callee, env);
      }
      return true;
    }
    if (!callee.isGetProp() || !callee.isQualifiedName()
        || !callee.getLastChild().getString().equals("bind")) {
      return false;
    }
    Node recv = callee.getFirstChild();
    JSType recvType;
    if (isFwd) {
      recvType = analyzeExprFwd(recv, env).type;
      maybeSetTypeI(callee, recvType.getProp(new QualifiedName("bind")));
    } else {
      recvType = analyzeExprBwd(recv, env).type;
    }
    return !recvType.isUnknown() && recvType.isSubtypeOf(commonTypes.topFunction());
  }

  private boolean isGoogTypeof(Node expr) {
    if (!expr.isCall()) {
      return false;
    }
    expr = expr.getFirstChild();
    return expr.isGetProp() && expr.getFirstChild().isName() &&
        expr.getFirstChild().getString().equals("goog") &&
        expr.getLastChild().getString().equals("typeOf");
  }

  private JSType scalarValueToType(Token token) {
    switch (token) {
      case NUMBER:
        return NUMBER;
      case STRING:
        return STRING;
      case TRUE:
        return TRUE_TYPE;
      case FALSE:
        return FALSE_TYPE;
      case NULL:
        return NULL;
      default:
        throw new RuntimeException("The token isn't a scalar value " + token);
    }
  }

  private void warnInvalidOperand(
      Node expr, Token operatorType, Object expected, Object actual) {
    checkArgument((expected instanceof String) || (expected instanceof JSType));
    checkArgument((actual instanceof String) || (actual instanceof JSType));
    if (expected instanceof JSType && actual instanceof JSType) {
      warnings.add(
          JSError.make(
              expr,
              INVALID_OPERAND_TYPE,
              operatorType.toString(),
              errorMsgWithTypeDiff((JSType) expected, (JSType) actual)));
    } else {
      warnings.add(
          JSError.make(
              expr,
              INVALID_OPERAND_TYPE,
              operatorType.toString(),
              "Expected : " + expected + "\n" + "Found    : " + actual + "\n"));
    }
  }

  private static class EnvTypePair {
    TypeEnv env;
    JSType type;

    EnvTypePair(TypeEnv env, JSType type) {
      this.env = env;
      this.type = type;
    }

    static EnvTypePair addBinding(TypeEnv env, String varName, JSType type) {
      return new EnvTypePair(envPutType(env, varName, type), type);
    }

    static EnvTypePair join(EnvTypePair p1, EnvTypePair p2) {
      return new EnvTypePair(TypeEnv.join(p1.env, p2.env),
          JSType.join(p1.type, p2.type));
    }
  }

  private static JSType envGetType(TypeEnv env, String pname) {
    checkArgument(!pname.contains("."), pname);
    return env.getType(pname);
  }

  private static JSType envGetTypeOfQname(TypeEnv env, QualifiedName qname) {
    JSType leftmostType = envGetType(env, qname.getLeftmostName());
    if (qname.isIdentifier()) {
      return leftmostType;
    }
    return leftmostType == null ? null : leftmostType.getProp(qname.getAllButLeftmost());
  }

  private static TypeEnv envPutType(TypeEnv env, String varName, JSType type) {
    checkArgument(!varName.contains("."));
    return env.putType(varName, type);
  }

  private static class LValueResultFwd {
    TypeEnv env;
    JSType type;
    JSType declType;
    QualifiedName ptr;

    LValueResultFwd(
        TypeEnv env, JSType type, JSType declType, QualifiedName ptr) {
      checkNotNull(type);
      this.env = env;
      this.type = type;
      this.declType = declType;
      this.ptr = ptr;
    }
  }

  // Some expressions are analyzed during GTI, so they're skipped here.
  // But we must annotate them with a type anyway.
  private JSType markAndGetTypeOfPreanalyzedNode(Node n, TypeEnv env, boolean isFwd) {
    switch (n.getToken()) {
      case NAME:
      case THIS: {
        JSType result = envGetType(env, n.isThis() ? THIS_ID : n.getString());
        Preconditions.checkNotNull(result, "Null declared type at node: %s", n);
        if (isFwd) {
          maybeSetTypeI(n, result);
        }
        return result;
      }
      case GETPROP: {
        JSType recvType = markAndGetTypeOfPreanalyzedNode(n.getFirstChild(), env, isFwd);
        String pname = n.getLastChild().getString();
        JSType result = null;
        if (recvType.isSubtypeOf(TOP_OBJECT)) {
          result = recvType.getProp(new QualifiedName(pname));
        }

        if (result == null) {
          warnings.add(JSError.make(n, UNKNOWN_NAMESPACE_PROPERTY, n.getQualifiedName()));
          return UNKNOWN;
        }

        Preconditions.checkNotNull(result, "Null declared type@%s", n);
        if (isFwd) {
          maybeSetTypeI(n, result);
        }
        return result;
      }
      default: {
        // For the rhs of an assignment, just mark it with the type of the lhs.
        Node assign = n.getParent();
        checkState(assign.isAssign() && assign.getLastChild() == n,
            "Expected assign but found %s", assign);
        JSType lhsType = checkNotNull((JSType) assign.getFirstChild().getTypeI());
        maybeSetTypeI(n, lhsType);
        return lhsType;
      }
    }
  }

  private void maybeSetTypeI(Node n, JSType t) {
    TypeI oldType = n.getTypeI();
    checkState(oldType == null || oldType instanceof JSType);
    // When creating a function summary, we set a precise type on the function's
    // name node. Since we're visiting inner scopes first, the name node is
    // revisited after the function's scope is analyzed, and its type then can
    // be less precise. So, we keep the summary type.
    // TODO(dimvar): Look into why the name node has a less precise type in the
    // outer scope; we've already computed a good type for it, don't lose it.
    if (oldType == null) {
      n.setTypeI(t);
    }
  }

  private LValueResultFwd analyzeLValueFwd(
      Node expr, TypeEnv inEnv, JSType type) {
    return analyzeLValueFwd(expr, inEnv, type, false);
  }

  private LValueResultFwd analyzeLValueFwd(
      Node expr, TypeEnv inEnv, JSType requiredType, boolean insideQualifiedName) {
    LValueResultFwd lvalResult = null;
    switch (expr.getToken()) {
      case THIS: {
        mayWarnAboutGlobalThis(expr);
        if (this.currentScope.hasThis()) {
          lvalResult = new LValueResultFwd(inEnv, envGetType(inEnv, THIS_ID),
              this.currentScope.getDeclaredTypeOf(THIS_ID),
              new QualifiedName(THIS_ID));
        } else {
          lvalResult = new LValueResultFwd(inEnv, UNKNOWN, null, null);
        }
        break;
      }
      case NAME: {
        String varName = expr.getString();
        JSType varType = analyzeExprFwd(expr, inEnv).type;
        lvalResult = new LValueResultFwd(inEnv, varType,
            this.currentScope.getDeclaredTypeOf(varName),
            varType.hasNonScalar() ? new QualifiedName(varName) : null);
        break;
      }
      case GETPROP:
      case GETELEM: {
        Node obj = expr.getFirstChild();
        Node prop = expr.getLastChild();
        QualifiedName pname = expr.isGetProp() || prop.isString()
            ? new QualifiedName(prop.getString()) : null;
        LValueResultFwd recvLvalue = analyzeReceiverLvalFwd(obj, pname, inEnv, requiredType);
        if (!recvLvalue.type.isSubtypeOf(TOP_OBJECT)) {
          EnvTypePair pair = analyzeExprFwd(prop, recvLvalue.env, requiredType);
          lvalResult = new LValueResultFwd(pair.env, requiredType, null, null);
          break;
        }
        JSType indexType = recvLvalue.type.getIndexType();
        // (1) A getelem where the receiver is an IObject
        if (expr.isGetElem() && indexType != null) {
          lvalResult = analyzeIObjectElmLvalFwd(prop, recvLvalue, indexType);
          break;
        }
        // (2) A getelem where the prop is a string literal is like a getprop
        if (expr.isGetProp() || prop.isString()) {
          lvalResult = analyzePropLValFwd(
              obj, pname, recvLvalue, requiredType, insideQualifiedName);
          break;
        }
        // (3) All other getelems
        // TODO(dimvar): there is some recomputation here; the receiver will be
        // analyzed again. Some more refactoring can fix this.
        EnvTypePair pair = analyzeExprFwd(expr, recvLvalue.env, requiredType);
        lvalResult = new LValueResultFwd(pair.env, pair.type, null, null);
        break;
      }
      case VAR: { // Can happen iff its parent is a for/in or for/of.
          checkState(expr.getParent().isForIn() || expr.getParent().isForOf());
          Node nameNode = expr.getFirstChild();
          String name = nameNode.getString();
          // For/in and for/of can never have rhs of its VAR
          checkState(!nameNode.hasChildren());
          maybeSetTypeI(nameNode, requiredType);
          if (expr.getParent().isForIn()) {
            return new LValueResultFwd(inEnv, STRING, null, new QualifiedName(name));
          } else {
            JSType declType = this.currentScope.getDeclaredTypeOf(name);
            return new LValueResultFwd(inEnv, requiredType, declType, new QualifiedName(name));
          }
        }
      default: {
        // Expressions that aren't lvalues should be handled because they may
        // be, e.g., the left child of a getprop.
        // We must check that they are not the direct lvalues.
        checkState(insideQualifiedName);
        EnvTypePair pair = analyzeExprFwd(expr, inEnv, requiredType);
        return new LValueResultFwd(pair.env, pair.type, null, null);
      }
    }
    maybeSetTypeI(expr, lvalResult.type);
    mayWarnAboutUnknownType(expr, lvalResult.type);
    return lvalResult;
  }

  private LValueResultFwd analyzeIObjectElmLvalFwd(
      Node prop, LValueResultFwd recvLvalue, JSType indexType) {
    EnvTypePair pair = analyzeExprFwd(
        prop, recvLvalue.env, firstNonBottom(indexType, UNKNOWN));
    if (mayWarnAboutBadIObjectIndex(prop, recvLvalue.type, pair.type, indexType)) {
      return new LValueResultFwd(pair.env, UNKNOWN, null, null);
    }
    JSType inferred = getIndexedTypeOrUnknown(recvLvalue.type);
    JSType declared = null;
    if (recvLvalue.declType != null) {
      JSType receiverAdjustedDeclType =
          recvLvalue.declType.removeType(NULL_OR_UNDEFINED);
      declared = receiverAdjustedDeclType.getIndexedType();
    }
    return new LValueResultFwd(pair.env, inferred, declared, null);
  }

  private EnvTypePair mayWarnAboutNullableReferenceAndTighten(
      Node obj, JSType recvType, JSType maybeSpecType, TypeEnv inEnv) {
    if (!recvType.isUnknown()
        && !recvType.isTop()
        && (NULL.isSubtypeOf(recvType)
            || UNDEFINED.isSubtypeOf(recvType))) {
      JSType minusNull = recvType.removeType(NULL_OR_UNDEFINED);
      if (!minusNull.isBottom()) {
        if (this.reportNullDeref) {
          warnings.add(JSError.make(obj, NULLABLE_DEREFERENCE, recvType.toString()));
        }
        TypeEnv outEnv = inEnv;
        if (obj.isQualifiedName()) {
          QualifiedName qname = QualifiedName.fromNode(obj);
          if (maybeSpecType != null && maybeSpecType.isSubtypeOf(minusNull)) {
            minusNull = maybeSpecType;
          }
          outEnv = updateLvalueTypeInEnv(inEnv, obj, qname, minusNull);
        }
        return new EnvTypePair(outEnv, minusNull);
      }
    }
    return new EnvTypePair(inEnv, recvType);
  }

  private LValueResultFwd analyzePropLValFwd(Node obj, QualifiedName pname,
      LValueResultFwd recvLvalue, JSType requiredType, boolean insideQualifiedName) {
    checkArgument(pname.isIdentifier());
    TypeEnv inEnv = recvLvalue.env;
    JSType recvType = recvLvalue.type;
    if (!recvType.isUnion() && !recvType.isSingletonObj()) {
      // The lvalue is a subtype of TOP_OBJECT, but does not contain an object
      // yet, eg, it is ?, truthy, or bottom.
      recvType = TOP_OBJECT.withLoose();
    }
    Node propAccessNode = obj.getParent();
    if (propAccessNode.isGetProp() &&
        propAccessNode.getParent().isAssign() &&
        mayWarnAboutPropCreation(pname, propAccessNode, recvType)) {
      return new LValueResultFwd(inEnv, requiredType, null, null);
    }
    if (!insideQualifiedName
        && mayWarnAboutConstProp(propAccessNode, recvType, pname)) {
      return new LValueResultFwd(inEnv, requiredType, null, null);
    }
    if (!recvType.hasProp(pname)) {
      // Warn for inexistent prop either on the non-top-level of a qualified
      // name, or for assignment ops that won't create a new property.
      if (insideQualifiedName || !propAccessNode.getParent().isAssign()) {
        mayWarnAboutInexistentProp(propAccessNode, recvType, pname);
        if (!recvType.isLoose()) {
          return new LValueResultFwd(inEnv, requiredType, null, null);
        }
      }
      if (recvType.isLoose()) {
        // For loose objects, create the inner property if it doesn't exist.
        recvType = recvType.withProperty(pname, UNKNOWN);
        inEnv = updateLvalueTypeInEnv(inEnv, obj, recvLvalue.ptr, recvType);
      }
    }
    if (propAccessNode.isGetElem()) {
      mayWarnAboutStructPropAccess(obj, recvType);
    } else if (propAccessNode.isGetProp()) {
      mayWarnAboutDictPropAccess(obj, recvType);
    }
    QualifiedName setterPname =
        new QualifiedName(commonTypes.createSetterPropName(pname.getLeftmostName()));
    if (recvType.hasProp(setterPname)) {
      FunctionType funType = recvType.getProp(setterPname).getFunType();
      checkNotNull(funType);
      JSType formalType = funType.getFormalType(0);
      checkState(!formalType.isBottom());
      return new LValueResultFwd(inEnv, formalType, formalType, null);
    }
    QualifiedName ptr = recvLvalue.ptr == null
        ? null : QualifiedName.join(recvLvalue.ptr, pname);
    return recvType.mayHaveProp(pname)
        ? new LValueResultFwd(
            inEnv, recvType.getProp(pname), recvType.getDeclaredProp(pname), ptr)
        : new LValueResultFwd(inEnv, UNKNOWN, null, ptr);
  }

  private LValueResultFwd analyzeReceiverLvalFwd(
      Node obj, QualifiedName pname, TypeEnv inEnv, JSType propType) {
    // pname is null when the property name is not known.
    checkArgument(pname == null || pname.isIdentifier());
    JSType reqObjType = pickReqObjType(obj.getParent());
    if (pname != null) {
      reqObjType = reqObjType.withProperty(pname, propType);
    }
    LValueResultFwd lvalue = analyzeLValueFwd(obj, inEnv, reqObjType, true);
    EnvTypePair pair = mayWarnAboutNullableReferenceAndTighten(
        obj, lvalue.type, null, lvalue.env);
    JSType lvalueType = pair.type;
    if (lvalueType.isEnumElement()) {
      lvalueType = lvalueType.getEnumeratedTypeOfEnumElement();
    }
    if (!lvalueType.isSubtypeOf(TOP_OBJECT)) {
      warnings.add(JSError.make(obj, ADDING_PROPERTY_TO_NON_OBJECT,
              getPropNameForErrorMsg(obj.getParent()), lvalueType.toString()));
    }
    lvalue.type = lvalueType;
    lvalue.env = pair.env;
    return lvalue;
  }

  private static class LValueResultBwd {
    TypeEnv env;
    JSType type;
    QualifiedName ptr;

    LValueResultBwd(TypeEnv env, JSType type, QualifiedName ptr) {
      checkNotNull(type);
      this.env = env;
      this.type = type;
      this.ptr = ptr;
    }
  }

  private LValueResultBwd analyzeLValueBwd(
      Node expr, TypeEnv outEnv, JSType type, boolean doSlicing) {
    return analyzeLValueBwd(expr, outEnv, type, doSlicing, false);
  }

  /**
   * We use this to avoid putting global variables in type environments, because that
   * can cause crashes in TypeEnv#join.
   */
  private boolean isGlobalVariable(Node maybeName, TypeEnv env) {
    return maybeName.isName() && envGetType(env, maybeName.getString()) == null;
  }

  /** When {@code doSlicing} is set, remove the lvalue from the returned env */
  private LValueResultBwd analyzeLValueBwd(Node expr, TypeEnv outEnv,
      JSType type, boolean doSlicing, boolean insideQualifiedName) {
    switch (expr.getToken()) {
      case THIS:
      case NAME: {
        EnvTypePair pair = analyzeExprBwd(expr, outEnv, type);
        String name = expr.getQualifiedName();
        JSType declType = this.currentScope.getDeclaredTypeOf(name);
        if (doSlicing && !isGlobalVariable(expr, outEnv)) {
          pair.env = envPutType(pair.env, name, firstNonNull(declType, UNKNOWN));
        }
        return new LValueResultBwd(pair.env, pair.type,
            pair.type.hasNonScalar() ? new QualifiedName(name) : null);
      }
      case GETPROP: {
        Node obj = expr.getFirstChild();
        QualifiedName pname =
            new QualifiedName(expr.getLastChild().getString());
        return analyzePropLValBwd(obj, pname, outEnv, type, doSlicing);
      }
      case GETELEM: {
        if (expr.getLastChild().isString()) {
          Node obj = expr.getFirstChild();
          QualifiedName pname =
              new QualifiedName(expr.getLastChild().getString());
          return analyzePropLValBwd(obj, pname, outEnv, type, doSlicing);
        }
        EnvTypePair pair = analyzeExprBwd(expr, outEnv, type);
        return new LValueResultBwd(pair.env, pair.type, null);
      }
      default:
        {
          // Expressions that aren't lvalues should be handled because they may
          // be, e.g., the left child of a getprop.
          // We must check that they are not the direct lvalues.
          checkState(insideQualifiedName);
          EnvTypePair pair = analyzeExprBwd(expr, outEnv, type);
          return new LValueResultBwd(pair.env, pair.type, null);
        }
    }
  }

  private LValueResultBwd analyzePropLValBwd(Node obj, QualifiedName pname,
      TypeEnv outEnv, JSType type, boolean doSlicing) {
    checkArgument(pname.isIdentifier());
    JSType reqObjType =
        pickReqObjType(obj.getParent()).withProperty(pname, type);
    LValueResultBwd lvalue =
        analyzeLValueBwd(obj, outEnv, reqObjType, false, true);
    if (lvalue.ptr != null) {
      lvalue.ptr = QualifiedName.join(lvalue.ptr, pname);
      if (doSlicing) {
        String objName = lvalue.ptr.getLeftmostName();
        QualifiedName props = lvalue.ptr.getAllButLeftmost();
        JSType objType = envGetType(lvalue.env, objName);
        // withoutProperty only removes inferred properties
        JSType slicedObjType = objType.withoutProperty(props);
        lvalue.env = envPutType(lvalue.env, objName, slicedObjType);
      }
    }
    lvalue.type = lvalue.type.mayHaveProp(pname) ?
        lvalue.type.getProp(pname) : UNKNOWN;
    return lvalue;
  }

  private JSType pickReqObjType(Node expr) {
    Token exprKind = expr.getToken();
    switch (exprKind) {
      case OBJECTLIT: {
        JSDocInfo jsdoc = expr.getJSDocInfo();
        if (jsdoc != null && jsdoc.makesStructs()) {
          return this.commonTypes.getTopStruct();
        }
        if (jsdoc != null && jsdoc.makesDicts()) {
          return this.commonTypes.getTopDict();
        }
        return this.commonTypes.getEmptyObjectLiteral();
      }
      case FOR:
      case FOR_IN:
        Preconditions.checkState(expr.isForIn());
        return TOP_OBJECT;
      case FOR_OF:
        Preconditions.checkState(expr.isForOf());
        return this.commonTypes.getIterableInstance(UNKNOWN);
      case GETPROP:
      case GETELEM:
      case IN:
        return TOP_OBJECT;
      default:
        throw new RuntimeException("Unhandled node for pickReqObjType: " + exprKind);
    }
  }

  private static String getReadableCalleeName(Node expr) {
    return expr.isQualifiedName() ? expr.getQualifiedName() : "";
  }

  private static JSType specializeKeep2ndWhenBottom(
      JSType toBeSpecialized, JSType fallback) {
    JSType specializedType = toBeSpecialized.specialize(fallback);
    return specializedType.isBottom() ? fallback : specializedType;
  }

  private TypeEnv getEntryTypeEnv() {
    return getOutEnv(this.cfg.getEntry());
  }

  private TypeEnv getExitTypeEnv() {
    for (int i = 0; i < exitEnvs.size(); i++) {
      TypeEnv env = exitEnvs.get(i);
      exitEnvs.set(i, envPutType(env, RETVAL_ID, BOTTOM));
    }
    if (!this.cfg.getImplicitReturn().getInEdges().isEmpty()) {
      exitEnvs.add(getInEnv(this.cfg.getImplicitReturn()));
    }
    checkState(!exitEnvs.isEmpty(),
        "There must be at least one exit env, either from a normal function exit or a throw.");
    return TypeEnv.join(exitEnvs);
  }

  private static JSType firstNonBottom(JSType t1, JSType t2) {
    if (t1.isBottom()) {
      Preconditions.checkArgument(!t2.isBottom());
      return t2;
    }
    return t1;
  }

  private class DeferredCheck {
    final Node callSite;
    final NTIScope callerScope;
    final NTIScope calleeScope;
    // Null types means that they were declared (and should have been checked during inference)
    JSType expectedRetType;
    List<JSType> argTypes;

    DeferredCheck(
        Node callSite,
        JSType expectedRetType,
        NTIScope callerScope,
        NTIScope calleeScope) {
      this.callSite = callSite;
      this.expectedRetType = expectedRetType;
      this.callerScope = callerScope;
      this.calleeScope = calleeScope;
    }

    void updateReturn(JSType expectedRetType) {
      if (this.expectedRetType != null) {
        this.expectedRetType = JSType.meet(this.expectedRetType, expectedRetType);
      }
    }

    void updateArgTypes(List<JSType> argTypes) {
      this.argTypes = argTypes;
    }

    private void runCheck(Map<NTIScope, JSType> summaries, WarningReporter warnings) {
      FunctionType fnSummary = summaries.get(this.calleeScope).getFunType();
      println(
          "Running deferred check of function: ", calleeScope.getReadableName(),
          " with FunctionSummary of: ", fnSummary, " and callsite ret: ",
          expectedRetType, " args: ", argTypes);
      if (this.expectedRetType != null &&
          !fnSummary.getReturnType().isSubtypeOf(this.expectedRetType)) {
        warnings.add(JSError.make(
            this.callSite, INVALID_INFERRED_RETURN_TYPE,
            errorMsgWithTypeDiff(
                this.expectedRetType, fnSummary.getReturnType())));
      }
      int i = 0;
      Iterable<Node> args = NodeUtil.getInvocationArgsAsIterable(callSite);
      // this.argTypes can be null if in the fwd direction the analysis of the
      // call returned prematurely, e.g., because of a WRONG_ARGUMENT_COUNT.
      if (this.argTypes == null) {
        return;
      }
      int offset = this.callSite.isTaggedTemplateLit() ? 1 : 0;
      for (Node argNode : args) {
        JSType argType = this.argTypes.get(i);
        JSType formalType = fnSummary.getFormalType(i + offset);
        if (argNode.isName() && callerScope.isKnownFunction(argNode.getString())) {
          argType = summaries.get(callerScope.getScope(argNode.getString()));
        }
        if (argType != null) {
          if (argType.isSubtypeOf(formalType)) {
            registerImplicitUses(argNode, argType, formalType);
          } else {
            JSError error = JSError.make(argNode, INVALID_ARGUMENT_TYPE,
                Integer.toString(i + offset + 1), calleeScope.getReadableName(),
                errorMsgWithTypeDiff(formalType, argType));
            registerMismatchAndWarn(error, argType, formalType);
          }
        }
        i++;
      }
    }

    @Override
    public boolean equals(Object o) {
      checkArgument(o instanceof DeferredCheck);
      DeferredCheck dc2 = (DeferredCheck) o;
      return callSite == dc2.callSite &&
          callerScope == dc2.callerScope &&
          calleeScope == dc2.calleeScope &&
          Objects.equals(expectedRetType, dc2.expectedRetType) &&
          Objects.equals(argTypes, dc2.argTypes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          callSite, callerScope, calleeScope, expectedRetType, argTypes);
    }
  }
}

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

import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.javascript.rhino.FunctionTypeI;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.ObjectTypeI;
import com.google.javascript.rhino.TypeI;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Signals that the first type and the second type have been
 * used interchangeably.
 *
 * Type-based optimizations should take this into account
 * so that they don't wreck code with type warnings.
 */
class TypeMismatch implements Serializable {
  final TypeI typeA;
  final TypeI typeB;
  final Supplier<JSError> error;

  /**
   * It's the responsibility of the class that creates the
   * {@code TypeMismatch} to ensure that {@code a} and {@code b} are
   * non-matching types.
   */
  TypeMismatch(TypeI a, TypeI b, Supplier<JSError> error) {
    this.typeA = a;
    this.typeB = b;
    this.error = error;
  }

  static void registerIfMismatch(
      List<TypeMismatch> mismatches, List<TypeMismatch> implicitInterfaceUses,
      TypeI found, TypeI required, JSError error) {
    if (found != null && required != null && !found.isSubtypeWithoutStructuralTyping(required)) {
      registerMismatch(mismatches, implicitInterfaceUses, found, required, error);
    }
  }

  static void registerMismatch(
      List<TypeMismatch> mismatches, List<TypeMismatch> implicitInterfaceUses,
      TypeI found, TypeI required, JSError error) {
    // Don't register a mismatch for differences in null or undefined or if the
    // code didn't downcast.
    found = removeNullUndefinedAndTemplates(found);
    required = removeNullUndefinedAndTemplates(required);
    if (found.isSubtypeOf(required) || required.isSubtypeOf(found)) {
      boolean strictMismatch =
          !found.isSubtypeWithoutStructuralTyping(required)
          && !required.isSubtypeWithoutStructuralTyping(found);
      if (strictMismatch) {
        implicitInterfaceUses.add(new TypeMismatch(found, required, Suppliers.ofInstance(error)));
      }
      return;
    }

    mismatches.add(new TypeMismatch(found, required, Suppliers.ofInstance(error)));

    if (found.isFunctionType() && required.isFunctionType()) {
      FunctionTypeI fnTypeA = found.toMaybeFunctionType();
      FunctionTypeI fnTypeB = required.toMaybeFunctionType();
      Iterator<TypeI> paramItA = fnTypeA.getParameterTypes().iterator();
      Iterator<TypeI> paramItB = fnTypeB.getParameterTypes().iterator();
      while (paramItA.hasNext() && paramItB.hasNext()) {
        TypeMismatch.registerIfMismatch(
            mismatches, implicitInterfaceUses, paramItA.next(), paramItB.next(), error);
      }
      TypeMismatch.registerIfMismatch(
          mismatches, implicitInterfaceUses,
          fnTypeA.getReturnType(), fnTypeB.getReturnType(), error);
    }
  }

  static void recordImplicitUseOfNativeObject(
      List<TypeMismatch> mismatches, Node node, TypeI sourceType, TypeI targetType) {
    sourceType = sourceType.restrictByNotNullOrUndefined();
    targetType = targetType.restrictByNotNullOrUndefined();
    if (sourceType.isInstanceofObject()
        && !targetType.isInstanceofObject() && !targetType.isUnknownType()) {
      // We don't report a type error, but we still need to construct a JSError,
      // for people who enable the invalidation diagnostics in DisambiguateProperties.
      LazyError err =
          LazyError.of(
              "Implicit use of Object type: %s as type: %s", node, sourceType, targetType);
      mismatches.add(new TypeMismatch(sourceType, targetType, err));
    }
  }

  static void recordImplicitInterfaceUses(
      List<TypeMismatch> implicitInterfaceUses, Node node, TypeI sourceType, TypeI targetType) {
    sourceType = removeNullUndefinedAndTemplates(sourceType);
    targetType = removeNullUndefinedAndTemplates(targetType);
    if (targetType.isUnknownType()) {
      return;
    }
    boolean strictMismatch =
        !sourceType.isSubtypeWithoutStructuralTyping(targetType)
        && !targetType.isSubtypeWithoutStructuralTyping(sourceType);
    boolean mismatch = !sourceType.isSubtypeOf(targetType) && !targetType.isSubtypeOf(sourceType);
    if (strictMismatch || mismatch) {
      // We don't report a type error, but we still need to construct a JSError,
      // for people who enable the invalidation diagnostics in DisambiguateProperties.
      LazyError err = LazyError.of("Implicit use of type %s as %s", node, sourceType, targetType);
      implicitInterfaceUses.add(new TypeMismatch(sourceType, targetType, err));
    }
  }

  private static TypeI removeNullUndefinedAndTemplates(TypeI t) {
    TypeI result = t.restrictByNotNullOrUndefined();
    ObjectTypeI obj = result.toMaybeObjectType();
    if (obj != null && obj.isGenericObjectType()) {
      return obj.instantiateGenericsWithUnknown();
    }
    return result;
  }

  @Override public boolean equals(Object object) {
    if (object instanceof TypeMismatch) {
      TypeMismatch that = (TypeMismatch) object;
      return (that.typeA.equals(this.typeA) && that.typeB.equals(this.typeB))
          || (that.typeB.equals(this.typeA) && that.typeA.equals(this.typeB));
    }
    return false;
  }

  @Override public int hashCode() {
    return Objects.hash(typeA, typeB);
  }

  @Override public String toString() {
    return "(" + typeA + ", " + typeB + ")";
  }

  @AutoValue
  abstract static class LazyError implements Supplier<JSError>, Serializable {
    abstract String message();
    abstract Node node();
    abstract TypeI sourceType();
    abstract TypeI targetType();

    private static LazyError of(String message, Node node, TypeI sourceType, TypeI targetType) {
      return new AutoValue_TypeMismatch_LazyError(message, node, sourceType, targetType);
    }

    @Override
    public JSError get() {
      // NOTE: GWT does not support String.format, so we work around it with a quick hack.
      List<String> parts = Splitter.on("%s").splitToList(message());
      checkState(parts.size() == 3);
      return JSError.make(
          node(),
          TypeValidator.TYPE_MISMATCH_WARNING,
          parts.get(0) + sourceType() + parts.get(1) + targetType() + parts.get(2));
    }
  }
}

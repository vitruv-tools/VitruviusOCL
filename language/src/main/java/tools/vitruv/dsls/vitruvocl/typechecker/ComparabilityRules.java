/* ******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Max Oesterle - initial API and implementation
 *******************************************************************************/

package tools.vitruv.dsls.vitruvocl.typechecker;

import tools.vitruv.dsls.vitruvocl.types.Type;
import tools.vitruv.dsls.vitruvocl.types.TypeResolver;

/**
 * Which operand types the comparison operators accept: {@code ==}/{@code !=} need single values of
 * comparable types, the ordering operators {@code <}, {@code <=}, {@code >}, {@code >=} need
 * numeric (or {@code OclAny}) operands. An operand of type {@code ERROR} is accepted everywhere,
 * since its error has already been reported.
 */
final class ComparabilityRules {

  private ComparabilityRules() {}

  /**
   * Whether {@code type} is a collection that may hold more than one element.
   *
   * @param type the operand type
   * @return {@code true} for a collection whose upper bound is not 1
   */
  static boolean isMultiValued(Type type) {
    return type.isCollection() && type.getUpperBound() != 1;
  }

  /**
   * Checks if two types are comparable.
   *
   * <p>Types are comparable if:
   *
   * <ul>
   *   <li>Either is Type.ERROR (error already reported)
   *   <li>They are equal
   *   <li>One conforms to the other (subtype relationship)
   *   <li>Either is an optional {@code OclAny} (stands for {@code null})
   *   <li>Their element types are comparable, after unwrapping singletons, optionals and
   *       collections
   * </ul>
   *
   * @param t1 First type
   * @param t2 Second type
   * @return true if types are comparable
   */
  @SuppressWarnings("java:S3776")
  static boolean areComparable(Type t1, Type t2) {
    if (t1 == Type.ERROR || t2 == Type.ERROR) {
      return true;
    }
    if (t1.equals(t2)) {
      return true;
    }
    if (t1.isConformantTo(t2) || t2.isConformantTo(t1)) {
      return true;
    }

    // Optional ANY (?Any?) is comparable to anything — represents null
    if ((t1.isOptional() && t1.getElementType() == Type.ANY)
        || (t2.isOptional() && t2.getElementType() == Type.ANY)) {
      return true;
    }

    // Unwrap singletons and optionals for comparison (¡T! and ¿T? both unwrap to T)
    Type e1 = (t1.isSingleton() || t1.isOptional() || t1.isCollection()) ? t1.getElementType() : t1;
    Type e2 = (t2.isSingleton() || t2.isOptional() || t2.isCollection()) ? t2.getElementType() : t2;
    if (!e1.equals(t1) || !e2.equals(t2)) {
      return areComparable(e1, e2);
    }

    return false;
  }

  /**
   * Checks if two types can be ordered: both numeric, or either {@code OclAny} or {@code ERROR},
   * after unwrapping singletons and optionals.
   *
   * @param t1 First type
   * @param t2 Second type
   * @return true if the ordering operators accept the types
   */
  static boolean areOrderable(Type t1, Type t2) {
    // Unwrap ¡T! and ¿T? for ordering checks
    Type e1 = (t1.isSingleton() || t1.isOptional()) ? t1.getElementType() : t1;
    Type e2 = (t2.isSingleton() || t2.isOptional()) ? t2.getElementType() : t2;
    if (e1 == Type.ERROR || e2 == Type.ERROR) {
      return true;
    }
    if (e1 == Type.ANY || e2 == Type.ANY) {
      return true;
    }
    return TypeResolver.isNumeric(e1) && TypeResolver.isNumeric(e2);
  }
}

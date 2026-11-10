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
package tools.vitruv.dsls.vitruvocl.operations;

import java.util.List;

/**
 * A built-in VitruvOCL operation called with dot syntax ({@code receiver.name(...)}).
 *
 * @param name the operation name as written in source, e.g. {@code "select"}
 * @param family what kind of receiver the operation applies to
 * @param params names of the parenthesized arguments, in order (empty for {@code name()});
 *     iterators list no parameters, their {@code (x | body)} shape is implied by the family
 */
public record OclOperation(String name, Family family, List<String> params) {

  /** Receiver category of an operation. */
  public enum Family {
    /** Collection queries, modifications, conversions and aggregates. */
    COLLECTION,
    /** Arithmetic on numeric (singleton) receivers. */
    NUMERIC,
    /** Iterator operations with an {@code (x | body)} argument. */
    ITERATOR,
    /** String operations. */
    STRING,
    /** Type tests and casts taking a type argument. */
    TYPE,
    /** Operations on a metaclass itself, e.g. {@code Pkg::Class.allInstances()}. */
    CLASSIFIER
  }

  /** Name of the {@code iterate} operation, whose argument shape differs from other iterators. */
  public static final String ITERATE = "iterate";

  /** Creates an operation; {@code params} is copied. */
  public OclOperation {
    params = List.copyOf(params);
  }

  /**
   * Returns an LSP snippet that inserts a call of this operation, e.g. {@code select(${1:x} |
   * $0)} or {@code insertAt(${1:index}, ${2:element})}.
   *
   * @return the snippet text (LSP snippet syntax)
   */
  public String snippet() {
    if (ITERATE.equals(name)) {
      return name + "(${1:x}; ${2:acc} = ${3:init} | $0)";
    }
    if (family == Family.ITERATOR) {
      return name + "(${1:x} | $0)";
    }
    return switch (params.size()) {
      case 0 -> name + "()";
      case 1 -> name + "($0)";
      default -> {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < params.size(); i++) {
          if (i > 0) {
            sb.append(", ");
          }
          sb.append("${").append(i + 1).append(':').append(params.get(i)).append('}');
        }
        yield sb.append(')').toString();
      }
    };
  }
}

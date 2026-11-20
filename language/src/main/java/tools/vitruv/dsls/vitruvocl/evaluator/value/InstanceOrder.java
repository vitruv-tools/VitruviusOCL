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

package tools.vitruv.dsls.vitruvocl.evaluator.value;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.emf.ecore.EObject;

/**
 * Total order over model instances by identity.
 *
 * <p>Orders primarily by {@link System#identityHashCode}. Identity hashes are not unique (with
 * ~10<sup>5</sup> live objects a collision is likely), so colliding distinct instances are ordered
 * by a lazily assigned sequence number. Only colliding instances ever receive one, and the map
 * holds them weakly, so it neither grows with model size nor keeps instances alive.
 *
 * <p>Assumes model classes keep EMF's default identity {@code equals}/{@code hashCode} (as {@code
 * EObjectImpl} does); {@link OCLElement#semanticEquals} relies on the same assumption.
 */
final class InstanceOrder {

  private static final Map<EObject, Long> TIE_BREAKERS =
      Collections.synchronizedMap(new WeakHashMap<>());
  private static final AtomicLong NEXT_TIE_BREAKER = new AtomicLong();

  private InstanceOrder() {}

  /**
   * Compares two instances by identity.
   *
   * @return zero iff both denote the same instance; otherwise a stable, antisymmetric order
   */
  static int compare(EObject a, EObject b) {
    return compare(a, b, System.identityHashCode(a), System.identityHashCode(b));
  }

  /** Same as {@link #compare(EObject, EObject)} with the identity hashes supplied (testable). */
  static int compare(EObject a, EObject b, int hashA, int hashB) {
    if (a == b || a.equals(b)) {
      return 0;
    }
    int byHash = Integer.compare(hashA, hashB);
    if (byHash != 0) {
      return byHash;
    }
    return Long.compare(tieBreakerOf(a), tieBreakerOf(b));
  }

  private static long tieBreakerOf(EObject instance) {
    return TIE_BREAKERS.computeIfAbsent(instance, key -> NEXT_TIE_BREAKER.getAndIncrement());
  }
}

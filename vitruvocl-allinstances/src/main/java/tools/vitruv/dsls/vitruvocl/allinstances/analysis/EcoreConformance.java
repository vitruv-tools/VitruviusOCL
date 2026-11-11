package tools.vitruv.dsls.vitruvocl.allinstances.analysis;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EcorePackage;

/**
 * Type conformance as OCL sees it: unlike {@link EClass#isSuperTypeOf}, every class conforms to
 * {@code ecore::EObject}, including classes that do not list it as a supertype.
 */
public final class EcoreConformance {

  private EcoreConformance() {}

  /** Whether {@code type} is {@code ecore::EObject}, also from a second copy of Ecore. */
  public static boolean isEObject(EClass type) {
    return type == EcorePackage.Literals.EOBJECT
        || type != null
            && "EObject".equals(type.getName())
            && type.getEPackage() != null
            && EcorePackage.eNS_URI.equals(type.getEPackage().getNsURI());
  }

  /** Whether instances of {@code runtime} are instances of {@code type}. */
  public static boolean conforms(EClass runtime, EClass type) {
    return isEObject(type) || type.isSuperTypeOf(runtime);
  }
}

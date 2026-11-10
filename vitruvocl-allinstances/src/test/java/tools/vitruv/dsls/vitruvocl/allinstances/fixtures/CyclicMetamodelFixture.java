package tools.vitruv.dsls.vitruvocl.allinstances.fixtures;

import java.util.List;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;

/**
 * A minimal, artificially cyclic metamodel used to prove that {@link
 * tools.vitruv.dsls.vitruvocl.allinstances.analysis.ContainmentReachabilityAnalyzer} terminates on
 * cyclic containment structures: {@code A} containment-references {@code B} via {@code b}, and
 * {@code B} containment-references {@code A} back via {@code a}. {@code B} additionally contains
 * {@code C} via {@code c}, a side branch that is only reachable through the cycle.
 *
 * <p>Instance models alternate between distinct objects ({@code A1 -b-> B1 -a-> A2 -b-> B2 ...});
 * no object is ever contained within its own containment ancestry.
 */
public final class CyclicMetamodelFixture {

  public final EPackage cyclicPackage;
  public final EClass classA;
  public final EClass classB;
  public final EClass classC;
  public final EReference aToB;
  public final EReference bToA;
  public final EReference bToC;

  /** Builds the metamodel. */
  public CyclicMetamodelFixture() {
    EcoreFactory ecoreFactory = EcoreFactory.eINSTANCE;

    cyclicPackage = ecoreFactory.createEPackage();
    cyclicPackage.setName("cyclic");
    cyclicPackage.setNsPrefix("cyc");
    cyclicPackage.setNsURI("http://example.de/cyclic");

    classA = ecoreFactory.createEClass();
    classA.setName("A");
    classB = ecoreFactory.createEClass();
    classB.setName("B");
    classC = ecoreFactory.createEClass();
    classC.setName("C");

    cyclicPackage.getEClassifiers().addAll(List.of(classA, classB, classC));

    aToB = ecoreFactory.createEReference();
    aToB.setName("b");
    aToB.setContainment(true);
    aToB.setEType(classB);
    aToB.setUpperBound(1);
    classA.getEStructuralFeatures().add(aToB);

    bToA = ecoreFactory.createEReference();
    bToA.setName("a");
    bToA.setContainment(true);
    bToA.setEType(classA);
    bToA.setUpperBound(1);
    classB.getEStructuralFeatures().add(bToA);

    bToC = ecoreFactory.createEReference();
    bToC.setName("c");
    bToC.setContainment(true);
    bToC.setEType(classC);
    bToC.setUpperBound(-1);
    classB.getEStructuralFeatures().add(bToC);
  }

  /** Creates a new instance of {@code eClass} using this metamodel's dynamic EFactory. */
  public EObject create(EClass eClass) {
    return cyclicPackage.getEFactoryInstance().create(eClass);
  }
}

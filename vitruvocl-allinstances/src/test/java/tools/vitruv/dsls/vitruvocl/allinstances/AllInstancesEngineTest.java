package tools.vitruv.dsls.vitruvocl.allinstances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.vitruv.dsls.vitruvocl.allinstances.fixtures.ModelBuilding.addMany;

import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.jupiter.api.Test;
import tools.vitruv.dsls.vitruvocl.allinstances.cache.AllInstancesCallSite;
import tools.vitruv.dsls.vitruvocl.allinstances.cache.AllInstancesCallSite.Kind;
import tools.vitruv.dsls.vitruvocl.allinstances.fixtures.CyclicMetamodelFixture;
import tools.vitruv.dsls.vitruvocl.allinstances.fixtures.UniversityMetamodelFixture;

/** End-to-end tests tying static analysis, pruning, and single-pass collection together. */
class AllInstancesEngineTest {

  @Test
  void endToEnd_prunesModulesAndCollectsCorrectInstances() {
    UniversityMetamodelFixture f = new UniversityMetamodelFixture();

    EObject university = f.create(f.universityClass);
    EObject department = f.create(f.departmentClass);
    addMany(university, f.universityDepartments, department);

    EObject student = f.create(f.studentClass);
    EObject staff = f.create(f.staffClass);
    addMany(department, f.departmentMembers, student, staff);

    EObject webpage = f.create(f.webPageClass);
    staff.eSet(f.staffWebpage, webpage);

    EObject module = f.create(f.moduleClass);
    addMany(department, f.departmentModules, module);
    EObject lecture = f.create(f.lectureClass);
    addMany(module, f.moduleLectures, lecture);

    AllInstancesEngine engine =
        new AllInstancesEngine(
            f.universityPackage,
            List.of(
                new AllInstancesCallSite(f.memberClass, Kind.ALL_OF_KIND),
                new AllInstancesCallSite(f.webPageClass, Kind.ALL_OF_TYPE)));

    assertFalse(engine.getTraversableRefs().contains(f.departmentModules));
    assertFalse(engine.getTraversableRefs().contains(f.moduleLectures));
    assertTrue(engine.getTraversableRefs().contains(f.departmentMembers));
    assertTrue(engine.getTraversableRefs().contains(f.staffWebpage));

    Map<EClass, List<EObject>> result = engine.compute(List.of(university));

    assertEquals(2, result.get(f.memberClass).size());
    assertTrue(result.get(f.memberClass).containsAll(List.of(student, staff)));
    assertEquals(List.of(webpage), result.get(f.webPageClass));
  }

  @Test
  void engineIsReusableAcrossComputeCallsAsTheInstanceModelChanges() {
    UniversityMetamodelFixture f = new UniversityMetamodelFixture();
    AllInstancesEngine engine =
        new AllInstancesEngine(
            f.universityPackage,
            List.of(new AllInstancesCallSite(f.studentClass, Kind.ALL_OF_TYPE)));

    EObject department = f.create(f.departmentClass);
    EObject student1 = f.create(f.studentClass);
    addMany(department, f.departmentMembers, student1);

    Map<EClass, List<EObject>> firstResult = engine.compute(List.of(department));
    assertEquals(1, firstResult.get(f.studentClass).size());

    // The instance model changes between calls; the same engine (same cacheConfig/traverse) must
    // reflect the new state on the next compute(), since only the metamodel-derived cache is
    // reused.
    EObject student2 = f.create(f.studentClass);
    addMany(department, f.departmentMembers, student2);

    Map<EClass, List<EObject>> secondResult = engine.compute(List.of(department));
    assertEquals(2, secondResult.get(f.studentClass).size());
    assertTrue(secondResult.get(f.studentClass).containsAll(List.of(student1, student2)));
  }

  /**
   * Model {@code A1 -b-> B1 -a-> A2 -b-> B2}, with a {@code C} below each {@code B}. The second
   * {@code C} is only reachable by traversing {@code B.a}, i.e. by going once around the cycle.
   */
  @Test
  void cyclicMetamodel_collectsInstancesBehindTheCycle() {
    CyclicMetamodelFixture f = new CyclicMetamodelFixture();
    EObject a1 = f.create(f.classA);
    EObject b1 = f.create(f.classB);
    EObject a2 = f.create(f.classA);
    EObject b2 = f.create(f.classB);
    EObject c1 = f.create(f.classC);
    EObject c2 = f.create(f.classC);
    a1.eSet(f.aToB, b1);
    addMany(b1, f.bToC, c1);
    b1.eSet(f.bToA, a2);
    a2.eSet(f.aToB, b2);
    addMany(b2, f.bToC, c2);

    AllInstancesEngine engine =
        new AllInstancesEngine(
            f.cyclicPackage, List.of(new AllInstancesCallSite(f.classC, Kind.ALL_OF_KIND)));

    // Pre-order in declaration order: B.a (declared before B.c) is descended first.
    assertEquals(List.of(c2, c1), engine.compute(List.of(a1)).get(f.classC));
  }

  @Test
  void supertypeQuery_collectsInstancesHeldBySubtypeTypedReference() {
    EcoreFactory factory = EcoreFactory.eINSTANCE;
    EPackage pkg = factory.createEPackage();
    pkg.setName("persons");
    pkg.setNsURI("http://example.de/persons");
    EClass register = factory.createEClass();
    register.setName("Register");
    EClass person = factory.createEClass();
    person.setName("Person");
    person.setAbstract(true);
    EClass male = factory.createEClass();
    male.setName("Male");
    male.getESuperTypes().add(person);
    pkg.getEClassifiers().addAll(List.of(register, person, male));
    EReference males = factory.createEReference();
    males.setName("males");
    males.setContainment(true);
    males.setUpperBound(-1);
    males.setEType(male);
    register.getEStructuralFeatures().add(males);

    EObject root = pkg.getEFactoryInstance().create(register);
    EObject homer = pkg.getEFactoryInstance().create(male);
    addMany(root, males, homer);

    AllInstancesEngine engine =
        new AllInstancesEngine(pkg, List.of(new AllInstancesCallSite(person, Kind.ALL_OF_KIND)));

    assertTrue(engine.getTraversableRefs().contains(males));
    assertEquals(List.of(homer), engine.compute(List.of(root)).get(person));
  }
}

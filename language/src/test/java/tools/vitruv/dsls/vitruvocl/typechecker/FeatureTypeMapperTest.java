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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.vitruv.dsls.vitruvocl.VitruvOCLLexer;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.ErrorCollector;
import tools.vitruv.dsls.vitruvocl.common.OclLexers;
import tools.vitruv.dsls.vitruvocl.incremental.support.FakeCorrespondenceModel;
import tools.vitruv.dsls.vitruvocl.incremental.support.LiveModel;
import tools.vitruv.dsls.vitruvocl.model.ModelAccess;
import tools.vitruv.dsls.vitruvocl.symboltable.ScopeAnnotator;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableBuilder;
import tools.vitruv.dsls.vitruvocl.symboltable.SymbolTableImpl;

/**
 * Which type the type checker gives a navigated EMF feature: per data type, custom data type
 * instance class, multiplicity, ordering and uniqueness, and for features found only on subtypes of
 * an abstract class. Observed through the type error of an invariant body that is not Boolean; an
 * expectation ending in {@code " ..."} is a message prefix.
 */
class FeatureTypeMapperTest {

  private static final EPackage PACKAGE = createPackage();
  private static final ModelAccess MODEL = createModel();

  private static EPackage createPackage() {
    EcoreFactory f = EcoreFactory.eINSTANCE;
    EPackage pkg = f.createEPackage();
    pkg.setName("ftm");
    pkg.setNsPrefix("ftm");
    pkg.setNsURI("http://vitruvocl.test/typechecker/ftm");

    EClass holder = eClass(pkg, "Holder");
    EClass base = eClass(pkg, "Base");
    base.setAbstract(true);
    EClass sub1 = eClass(pkg, "Sub1");
    sub1.getESuperTypes().add(base);
    EClass sub2 = eClass(pkg, "Sub2");
    sub2.getESuperTypes().add(base);

    attribute(holder, "aString", EcorePackage.Literals.ESTRING, 1, 1);
    attribute(holder, "anInt", EcorePackage.Literals.EINT, 0, 1);
    attribute(holder, "aBoolean", EcorePackage.Literals.EBOOLEAN, 1, 1);
    attribute(holder, "aFloat", EcorePackage.Literals.EFLOAT, 1, 1);
    attribute(holder, "aDouble", EcorePackage.Literals.EDOUBLE, 1, 1);
    attribute(holder, "aLong", EcorePackage.Literals.ELONG, 1, 1);

    EEnum color = f.createEEnum();
    color.setName("Color");
    EEnumLiteral red = f.createEEnumLiteral();
    red.setName("RED");
    color.getELiterals().add(red);
    pkg.getEClassifiers().add(color);
    attribute(holder, "anEnum", color, 1, 1);

    String[][] dataTypes = {
      {"JavaString", "java.lang.String"},
      {"ShortString", "String"},
      {"PrimitiveInt", "int"},
      {"JavaInteger", "java.lang.Integer"},
      {"PrimitiveBoolean", "boolean"},
      {"ShortBoolean", "Boolean"},
      {"JavaBoolean", "java.lang.Boolean"},
      {"PrimitiveFloat", "float"},
      {"JavaFloat", "java.lang.Float"},
      {"PrimitiveDouble", "double"},
      {"JavaDouble", "java.lang.Double"},
      {"BigDecimal", "java.math.BigDecimal"},
      {"NoInstanceClass", null}
    };
    for (String[] dataType : dataTypes) {
      EDataType type = f.createEDataType();
      type.setName(dataType[0]);
      type.setInstanceClassName(dataType[1]);
      pkg.getEClassifiers().add(type);
      attribute(holder, "dt" + dataType[0], type, 1, 1);
    }

    attribute(holder, "orderedUnique", EcorePackage.Literals.EINT, 0, -1);
    attribute(holder, "orderedNonUnique", EcorePackage.Literals.EINT, 0, -1).setUnique(false);
    attribute(holder, "unorderedUnique", EcorePackage.Literals.EINT, 0, -1).setOrdered(false);
    EAttribute bag = attribute(holder, "unorderedNonUnique", EcorePackage.Literals.EINT, 1, 3);
    bag.setOrdered(false);
    bag.setUnique(false);

    reference(holder, "optionalRef", holder, 0, 1);
    reference(holder, "requiredRef", holder, 1, 1);
    reference(holder, "base", base, 1, 1);
    reference(holder, "bases", base, 0, -1);

    attribute(sub1, "shared", EcorePackage.Literals.EINT, 1, 1);
    attribute(sub2, "shared", EcorePackage.Literals.EINT, 1, 1);
    attribute(sub1, "onlySub1", EcorePackage.Literals.ESTRING, 1, 1);
    attribute(sub1, "clash", EcorePackage.Literals.EINT, 1, 1);
    attribute(sub2, "clash", EcorePackage.Literals.ESTRING, 1, 1);

    EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
    return pkg;
  }

  @AfterAll
  static void unregisterPackage() {
    EPackage.Registry.INSTANCE.remove(PACKAGE.getNsURI());
  }

  private static EClass eClass(EPackage pkg, String name) {
    EClass c = EcoreFactory.eINSTANCE.createEClass();
    c.setName(name);
    pkg.getEClassifiers().add(c);
    return c;
  }

  private static EAttribute attribute(
      EClass owner, String name, EClassifier type, int lower, int upper) {
    EAttribute a = EcoreFactory.eINSTANCE.createEAttribute();
    a.setName(name);
    a.setEType(type);
    a.setLowerBound(lower);
    a.setUpperBound(upper);
    owner.getEStructuralFeatures().add(a);
    return a;
  }

  private static void reference(EClass owner, String name, EClass type, int lower, int upper) {
    EReference r = EcoreFactory.eINSTANCE.createEReference();
    r.setName(name);
    r.setEType(type);
    r.setLowerBound(lower);
    r.setUpperBound(upper);
    owner.getEStructuralFeatures().add(r);
  }

  private static ModelAccess createModel() {
    ResourceSet rs = new ResourceSetImpl();
    rs.getResourceFactoryRegistry()
        .getExtensionToFactoryMap()
        .put("*", new XMIResourceFactoryImpl());
    Resource resource = rs.createResource(URI.createURI("memory:/holder.ftm"));
    resource.getContents().add(EcoreUtil.create((EClass) PACKAGE.getEClassifier("Holder")));
    return LiveModel.of(rs, List.of(resource), new FakeCorrespondenceModel()).wrapper();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "=>",
      quoteCharacter = '`',
      textBlock =
          """
          self.aString                => Invariant must be Boolean, got !String!
          self.anInt                  => Invariant must be Boolean, got !Integer!
          self.aBoolean               => ok
          self.aFloat                 => Invariant must be Boolean, got !Float!
          self.aDouble                => Invariant must be Boolean, got !Double!
          self.aLong                  => Invariant must be Boolean, got !String!
          self.anEnum                 => Invariant must be Boolean, got !Color!
          self.dtJavaString           => Invariant must be Boolean, got !String!
          self.dtShortString          => Invariant must be Boolean, got !String!
          self.dtPrimitiveInt         => Invariant must be Boolean, got !Integer!
          self.dtJavaInteger          => Invariant must be Boolean, got !Integer!
          self.dtPrimitiveBoolean     => Invariant must be Boolean, got !String!
          self.dtShortBoolean         => ok
          self.dtJavaBoolean          => ok
          self.dtPrimitiveFloat       => Invariant must be Boolean, got !Float!
          self.dtJavaFloat            => Invariant must be Boolean, got !Float!
          self.dtPrimitiveDouble      => Invariant must be Boolean, got !Double!
          self.dtJavaDouble           => Invariant must be Boolean, got !Double!
          self.dtBigDecimal           => Invariant must be Boolean, got !String!
          self.dtNoInstanceClass      => ok
          self.orderedUnique          => Invariant must be Boolean, got <Integer>
          self.orderedNonUnique       => Invariant must be Boolean, got [Integer]
          self.unorderedUnique        => Invariant must be Boolean, got {Integer}
          self.unorderedNonUnique     => Invariant must be Boolean, got {{Integer}}
          self.optionalRef            => Invariant must be Boolean, got ?ftm::Holder?
          self.requiredRef            => Invariant must be Boolean, got !ftm::Holder!
          self.base.shared            => Invariant must be Boolean, got !Integer!
          self.base.onlySub1          => Invariant must be Boolean, got !String!
          self.base.clash             => Unknown property 'clash' on type 'Base' ...
          self.bases.shared           => Invariant must be Boolean, got <Integer>
          self.bases.clash            => Unknown property 'clash' on type 'Base' ...
          """)
  void featureType(String body, String expected) {
    List<CompileError> errors = typeCheck("context ftm::Holder inv: " + body);
    String actual =
        errors.isEmpty()
            ? "ok"
            : errors.stream().map(CompileError::getMessage).collect(Collectors.joining(" | "));
    if (expected.endsWith(" ...")) {
      String prefix = expected.substring(0, expected.length() - 3);
      assertTrue(actual.startsWith(prefix), () -> "expected " + expected + " but was " + actual);
    } else {
      assertEquals(expected, actual);
    }
  }

  private static List<CompileError> typeCheck(String source) {
    VitruvOCLLexer lexer = OclLexers.create(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    VitruvOCLParser parser = new VitruvOCLParser(tokens);
    parser.removeErrorListeners();
    VitruvOCLParser.ContextDeclCSContext tree = parser.contextDeclCS();
    assertEquals(0, parser.getNumberOfSyntaxErrors(), () -> "syntax error in " + source);

    ErrorCollector errors = new ErrorCollector();
    SymbolTableImpl symbolTable = new SymbolTableImpl(MODEL);
    ScopeAnnotator scopes = new ScopeAnnotator();
    new SymbolTableBuilder(symbolTable, MODEL, errors, scopes).visit(tree);
    TypeCheckVisitor typeChecker = new TypeCheckVisitor(symbolTable, MODEL, errors, scopes);
    typeChecker.setTokenStream(tokens);
    typeChecker.visit(tree);
    return errors.getErrors();
  }
}

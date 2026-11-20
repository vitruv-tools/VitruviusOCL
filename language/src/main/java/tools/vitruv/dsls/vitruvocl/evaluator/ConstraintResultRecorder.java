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

package tools.vitruv.dsls.vitruvocl.evaluator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor.SkippedConstraint;
import tools.vitruv.dsls.vitruvocl.evaluator.EvaluationVisitor.ViolationRecord;
import tools.vitruv.dsls.vitruvocl.evaluator.value.OCLElement;
import tools.vitruv.dsls.vitruvocl.evaluator.value.Value;

/**
 * Bookkeeping of one evaluation run of the {@link EvaluationVisitor}: which instances violated
 * which constraint, with the constraint's {@code @severity} and interpolated {@code @message}, and
 * which {@code pre}/{@code post} blocks were skipped for lack of a transaction.
 */
final class ConstraintResultRecorder {

  /** Matches {@code {self.attr}} placeholders in {@code @message} templates. */
  private static final Pattern SELF_ATTRIBUTE_PLACEHOLDER =
      Pattern.compile("\\{self\\.([a-zA-Z_]\\w*)\\}");

  /**
   * EObject instances that violated a constraint, in evaluation order: one entry per instance and
   * constraint that evaluated to false. Used by callers to produce precise violation messages.
   */
  private final List<EObject> violatingInstances = new ArrayList<>();

  /** One record per violated constraint and instance, in evaluation order. */
  private final List<ViolationRecord> violationRecords = new ArrayList<>();

  /** The {@code pre}/{@code post} blocks not evaluated for lack of a transaction. */
  private final List<SkippedConstraint> skippedConstraints = new ArrayList<>();

  /**
   * Shared bookkeeping for one inv/pre/post evaluation result against one instance: appends the
   * boolean element to {@code allResults} and, if the result is false (or unreadable as a boolean),
   * records the instance as violating with its severity/message annotations.
   */
  void record(
      Value result,
      List<OCLElement> allResults,
      EObject instance,
      List<VitruvOCLParser.AnnotationCSContext> annotations) {
    if (result == null || result.isEmpty()) {
      return;
    }
    OCLElement elem = result.getElements().get(0);
    allResults.add(elem);
    Boolean boolResult = elem.tryGetBool();
    if (boolResult == null || !boolResult) {
      violatingInstances.add(instance);
      String severity = extractSeverity(annotations);
      String customMessage = extractCustomMessage(annotations, instance);
      violationRecords.add(new ViolationRecord(severity, customMessage, instance));
    }
  }

  private static String extractSeverity(List<VitruvOCLParser.AnnotationCSContext> annotations) {
    for (VitruvOCLParser.AnnotationCSContext ann : annotations) {
      if (ann instanceof VitruvOCLParser.SeverityAnnotationContext sev) {
        return sev.severityValue.getText();
      }
    }
    return "WARNING";
  }

  /**
   * Returns the interpolated {@code @message} template for the constraint, or {@code null} when no
   * {@code @message} annotation is present.
   */
  private static String extractCustomMessage(
      List<VitruvOCLParser.AnnotationCSContext> annotations, EObject instance) {
    for (VitruvOCLParser.AnnotationCSContext ann : annotations) {
      if (ann instanceof VitruvOCLParser.MessageAnnotationContext msg) {
        String raw = msg.message.getText();
        String template = raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : raw;
        return interpolateTemplate(template, instance);
      }
    }
    return null;
  }

  @SuppressWarnings("java:S125")
  private static String interpolateTemplate(String template, EObject instance) {
    // Replace {self.attr}
    Matcher m = SELF_ATTRIBUTE_PLACEHOLDER.matcher(template);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String attr = m.group(1);
      EStructuralFeature feature = instance.eClass().getEStructuralFeature(attr);
      String val;
      if (feature == null) {
        val = "{" + attr + "}";
      } else {
        String raw = String.valueOf(instance.eGet(feature));
        boolean isString = feature.getEType() == EcorePackage.Literals.ESTRING;
        val = isString ? "\"" + raw + "\"" : raw;
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(val));
    }
    m.appendTail(sb);
    // Replace bare {self}
    return sb.toString()
        .replace(
            "{self}", instance.eClass().getName() + "@" + Integer.toHexString(instance.hashCode()));
  }

  private static String nameOf(TerminalNode idToken) {
    return idToken != null ? idToken.getText() : null;
  }

  /** Best-effort display name for a context header, used only in skip notices. */
  private static String describeContextForSkipNotice(
      VitruvOCLParser.ClassifierContextCSContext ctx) {
    if (ctx.metamodel != null && ctx.className != null) {
      return ctx.metamodel.getText() + "::" + ctx.className.getText();
    }
    if (ctx.contextName != null) {
      return ctx.contextName.getText();
    }
    return "<context>";
  }

  /**
   * Records every {@code pre}/{@code post} block of {@code ctx} as skipped, once per context (not
   * per instance: which blocks are skipped is a static, instance-independent fact).
   */
  void recordSkippedBlocks(VitruvOCLParser.ClassifierContextCSContext ctx) {
    if (ctx.preCS().isEmpty() && ctx.postCS().isEmpty()) {
      return;
    }
    String contextName = describeContextForSkipNotice(ctx);
    for (VitruvOCLParser.PreCSContext pre : ctx.preCS()) {
      skippedConstraints.add(new SkippedConstraint("pre", nameOf(pre.ID()), contextName));
    }
    for (VitruvOCLParser.PostCSContext post : ctx.postCS()) {
      skippedConstraints.add(new SkippedConstraint("post", nameOf(post.ID()), contextName));
    }
  }

  /** The violating instances in evaluation order (unmodifiable). */
  List<EObject> violatingInstances() {
    return Collections.unmodifiableList(violatingInstances);
  }

  /** The violation records in evaluation order (unmodifiable). */
  List<ViolationRecord> violationRecords() {
    return Collections.unmodifiableList(violationRecords);
  }

  /** The skipped {@code pre}/{@code post} blocks (unmodifiable). */
  List<SkippedConstraint> skippedConstraints() {
    return Collections.unmodifiableList(skippedConstraints);
  }
}

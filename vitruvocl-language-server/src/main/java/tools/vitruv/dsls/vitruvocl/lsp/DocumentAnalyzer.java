/* ******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package tools.vitruv.dsls.vitruvocl.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import tools.vitruv.dsls.vitruvocl.VitruvOCLParser;
import tools.vitruv.dsls.vitruvocl.adapter.emf.MetamodelWrapper;
import tools.vitruv.dsls.vitruvocl.analysis.OclAnalysis;
import tools.vitruv.dsls.vitruvocl.analysis.OclAnalyzer;
import tools.vitruv.dsls.vitruvocl.analysis.SyntaxError;
import tools.vitruv.dsls.vitruvocl.common.CompileError;
import tools.vitruv.dsls.vitruvocl.common.EditDistance;
import tools.vitruv.dsls.vitruvocl.common.ErrorSeverity;
import tools.vitruv.dsls.vitruvocl.types.Type;

/**
 * Runs {@link OclAnalyzer} in tolerant mode (symbol-table construction + type checking, also after
 * syntax errors) on the current document text and turns its result into a {@link DocumentAnalysis}
 * with LSP diagnostics.
 *
 * <p>The evaluation pass (Pass 3) is intentionally omitted — the language server performs static
 * analysis only; constraint evaluation is handled separately by the CLI runner.
 *
 * <p>ANTLR's built-in error recovery ensures that a parse tree is always produced, even for
 * syntactically broken documents. Both passes are always attempted so that type-error diagnostics
 * are emitted even when the document has syntax errors.
 */
public class DocumentAnalyzer {

  private static final Logger LOG = Logger.getLogger(DocumentAnalyzer.class.getName());

  private static final String LANGUAGE_ID = "vitruvOCL";

  private final MetamodelWrapper wrapper;

  /**
   * Creates a document analyzer backed by the given metamodel wrapper.
   *
   * @param wrapper metamodel and instance access used during type checking
   */
  public DocumentAnalyzer(MetamodelWrapper wrapper) {
    this.wrapper = wrapper;
  }

  /*
   * Strips import declaration lines, preserving line numbers by replacing content with a comment.
   */
  private static String stripImportLines(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (String line : text.split("\n", -1)) {
      String trimmed = line.stripLeading();
      if (trimmed.startsWith("import ")) {
        // Replace with a blank comment of the same length so line numbers are preserved
        sb.append("--").append(" ".repeat(Math.max(0, line.length() - 2)));
      } else {
        sb.append(line);
      }
      sb.append('\n');
    }
    // Remove the trailing '\n' added for the last line if original didn't have it
    if (!text.endsWith("\n") && !sb.isEmpty()) {
      sb.deleteCharAt(sb.length() - 1);
    }
    return sb.toString();
  }

  /**
   * Analyzes {@code documentText} and returns a fresh {@link DocumentAnalysis}.
   *
   * <p>Never throws — all exceptions are swallowed and reported as an internal-error diagnostic so
   * that the language server remains stable even when the document is in an extreme broken state.
   *
   * @param documentText the full document source
   * @return the analysis result, including any diagnostics
   */
  public DocumentAnalysis analyze(String documentText) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    VitruvOCLParser.ContextDeclCSContext tree = null;
    ParseTreeProperty<Type> nodeTypes = null;

    // Strip import declarations before parsing — the VitruvOCL grammar has no import rule.
    // We replace them with blank comments to preserve line numbers for diagnostics.
    documentText = stripImportLines(documentText);

    // Pre-parse scan: flag @keyword typos with quick-fix suggestions before ANTLR runs.
    checkAnnotationKeywordTypos(documentText, diagnostics);

    try {
      // Lexer, parser and both passes, which always run so that hover and completion get what the
      // passes produce even for a document with syntax errors
      OclAnalysis analysis =
          OclAnalyzer.analyze(
              documentText, wrapper, OclAnalyzer.Mode.TOLERANT, new OclErrorStrategy());
      tree = analysis.tree();
      nodeTypes = analysis.nodeTypes();

      // Lexer and parser errors get keyword-typo suggestions; trailing input after the point where
      // the parser stopped (always last) is reported as the analyzer words it
      LspErrorListener errorListener = new LspErrorListener();
      for (SyntaxError error : analysis.reportedSyntaxErrors()) {
        if (error.kind() != SyntaxError.Kind.TRAILING_INPUT) {
          errorListener.add(error);
        }
      }
      List<Diagnostic> parserDiags = errorListener.getDiagnostics();
      diagnostics.addAll(parserDiags);
      for (SyntaxError error : analysis.reportedSyntaxErrors()) {
        if (error.kind() == SyntaxError.Kind.TRAILING_INPUT) {
          diagnostics.add(toDiagnostic(error));
        }
      }

      // A crashed pass: the diagnostics below are incomplete. One internal-error diagnostic per
      // document is enough.
      for (OclAnalysis.PassCrash crash : analysis.crashes()) {
        LOG.log(Level.WARNING, "[OCL-LS] " + crash.pass() + " crashed", crash.exception());
      }
      analysis.crashes().stream()
          .findFirst()
          .ifPresent(crash -> diagnostics.add(internalError(crash.pass(), crash.exception())));

      // -----------------------------------------------------------------------
      // Convert the passes' errors to LSP Diagnostics. Pass errors inside invariants that ANTLR's
      // error recovery mis-parented (see findOrphanedInvRanges) are suppressed, so that squiggles
      // do not bleed into the previous constraint. Only the parser's own errors indicate such a
      // recovery: trailing input lies after the parse tree.
      // -----------------------------------------------------------------------
      List<int[]> orphanedRanges = findOrphanedInvRanges(tree, parserDiags);
      for (CompileError error : analysis.errors()) {
        int errLine = Math.max(0, error.getLine() - 1); // 0-based
        boolean orphaned = false;
        for (int[] r : orphanedRanges) {
          if (errLine >= r[0] && errLine <= r[1]) {
            orphaned = true;
            break;
          }
        }
        if (orphaned) {
          continue;
        }

        Diagnostic d = toDiagnostic(error);
        diagnostics.add(d);
        LOG.fine(
            () ->
                String.format(
                    "[OCL-LS] DIAG type-checker  L%d:C%d → L%d:C%d  %s",
                    d.getRange().getStart().getLine(),
                    d.getRange().getStart().getCharacter(),
                    d.getRange().getEnd().getLine(),
                    d.getRange().getEnd().getCharacter(),
                    d.getMessage().getLeft()));
      }

    } catch (Exception e) {
      // Catch-all — return empty analysis with a single internal-error diagnostic.
      LOG.log(Level.WARNING, "[OCL-LS] Unexpected analysis error", e);
      diagnostics.add(internalError("analysis", e));
    }

    return new DocumentAnalysis(tree, nodeTypes, diagnostics);
  }

  /**
   * Builds the diagnostic for an analysis step that crashed, placed at the start of the document.
   *
   * @param step human-readable name of the step that failed
   * @param e the exception it threw
   */
  private static Diagnostic internalError(String step, Exception e) {
    return new Diagnostic(
        new Range(new Position(0, 0), new Position(0, 1)),
        "Internal language server error during "
            + step
            + " (diagnostics may be incomplete): "
            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
        DiagnosticSeverity.Error,
        LANGUAGE_ID);
  }

  // ---------------------------------------------------------------------------
  // Annotation keyword typo detection
  // ---------------------------------------------------------------------------

  private static final Pattern AT_WORD = Pattern.compile("@(\\w+)");
  private static final List<String> ANNOTATION_KEYWORDS = List.of("severity", "message");

  /**
   * Scans {@code text} for {@code @word} tokens that look like misspellings of {@code @severity} or
   * {@code @message} and appends a diagnostic with a quick-fix suggestion.
   */
  private static void checkAnnotationKeywordTypos(String text, List<Diagnostic> diagnostics) {
    String[] lines = text.split("\n", -1);
    for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
      Matcher m = AT_WORD.matcher(lines[lineIdx]);
      while (m.find()) {
        String word = m.group(1);
        // Exact match — valid annotation keyword, skip; otherwise report if it looks close.
        if (!ANNOTATION_KEYWORDS.contains(word)) {
          reportAnnotationTypoIfClose(word, m, lineIdx, diagnostics);
        }
      }
    }
  }

  /**
   * Reports a diagnostic for {@code word} if it is close enough (by edit distance) to a known
   * annotation keyword to be considered a likely typo.
   */
  private static void reportAnnotationTypoIfClose(
      String word, Matcher m, int lineIdx, List<Diagnostic> diagnostics) {
    // Find the closest known annotation keyword.
    String bestKeyword = null;
    int bestDist = Integer.MAX_VALUE;
    for (String kw : ANNOTATION_KEYWORDS) {
      int dist = EditDistance.damerauLevenshtein(word.toLowerCase(), kw);
      if (dist < bestDist) {
        bestDist = dist;
        bestKeyword = kw;
      }
    }

    // Only flag if within edit distance 3 (generous — covers @Severity, @severit, @sev, @msg).
    int threshold = EditDistance.editThreshold(word.length());
    if (bestKeyword == null || bestDist > threshold) {
      return;
    }

    int startCol = m.start(); // position of '@'
    int endCol = m.end();
    String suggestion = "@" + bestKeyword;
    String badAnnotation = "@" + word;

    Diagnostic d =
        new Diagnostic(
            new Range(new Position(lineIdx, startCol), new Position(lineIdx, endCol)),
            "Unknown annotation '" + badAnnotation + "'. Did you mean '" + suggestion + "'?",
            DiagnosticSeverity.Error,
            LANGUAGE_ID);
    d.setData(suggestion); // picked up by the code-action handler for the quick fix
    diagnostics.add(d);
    final int capturedLine = lineIdx;
    LOG.fine(
        () ->
            String.format(
                "[OCL-LS] DIAG annotation-typo L%d:C%d  %s → %s",
                capturedLine, startCol, badAnnotation, suggestion));
  }

  /**
   * Returns line ranges (0-based, inclusive) of {@code invCS} nodes that are almost certainly
   * mis-parented due to ANTLR error recovery consuming tokens from the next context block.
   *
   * <p>Heuristic: if a syntax error falls inside a {@code classifierContextCS}'s token range, any
   * {@code invCS} child whose start line is <em>after</em> that syntax error is considered orphaned
   * — it was absorbed by error recovery, not legitimately part of this context.
   */
  private static List<int[]> findOrphanedInvRanges(
      VitruvOCLParser.ContextDeclCSContext tree, List<Diagnostic> syntaxDiags) {

    List<int[]> orphaned = new ArrayList<>();
    if (tree == null || syntaxDiags.isEmpty()) {
      return orphaned;
    }

    for (VitruvOCLParser.ClassifierContextCSContext ctx : tree.classifierContextCS()) {
      if (ctx.getStart() != null) {
        collectOrphanedInvRanges(ctx, syntaxDiags, orphaned);
      }
    }
    return orphaned;
  }

  /** Finds orphaned {@code invCS} ranges within a single {@code classifierContextCS} block. */
  private static void collectOrphanedInvRanges(
      VitruvOCLParser.ClassifierContextCSContext ctx,
      List<Diagnostic> syntaxDiags,
      List<int[]> orphaned) {
    int ctxStart = ctx.getStart().getLine() - 1; // 0-based
    int ctxStop = ctx.getStop() != null ? ctx.getStop().getLine() - 1 : Integer.MAX_VALUE;

    // First syntax-error line that falls strictly inside this context block.
    int firstErrLine = Integer.MAX_VALUE;
    for (Diagnostic d : syntaxDiags) {
      int l = d.getRange().getStart().getLine(); // already 0-based
      if (l > ctxStart && l <= ctxStop && l < firstErrLine) {
        firstErrLine = l;
      }
    }

    if (firstErrLine == Integer.MAX_VALUE) {
      return;
    }

    for (VitruvOCLParser.InvCSContext inv : ctx.invCS()) {
      if (inv.getStart() != null) {
        addIfOrphaned(inv, firstErrLine, syntaxDiags, orphaned);
      }
    }
  }

  /**
   * Adds {@code inv}'s line range to {@code orphaned} if it starts after {@code firstErrLine}
   * (Case 1) or directly contains a syntax error (Case 2 — its type-checker diagnostics would be
   * unreliable cascade errors, so they are suppressed too).
   */
  private static void addIfOrphaned(
      VitruvOCLParser.InvCSContext inv,
      int firstErrLine,
      List<Diagnostic> syntaxDiags,
      List<int[]> orphaned) {
    int invStart = inv.getStart().getLine() - 1; // 0-based
    int invStop = inv.getStop() != null ? inv.getStop().getLine() - 1 : invStart;

    if (invStart > firstErrLine) {
      orphaned.add(new int[] {invStart, invStop});
      return;
    }

    boolean invHasSyntaxError = false;
    for (Diagnostic d : syntaxDiags) {
      int l = d.getRange().getStart().getLine(); // already 0-based
      if (l >= invStart && l <= invStop) {
        invHasSyntaxError = true;
        break;
      }
    }
    if (invHasSyntaxError) {
      orphaned.add(new int[] {invStart, invStop});
    }
  }

  /** A syntax error reported by the analyzer itself, spanning its token. */
  private static Diagnostic toDiagnostic(SyntaxError error) {
    int line = Math.max(0, error.line() - 1);
    int start = Math.max(0, error.column());
    int end = start + Math.max(1, error.offendingToken().getText().length());
    return new Diagnostic(
        new Range(new Position(line, start), new Position(line, end)),
        error.message(),
        DiagnosticSeverity.Error,
        LANGUAGE_ID);
  }

  private static Diagnostic toDiagnostic(CompileError error) {
    // ANTLR: line is 1-based, col 0-based → LSP: both 0-based.
    int startLine = Math.max(0, error.getLine() - 1);
    int startCol = Math.max(0, error.getColumn());

    int endLine;
    int endCol;
    if (error.getEndLine() > 0) {
      // Full span available — underline exactly the erroneous token range.
      endLine = Math.max(startLine, error.getEndLine() - 1);
      endCol = Math.max(startCol + 1, error.getEndColumn());
    } else {
      // No span — fall back to a single character so VS Code shows something.
      endLine = startLine;
      endCol = startCol + 1;
    }

    DiagnosticSeverity severity =
        error.getSeverity() == ErrorSeverity.ERROR
            ? DiagnosticSeverity.Error
            : DiagnosticSeverity.Warning;

    Diagnostic d =
        new Diagnostic(
            new Range(new Position(startLine, startCol), new Position(endLine, endCol)),
            error.getMessage(),
            severity,
            LANGUAGE_ID);

    // Attach the quick-fix suggestion as diagnostic data so the codeAction handler
    // can build a WorkspaceEdit without re-analysing the document.
    if (error.getSuggestion() != null) {
      d.setData(error.getSuggestion());
    }

    return d;
  }
}

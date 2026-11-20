/**
 * Finding the constraint units of an `.ocl` document without the language server, and the
 * annotation zone below a block header. Pure functions without `vscode`, so that they can be
 * tested with `node --test`.
 *
 * The scan follows the lexer of the grammar as far as finding blocks needs: `--` starts a comment
 * up to the end of the line, `"` a string up to the closing `"` (or the end of the line), and
 * `context`, `inv`, `pre` and `post` are keywords wherever they stand, also on a line of their own.
 * It yields units shaped like those of the language server's `vitruvocl/constraintUnits`.
 */
import type { ConstraintUnitInfo } from './constraintLayout';

/** A word or punctuation character outside comments and strings. Lines start at 0. */
interface Token {
    text: string;
    line: number;
    column: number;
    offset: number;
}

const WORD_START = /[A-Za-z_]/;
const WORD_PART = /[A-Za-z0-9_]/;

/** The words and punctuation of the text, without comments, strings and white space. */
function tokenize(text: string): Token[] {
    const tokens: Token[] = [];
    let line = 0;
    let lineStart = 0;
    let i = 0;
    while (i < text.length) {
        const c = text[i];
        if (c === '\n') {
            line++;
            lineStart = ++i;
        } else if (c === '-' && text[i + 1] === '-') {
            while (i < text.length && text[i] !== '\n') i++;
        } else if (c === '"') {
            i++;
            while (i < text.length && text[i] !== '"' && text[i] !== '\n') {
                if (text[i] === '\\' && text[i + 1] === '\n') {
                    line++; // as in the grammar, an escaped line break continues the string
                    lineStart = i + 2;
                }
                i += text[i] === '\\' ? 2 : 1;
            }
            if (text[i] === '"') i++;
        } else if (WORD_START.test(c) || (c === '@' && WORD_START.test(text[i + 1] ?? ''))) {
            // `@pre`, `@severity` and `@message` are one token, as in the grammar's lexer
            const start = i;
            i++;
            while (i < text.length && WORD_PART.test(text[i])) i++;
            tokens.push({ text: text.slice(start, i), line, column: start - lineStart, offset: start });
        } else if (/\s/.test(c)) {
            i++;
        } else {
            tokens.push({ text: c, line, column: i - lineStart, offset: i });
            i++;
        }
    }
    return tokens;
}

const BLOCK_KINDS: Record<string, ConstraintUnitInfo['kind']> = { inv: 'INV', pre: 'PRE', post: 'POST' };

/** A block keyword and the block's name, if it has one. */
interface BlockStart {
    keyword: Token;
    name: string | null;
}

/** Whether the token at `index` is a single `:`, not part of `::`. */
function isSingleColon(tokens: Token[], index: number): boolean {
    const token = tokens[index];
    if (token?.text !== ':') return false;
    const before = tokens[index - 1];
    const after = tokens[index + 1];
    const joinedBefore = before?.text === ':' && before.offset === token.offset - 1;
    const joinedAfter = after?.text === ':' && after.offset === token.offset + 1;
    return !joinedBefore && !joinedAfter;
}

/**
 * The block that starts at `index`. As in the language's splitter, every `inv`, `pre` and `post`
 * starts a block, also one still being typed; the word after it is the block's name if `:` or `(`
 * follows that word.
 */
function blockAt(tokens: Token[], index: number): BlockStart | undefined {
    const keyword = tokens[index];
    if (!(keyword.text in BLOCK_KINDS)) return undefined;
    const candidate = tokens[index + 1];
    const named = candidate !== undefined
        && WORD_START.test(candidate.text[0])
        && (isSingleColon(tokens, index + 2) || tokens[index + 2]?.text === '(');
    return { keyword, name: named ? candidate.text : null };
}

/** The non-blank lines of `text.slice(from, to)`, comments removed, with their line numbers. */
function contentLines(text: string, from: number, to: number, firstLine: number): Array<{ text: string; line: number }> {
    return text.slice(from, to).split('\n')
        .map((content, i) => ({ text: stripComment(content).replace(/\s+$/, ''), line: firstLine + i }))
        .filter(entry => entry.text.trim() !== '');
}

/** The line without its `--` comment; `--` inside a string does not start one. */
export function stripComment(line: string): string {
    let inString = false;
    for (let i = 0; i < line.length; i++) {
        if (line[i] === '\\' && inString) {
            i++;
        } else if (line[i] === '"') {
            inString = !inString;
        } else if (!inString && line[i] === '-' && line[i + 1] === '-') {
            return line.slice(0, i);
        }
    }
    return line;
}

/** The code of a line: without its comment, and with every string emptied to `""`. */
function codeOf(line: string): string {
    return stripComment(line).replace(/"(?:[^"\\]|\\.)*("|$)/g, '""');
}

/**
 * The constraint units of the text, like the language server splits them: one per `inv`, `pre`
 * and `post` block (and per context without blocks), each with its `context` declaration, without
 * blank and comment lines. Text before the first `context` (imports) is ignored.
 */
export function unitsFromText(text: string): ConstraintUnitInfo[] {
    const tokens = tokenize(text);
    const units: ConstraintUnitInfo[] = [];
    let i = 0;
    while (i < tokens.length) {
        if (tokens[i].text !== 'context') {
            i++;
            continue;
        }
        const context = tokens[i];
        let end = i + 1;
        while (end < tokens.length && tokens[end].text !== 'context') end++;
        const contextEnd = end < tokens.length ? tokens[end].offset : text.length;

        const blocks: BlockStart[] = [];
        for (let j = i + 1; j < end; j++) {
            const block = blockAt(tokens, j);
            if (block) blocks.push(block);
        }
        const headerEnd = blocks.length > 0 ? blocks[0].keyword.offset : contextEnd;
        const header = contentLines(text, context.offset, headerEnd, context.line);

        if (blocks.length === 0) {
            units.push({
                kind: 'EMPTY_CONTEXT', name: null,
                text: header.map(entry => entry.text.trim()).join('\n'),
                textLines: header.map(entry => entry.line + 1),
                line: context.line + 1, column: context.column,
            });
        }
        blocks.forEach((block, b) => {
            const blockEnd = b + 1 < blocks.length ? blocks[b + 1].keyword.offset : contextEnd;
            const body = contentLines(text, block.keyword.offset, blockEnd, block.keyword.line);
            const lines = [...header.map(entry => ({ ...entry, text: entry.text.trim() })), ...body];
            units.push({
                kind: BLOCK_KINDS[block.keyword.text], name: block.name,
                text: lines.map(entry => entry.text).join('\n'),
                textLines: lines.map(entry => entry.line + 1),
                line: block.keyword.line + 1, column: block.keyword.column,
            });
        });
        i = end;
    }
    return units;
}

/**
 * Matches a block header in the code of a line: `inv name:`, `pre:`, `post name(...) :`; not the
 * `pre` of `x@pre`.
 */
const BLOCK_HEADER = /(?<![@.\w])(inv|pre|post)\b(\s+\w+)?\s*(\(.*\))?\s*:(?!:)/;

/**
 * Whether `line` is in the annotation zone of an `inv`/`pre`/`post` header: every non-blank line
 * between it and the header above is an `@severity` or `@message` annotation or a comment, so no
 * OCL body has begun yet.
 *
 * @param lineAt the text of a line of the document, by its index
 */
export function isInAnnotationZone(lineAt: (line: number) => string, line: number): boolean {
    for (let i = line - 1; i >= 0; i--) {
        const lineText = codeOf(lineAt(i));
        if (lineText.trim() === '') continue; // blank and comment lines are fine
        if (BLOCK_HEADER.test(lineText)) {
            return true; // reached the inv/pre/post header — we're in the zone
        }
        if (/^\s*@(severity|message)\b/.test(lineText)) continue; // other annotation — ok
        return false; // anything else means we're in the OCL body
    }
    return false;
}

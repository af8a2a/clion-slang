package dev.slang.intellij.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A dependency-free lexer intended for fast editor highlighting.
 *
 * <p>It deliberately does not try to parse Slang. Context-sensitive constructs are reduced to
 * conservative lexical heuristics so the plugin can work without C/C++ or generated PSI.</p>
 */
public final class SlangLexer extends LexerBase {
    private static final int DEFAULT_STATE = 0;
    private static final int IN_BLOCK_COMMENT_STATE = 1;
    private static final int IN_DOC_COMMENT_STATE = 2;
    private static final int IN_RAW_STRING_STATE = 3;

    private static final Set<String> KEYWORDS = words(
            // Slang declarations and type system.
            "associatedtype", "attribute_syntax", "class", "concept", "constructor", "deinit", "dyn", "enum",
            "extension", "extern", "func", "generic", "implementing", "implements", "import",
            "init", "interface", "internal", "let", "module", "namespace", "new", "operator", "package",
            "private", "property", "protected", "public", "require", "specialize", "struct",
            "subscript", "this", "type_param", "typealias", "typedef", "using", "var", "where", "witness",
            "$for", "__exported", "__generic", "__include", "__interface", "__target_switch", "spirv_asm",

            // Control flow and compile-time control flow.
            "break", "case", "catch", "continue", "default", "defer", "discard", "do", "else",
            "expand", "each", "for", "if", "return", "switch", "throw", "try", "while",
            "compiletime", "compileTime", "static_assert",

            // Storage, access, interpolation and differentiability modifiers.
            "const", "constexpr", "differentiable", "export", "final", "forceinline", "get",
            "globallycoherent", "groupshared", "in", "inline", "inout", "instance", "mutating",
            "no_diff", "nonmutating", "out", "override", "precise", "ref", "sealed", "set",
            "shared", "static", "uniform", "virtual", "volatile", "row_major", "column_major",
            "snorm", "unorm", "centroid", "linear", "nointerpolation", "noperspective", "sample",

            // HLSL declarations and legacy effect syntax accepted by Slang front ends.
            "asm", "asm_fragment", "cbuffer", "tbuffer", "register", "packoffset", "pass",
            "technique", "technique10", "technique11",

            // Geometry/tessellation and ray tracing modifiers.
            "point", "line", "triangle", "lineadj", "triangleadj", "patch", "payload",
            "callabledata", "callabledataext", "raypayload", "raypayloadext",

            // Reserved literal-like words.
            "null", "nullptr", "none", "undefined"
    );

    private static final Set<String> BUILTIN_TYPES = words(
            "void", "bool", "int", "uint", "dword", "half", "float", "double",
            "int8_t", "uint8_t", "int16_t", "uint16_t", "int32_t", "uint32_t", "int64_t", "uint64_t",
            "intptr_t", "uintptr_t", "min10float", "min16float", "min12int", "min16int", "min16uint",
            "vector", "matrix", "string", "String",

            "SamplerState", "SamplerComparisonState", "sampler", "sampler_state",
            "Sampler1D", "Sampler2D", "Sampler3D", "SamplerCUBE",
            "Texture1D", "Texture1DArray", "Texture2D", "Texture2DArray", "Texture2DMS",
            "Texture2DMSArray", "Texture3D", "TextureCube", "TextureCubeArray",
            "RWTexture1D", "RWTexture1DArray", "RWTexture2D", "RWTexture2DArray", "RWTexture3D",
            "RasterizerOrderedTexture1D", "RasterizerOrderedTexture1DArray",
            "RasterizerOrderedTexture2D", "RasterizerOrderedTexture2DArray", "RasterizerOrderedTexture3D",
            "Buffer", "RWBuffer", "StructuredBuffer", "RWStructuredBuffer",
            "AppendStructuredBuffer", "ConsumeStructuredBuffer", "ByteAddressBuffer", "RWByteAddressBuffer",
            "RasterizerOrderedBuffer", "RasterizerOrderedByteAddressBuffer",
            "RasterizerOrderedStructuredBuffer", "ConstantBuffer", "ParameterBlock",
            "InputPatch", "OutputPatch", "PointStream", "LineStream", "TriangleStream",
            "RaytracingAccelerationStructure", "RayDesc", "BuiltInTriangleIntersectionAttributes",
            "HitObject", "HitObjectAttributes", "DifferentialPair", "Atomic"
    );

    private static final Set<String> ATTRIBUTE_NAMES = words(
            "allow_uav_condition", "backward_differentiable", "branch", "call", "domain",
            "earlydepthstencil", "entrypoint", "fastopt", "flatten", "forcecase", "instance",
            "loop", "max_tess_factor", "maxtessfactor", "numthreads", "outputcontrolpoints",
            "outputtopology", "partitioning", "patchconstantfunc", "shader", "shader_record",
            "unroll", "vk", "binding", "location", "push_constant", "wave_size",
            "differentiable", "prefer_recompute", "primal_substitute", "derivative_group_linear",
            "derivative_group_quads", "cuda", "spirv", "glsl", "nvapi"
    );

    private static final Set<String> SEMANTICS = words(
            "BINORMAL", "BLENDINDICES", "BLENDWEIGHT", "COLOR", "DEPTH", "FOG", "NORMAL",
            "POSITION", "POSITIONT", "PSIZE", "TANGENT", "TESSFACTOR", "TEXCOORD", "VFACE", "VPOS"
    );

    private static final Pattern NUMERIC_BUILTIN_TYPE = Pattern.compile(
            "(?:bool|int|uint|dword|half|float|double|min10float|min16float|min12int|min16int|min16uint|" +
                    "int8_t|uint8_t|int16_t|uint16_t|int32_t|uint32_t|int64_t|uint64_t)" +
                    "(?:[1-4](?:x[1-4])?)?"
    );

    private static final String[] MULTI_CHARACTER_OPERATORS = {
            ">>>=", "<<=", ">>=", "&&=", "||=", "^^=", "->*", "...",
            "++", "--", "->", "=>", "::", "==", "!=", "<=", ">=", "&&", "||",
            "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "??", "?.", "**", "##"
    };

    private CharSequence buffer = "";
    private int bufferEnd;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;
    private int state;
    private int nextState;
    private String rawDelimiter = "";

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.state = isKnownState(initialState) ? initialState : DEFAULT_STATE;
        this.nextState = this.state;
        if (this.state == IN_RAW_STRING_STATE) {
            String recoveredDelimiter = recoverRawDelimiter(startOffset);
            this.rawDelimiter = recoveredDelimiter == null ? "" : recoveredDelimiter;
        } else {
            this.rawDelimiter = "";
        }
        locateToken();
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        state = nextState;
        locateToken();
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }

    private void locateToken() {
        if (tokenStart >= bufferEnd) {
            tokenEnd = tokenStart;
            tokenType = null;
            return;
        }

        nextState = state;
        if (state == IN_BLOCK_COMMENT_STATE || state == IN_DOC_COMMENT_STATE) {
            boolean doc = state == IN_DOC_COMMENT_STATE;
            scanBlockCommentChunk(tokenStart, doc);
            tokenType = doc ? SlangTokenTypes.DOC_COMMENT : SlangTokenTypes.BLOCK_COMMENT;
            return;
        }
        if (state == IN_RAW_STRING_STATE) {
            scanRawStringChunk(tokenStart);
            tokenType = SlangTokenTypes.STRING_LITERAL;
            return;
        }

        char c = charAt(tokenStart);

        if (Character.isWhitespace(c)) {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd && Character.isWhitespace(charAt(tokenEnd))) {
                tokenEnd++;
            }
            tokenType = SlangTokenTypes.WHITE_SPACE;
            return;
        }

        if (c == '#' && isDirectiveStart(tokenStart)) {
            scanPreprocessorDirective();
            return;
        }

        if (c == '/' && tokenStart + 1 < bufferEnd) {
            char next = charAt(tokenStart + 1);
            if (next == '/') {
                boolean doc = tokenStart + 2 < bufferEnd
                        && (charAt(tokenStart + 2) == '/' || charAt(tokenStart + 2) == '!');
                tokenEnd = tokenStart + 2;
                while (tokenEnd < bufferEnd && !isLineBreak(charAt(tokenEnd))) {
                    tokenEnd++;
                }
                tokenType = doc ? SlangTokenTypes.DOC_COMMENT : SlangTokenTypes.LINE_COMMENT;
                return;
            }
            if (next == '*') {
                boolean doc = tokenStart + 2 < bufferEnd
                        && (charAt(tokenStart + 2) == '*' || charAt(tokenStart + 2) == '!');
                scanBlockCommentChunk(tokenStart + 2, doc);
                tokenType = doc ? SlangTokenTypes.DOC_COMMENT : SlangTokenTypes.BLOCK_COMMENT;
                return;
            }
        }

        if (c == 'R' && tokenStart + 1 < bufferEnd && charAt(tokenStart + 1) == '"'
                && tryScanRawString()) {
            tokenType = SlangTokenTypes.STRING_LITERAL;
            return;
        }

        if (c == '"' || c == '\'') {
            scanQuotedLiteral(c);
            tokenType = c == '"' ? SlangTokenTypes.STRING_LITERAL : SlangTokenTypes.CHARACTER_LITERAL;
            return;
        }

        if (Character.isDigit(c) || (c == '.' && tokenStart + 1 < bufferEnd
                && Character.isDigit(charAt(tokenStart + 1)))) {
            scanNumber();
            tokenType = SlangTokenTypes.NUMBER_LITERAL;
            return;
        }

        if (isIdentifierStart(c)) {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < bufferEnd && isIdentifierPart(charAt(tokenEnd))) {
                tokenEnd++;
            }
            tokenType = classifyIdentifier(buffer.subSequence(tokenStart, tokenEnd).toString());
            return;
        }

        IElementType punctuation = punctuationType(c);
        if (punctuation != null && !startsMultiCharacterOperator()) {
            tokenEnd = tokenStart + 1;
            tokenType = punctuation;
            return;
        }

        int operatorLength = operatorLength();
        if (operatorLength > 0) {
            tokenEnd = tokenStart + operatorLength;
            tokenType = SlangTokenTypes.OPERATOR;
            return;
        }

        tokenEnd = tokenStart + 1;
        tokenType = SlangTokenTypes.BAD_CHARACTER;
    }

    private void scanPreprocessorDirective() {
        tokenEnd = tokenStart + 1;
        while (tokenEnd < bufferEnd) {
            char c = charAt(tokenEnd);
            if (!isLineBreak(c)) {
                tokenEnd++;
                continue;
            }

            int previous = tokenEnd - 1;
            if (c == '\n' && previous >= tokenStart && charAt(previous) == '\r') {
                previous--;
            }
            if (previous < tokenStart || charAt(previous) != '\\') {
                break;
            }

            if (c == '\r' && tokenEnd + 1 < bufferEnd && charAt(tokenEnd + 1) == '\n') {
                tokenEnd += 2;
            } else {
                tokenEnd++;
            }
        }
        tokenType = SlangTokenTypes.PREPROCESSOR;
    }

    private void scanBlockCommentChunk(int contentStart, boolean doc) {
        tokenEnd = contentStart;
        while (tokenEnd < bufferEnd) {
            char c = charAt(tokenEnd);
            if (c == '*' && tokenEnd + 1 < bufferEnd && charAt(tokenEnd + 1) == '/') {
                tokenEnd += 2;
                nextState = DEFAULT_STATE;
                return;
            }
            if (isLineBreak(c)) {
                consumeLineBreak();
                nextState = doc ? IN_DOC_COMMENT_STATE : IN_BLOCK_COMMENT_STATE;
                return;
            }
            tokenEnd++;
        }
        nextState = doc ? IN_DOC_COMMENT_STATE : IN_BLOCK_COMMENT_STATE;
    }

    private boolean tryScanRawString() {
        String delimiter = parseRawDelimiter(tokenStart);
        if (delimiter == null) {
            return false;
        }
        rawDelimiter = delimiter;
        int contentStart = tokenStart + 3 + delimiter.length();
        scanRawStringChunk(contentStart);
        return true;
    }

    private void scanRawStringChunk(int contentStart) {
        tokenEnd = contentStart;
        while (tokenEnd < bufferEnd) {
            char c = charAt(tokenEnd);
            if (c == ')' && isRawStringTerminator(tokenEnd, rawDelimiter)) {
                tokenEnd += rawDelimiter.length() + 2;
                nextState = DEFAULT_STATE;
                rawDelimiter = "";
                return;
            }
            if (isLineBreak(c)) {
                consumeLineBreak();
                nextState = IN_RAW_STRING_STATE;
                return;
            }
            tokenEnd++;
        }
        nextState = IN_RAW_STRING_STATE;
    }

    /** Returns the delimiter from {@code R"delimiter(}, or null if this is not a valid prefix. */
    private @Nullable String parseRawDelimiter(int rawStart) {
        if (rawStart < 0 || rawStart + 2 >= bufferEnd
                || charAt(rawStart) != 'R' || charAt(rawStart + 1) != '"') {
            return null;
        }
        int delimiterStart = rawStart + 2;
        int i = delimiterStart;
        while (i < bufferEnd && i - delimiterStart <= 16) {
            char c = charAt(i);
            if (c == '(') {
                return buffer.subSequence(delimiterStart, i).toString();
            }
            if (Character.isWhitespace(c) || c == ')' || c == '\\' || Character.isISOControl(c)) {
                return null;
            }
            i++;
        }
        return null;
    }

    private boolean isRawStringTerminator(int closingParenthesis, String delimiter) {
        int quote = closingParenthesis + delimiter.length() + 1;
        if (quote >= bufferEnd || charAt(quote) != '"') {
            return false;
        }
        for (int i = 0; i < delimiter.length(); i++) {
            if (charAt(closingParenthesis + 1 + i) != delimiter.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reconstructs raw-string context if a lexer client explicitly resumes with the raw-string
     * state. The editor normally rewinds to the zero-state opening token, but honoring the passed
     * state also makes direct and boundary-focused lexer use deterministic.
     */
    private @Nullable String recoverRawDelimiter(int offset) {
        int lowerBound = Math.max(0, offset - 65_536);
        for (int i = offset - 1; i >= lowerBound; i--) {
            if (charAt(i) != 'R' || i + 1 >= bufferEnd || charAt(i + 1) != '"') {
                continue;
            }
            String delimiter = parseRawDelimiter(i);
            if (delimiter == null) {
                continue;
            }
            int contentStart = i + 3 + delimiter.length();
            if (!hasRawTerminatorBefore(contentStart, delimiter, offset)) {
                return delimiter;
            }
        }
        return null;
    }

    private boolean hasRawTerminatorBefore(int contentStart, String delimiter, int limit) {
        int max = Math.min(limit, bufferEnd);
        for (int i = contentStart; i < max; i++) {
            if (charAt(i) == ')' && isRawStringTerminator(i, delimiter)
                    && i + delimiter.length() + 2 <= max) {
                return true;
            }
        }
        return false;
    }

    private void consumeLineBreak() {
        char first = charAt(tokenEnd++);
        if (first == '\r' && tokenEnd < bufferEnd && charAt(tokenEnd) == '\n') {
            tokenEnd++;
        }
    }

    private void scanQuotedLiteral(char quote) {
        tokenEnd = tokenStart + 1;
        boolean escaped = false;
        while (tokenEnd < bufferEnd) {
            char c = charAt(tokenEnd);
            if (escaped) {
                escaped = false;
                tokenEnd++;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                tokenEnd++;
                continue;
            }
            if (c == quote) {
                tokenEnd++;
                return;
            }
            if (isLineBreak(c)) {
                return;
            }
            tokenEnd++;
        }
    }

    private void scanNumber() {
        int i = tokenStart;

        if (charAt(i) == '.') {
            i++;
            i = consumeDigits(i, 10);
            i = consumeDecimalExponent(i);
            tokenEnd = consumeNumberSuffix(i);
            return;
        }

        if (i + 1 < bufferEnd && charAt(i) == '0') {
            char prefix = charAt(i + 1);
            if (prefix == 'x' || prefix == 'X') {
                i = consumeDigits(i + 2, 16);
                if (i < bufferEnd && charAt(i) == '.') {
                    i = consumeDigits(i + 1, 16);
                }
                if (i < bufferEnd && (charAt(i) == 'p' || charAt(i) == 'P')) {
                    i++;
                    if (i < bufferEnd && (charAt(i) == '+' || charAt(i) == '-')) {
                        i++;
                    }
                    i = consumeDigits(i, 10);
                }
                tokenEnd = consumeNumberSuffix(i);
                return;
            }
            if (prefix == 'b' || prefix == 'B') {
                i = consumeDigits(i + 2, 2);
                tokenEnd = consumeNumberSuffix(i);
                return;
            }
        }

        i = consumeDigits(i, 10);
        if (i < bufferEnd && charAt(i) == '.' && !(i + 1 < bufferEnd && charAt(i + 1) == '.')) {
            i = consumeDigits(i + 1, 10);
        }
        i = consumeDecimalExponent(i);
        tokenEnd = consumeNumberSuffix(i);
    }

    private int consumeDecimalExponent(int offset) {
        int i = offset;
        if (i < bufferEnd && (charAt(i) == 'e' || charAt(i) == 'E')) {
            i++;
            if (i < bufferEnd && (charAt(i) == '+' || charAt(i) == '-')) {
                i++;
            }
            i = consumeDigits(i, 10);
        }
        return i;
    }

    private int consumeDigits(int offset, int radix) {
        int i = offset;
        while (i < bufferEnd) {
            char c = charAt(i);
            if (c == '_' || Character.digit(c, radix) >= 0) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private int consumeNumberSuffix(int offset) {
        int i = offset;
        while (i < bufferEnd) {
            char c = charAt(i);
            if (c == 'u' || c == 'U' || c == 'l' || c == 'L' || c == 'f' || c == 'F'
                    || c == 'h' || c == 'H') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private IElementType classifyIdentifier(String text) {
        if ("true".equals(text) || "false".equals(text)) {
            return SlangTokenTypes.BOOLEAN_LITERAL;
        }
        if (isLikelyAttributeIdentifier(tokenStart)) {
            return SlangTokenTypes.ATTRIBUTE;
        }
        if (BUILTIN_TYPES.contains(text) || NUMERIC_BUILTIN_TYPE.matcher(text).matches()) {
            return SlangTokenTypes.TYPE_KEYWORD;
        }
        if (KEYWORDS.contains(text)) {
            return SlangTokenTypes.KEYWORD;
        }
        if (isSemantic(text)) {
            return SlangTokenTypes.SEMANTIC;
        }
        return SlangTokenTypes.IDENTIFIER;
    }

    private boolean isSemantic(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.startsWith("SV_") || upper.startsWith("NV_")) {
            return true;
        }
        int previous = previousNonWhitespace(tokenStart - 1);
        if (previous < 0 || charAt(previous) != ':'
                || (previous > 0 && charAt(previous - 1) == ':')) {
            return false;
        }
        int end = upper.length();
        while (end > 0 && Character.isDigit(upper.charAt(end - 1))) {
            end--;
        }
        return SEMANTICS.contains(upper.substring(0, end));
    }

    /**
     * Recognizes declaration attributes without treating ordinary array subscripts as attributes.
     * Attribute names are identified before their argument list; argument identifiers remain normal.
     */
    private boolean isLikelyAttributeIdentifier(int identifierStart) {
        int openBracket = findUnmatchedOpeningBracket(identifierStart - 1);
        if (openBracket < 0) {
            return false;
        }

        int beforeBracket = previousNonWhitespace(openBracket - 1);
        if (beforeBracket >= 0) {
            char before = charAt(beforeBracket);
            if (isIdentifierPart(before) || Character.isDigit(before) || before == ')') {
                return false;
            }
        }

        int parentheses = 0;
        for (int i = openBracket + 1; i < identifierStart; i++) {
            char c = charAt(i);
            if (c == '(') {
                parentheses++;
            } else if (c == ')' && parentheses > 0) {
                parentheses--;
            }
        }
        if (parentheses != 0) {
            return false;
        }

        String name = buffer.subSequence(identifierStart, tokenEnd).toString().toLowerCase(Locale.ROOT);
        if (ATTRIBUTE_NAMES.contains(name)) {
            return true;
        }

        int previous = previousNonWhitespace(identifierStart - 1);
        return previous == openBracket || (previous >= 1 && charAt(previous) == ':' && charAt(previous - 1) == ':')
                || (previous >= 0 && charAt(previous) == ',');
    }

    private int findUnmatchedOpeningBracket(int offset) {
        int nestedClosings = 0;
        int lowerBound = Math.max(0, offset - 2048);
        for (int i = offset; i >= lowerBound; i--) {
            char c = charAt(i);
            if (c == ']') {
                nestedClosings++;
            } else if (c == '[') {
                if (nestedClosings == 0) {
                    return i;
                }
                nestedClosings--;
            } else if (nestedClosings == 0 && (c == ';' || c == '{' || c == '}')) {
                return -1;
            }
        }
        return -1;
    }

    private int previousNonWhitespace(int offset) {
        int i = offset;
        while (i >= 0 && Character.isWhitespace(charAt(i))) {
            i--;
        }
        return i;
    }

    private boolean isDirectiveStart(int offset) {
        for (int i = offset - 1; i >= 0; i--) {
            char c = charAt(i);
            if (isLineBreak(c)) {
                return true;
            }
            if (c != ' ' && c != '\t' && c != '\f') {
                return false;
            }
        }
        return true;
    }

    private boolean startsMultiCharacterOperator() {
        for (String operator : MULTI_CHARACTER_OPERATORS) {
            if (operator.length() > 1 && startsWith(tokenStart, operator)) {
                return true;
            }
        }
        return false;
    }

    private int operatorLength() {
        for (String operator : MULTI_CHARACTER_OPERATORS) {
            if (startsWith(tokenStart, operator)) {
                return operator.length();
            }
        }
        return isSingleCharacterOperator(charAt(tokenStart)) ? 1 : 0;
    }

    private boolean startsWith(int offset, String text) {
        if (offset + text.length() > bufferEnd) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (charAt(offset + i) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable IElementType punctuationType(char c) {
        return switch (c) {
            case '{' -> SlangTokenTypes.LBRACE;
            case '}' -> SlangTokenTypes.RBRACE;
            case '[' -> SlangTokenTypes.LBRACKET;
            case ']' -> SlangTokenTypes.RBRACKET;
            case '(' -> SlangTokenTypes.LPAREN;
            case ')' -> SlangTokenTypes.RPAREN;
            case ';' -> SlangTokenTypes.SEMICOLON;
            case ',' -> SlangTokenTypes.COMMA;
            case '.' -> SlangTokenTypes.DOT;
            case ':' -> SlangTokenTypes.COLON;
            default -> null;
        };
    }

    private static boolean isSingleCharacterOperator(char c) {
        return switch (c) {
            case '+', '-', '*', '/', '%', '=', '!', '~', '&', '|', '^', '<', '>', '?', '@', '#' -> true;
            default -> false;
        };
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static boolean isLineBreak(char c) {
        return c == '\r' || c == '\n';
    }

    private static boolean isKnownState(int value) {
        return value >= DEFAULT_STATE && value <= IN_RAW_STRING_STATE;
    }

    private char charAt(int offset) {
        return buffer.charAt(offset);
    }

    private static Set<String> words(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}

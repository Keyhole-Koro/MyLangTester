import java.util.ArrayList;
import java.util.List;

/**
 * The little of MyLang's lexical rules that mytest needs: where comments and
 * string and character literals begin and end. Everything else is code. The
 * compiler remains responsible for the full syntax.
 */
final class SourceScanner {
    private SourceScanner() {
    }

    /**
     * Skip the comment or the string or character literal that starts at
     * `index`, returning the index just past it (the end of the source if it
     * is unclosed). Return `index` itself when code starts there.
     */
    static int skipNonCode(String source, int index) {
        if (source.startsWith("//", index)) {
            int newline = source.indexOf('\n', index + 2);
            return newline < 0 ? source.length() : newline + 1;
        }
        if (source.startsWith("/*", index)) {
            int end = source.indexOf("*/", index + 2);
            return end < 0 ? source.length() : end + 2;
        }
        char quote = source.charAt(index);
        if (quote != '"' && quote != '\'') return index;
        int i = index + 1;
        while (i < source.length()) {
            if (source.charAt(i) == '\\') {
                i += 2;
                continue;
            }
            if (source.charAt(i++) == quote) break;
        }
        return Math.min(i, source.length());
    }

    /** The first code character at or after `index`, or the end of the source. */
    static int nextCode(String source, int index) {
        int i = index;
        while (i < source.length()) {
            int next = skipNonCode(source, i);
            if (next == i) return i;
            i = next;
        }
        return source.length();
    }

    /**
     * Split code into tokens: an identifier is one token and every other
     * character outside whitespace is a token of its own. Comments and
     * literals produce none, so text in them can never look like code.
     */
    static List<String> codeTokens(String source) {
        List<String> tokens = new ArrayList<>();
        int i = nextCode(source, 0);
        while (i < source.length()) {
            char ch = source.charAt(i);
            if (isIdentifierStart(ch)) {
                int start = i++;
                while (i < source.length() && isIdentifierPart(source.charAt(i))) i++;
                tokens.add(source.substring(start, i));
            } else {
                if (!Character.isWhitespace(ch)) tokens.add(String.valueOf(ch));
                i++;
            }
            i = nextCode(source, i);
        }
        return tokens;
    }

    static boolean isIdentifierStart(char ch) {
        return ch == '_' || Character.isAlphabetic(ch);
    }

    static boolean isIdentifierPart(char ch) {
        return isIdentifierStart(ch) || Character.isDigit(ch);
    }
}

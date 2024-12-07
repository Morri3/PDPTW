import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AADS {
    protected static class JSONException extends RuntimeException {
        private static final long serialVersionUID = 0; // Serialization ID

        public JSONException(final String message) {
            super(message);
        }

        public JSONException(final String message, final Throwable cause) {
            super(message, cause);
        }

        public JSONException(final Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    protected interface JSONString {
        // Allows a class to change the behavior of JSONObject.toString(), JSONArray.toString(), and JSONWriter.value(Object).
        public String toJSONString();
    }

    /**
     * Allow the parsing of JSON source strings, called in the JSONObject and JSONArray constructors.
     */
    protected static class JSONTokener {
        private long character; // current read character position on the current line当前行的当前读字符的位置
        private boolean eof; // flag to indicate if the end of the input has been found是否是输入末尾
        private long index; // current read index of the input输入的当前读的下标
        private long line; // current line of the input输入的当前行
        private char previous; // previous character read from the input从输入读取的上一个字符
        private final Reader reader; // Reader for the input
        private boolean usePrevious; // flag to indicate that a previous character was requested是否已经请求上一个字符
        private long characterPreviousLine; // the number of characters read in the previous line上一行读取的字符总数

        /**
         * Construct a JSONTokener from a Reader. The caller must close the Reader.
         */
        public JSONTokener(Reader reader) {
            this.reader = reader.markSupported() ? reader : new BufferedReader(reader);
            this.character = 1;
            this.eof = false;
            this.index = 0;
            this.line = 1;
            this.previous = 0;
            this.usePrevious = false;
            this.characterPreviousLine = 0;
        }

        public JSONTokener(String s) {
            this(new StringReader(s));
        }

        /* Get the next character in the source string. */
        public char next() throws JSONException {
            int c;
            // If the character has been requested, restore the flag and get the character requested last time
            if (this.usePrevious) {
                this.usePrevious = false;
                c = this.previous;
            } else {
                try {
                    c = this.reader.read(); // read a single character from the stream
                } catch (IOException exception) {
                    throw new JSONException(exception);
                }
            }

            // End of stream
            if (c <= 0) {
                this.eof = true;
                return 0;
            }

            this.incrementIndexes(c); // update indexes
            this.previous = (char) c; // update the 'previous' character
            return this.previous; // return current character
        }

        /* Get the next n characters. */
        public String next(int n) throws JSONException {
            if (n == 0) return "";

            char[] chars = new char[n];
            int pos = 0;

            // iterate over n characters
            while (pos < n) {
                chars[pos] = this.next();
                if (this.end()) {
                    throw new JSONException("Substring bounds error");
                }
                pos++;
            }
            return new String(chars); // transfer n characters into a string
        }

        /* Get the next char in the string, skipping whitespace. */
        public char nextClean() throws JSONException {
            while (true) {
                char c = this.next(); // read next character
                // Read to the end of the text/read the character after the space in the ASCII code table, and return the character
                if (c == 0 || c > ' ') {
                    return c;
                }
            }
        }

        /* Get the next value (Boolean, Double, Integer, JSONArray, JSONObject, Long, String, JSONObject.NULL object) */
        public Object nextValue() throws JSONException {
            char c = this.nextClean();
            // Determines whether there is a deeper object/array start symbol. If so, throw an exception.
            switch (c) {
                case '{':
                    this.back();
                    try {
                        return new JSONObject(this);
                    } catch (StackOverflowError e) {
                        throw new JSONException("JSON Object depth too large to process.", e);
                    }
                case '[':
                    this.back();
                    try {
                        return new JSONArray(this);
                    } catch (StackOverflowError e) {
                        throw new JSONException("JSON Array depth too large to process.", e);
                    }
            }
            return nextSimpleValue(c); // return next value
        }

        /* Increments the internal indexes according to the previous character
         * read and the character passed as the current character.
         * TODO  根据 读取的前一个字符 和 作为当前字符传递的字符 增加内部索引 */
        private void incrementIndexes(int c) {
            if (c > 0) {
                this.index++;
                if (c == '\r') { // Enter(read the next line, the current character is used as the previous character for the next read, and the character is restored
                    this.line++;
                    this.characterPreviousLine = this.character;
                    this.character = 0;
                } else if (c == '\n') {
                    if (this.previous != '\r') { // not Enter
                        this.line++;
                        this.characterPreviousLine = this.character;
                    }
                    this.character = 0; // restore the character
                } else {
                    this.character++; // read next character in the same line
                }
            }
        }

        /* Decrements the indexes based on the previous character read. */
        private void decrementIndexes() {
            this.index--;
            if (this.previous == '\r' || this.previous == '\n') { // read Enter/ \n
                // return to the places of Enter /  \n
                this.line--;
                this.character = this.characterPreviousLine;
            } else if (this.character > 0) {
                // back one character
                this.character--;
            }
        }

        public Object nextSimpleValue(char c) {
            String string;
            // we should read strings if the character c is a quotation mark
            switch (c) {
                case '"': // double quotes
                case '\'': // single quotes
                    return this.nextString(c);
            }

            // put boolean value, null value and number into the StringBuilder, until reaching EOF
            StringBuilder sb = new StringBuilder();
            while (c >= ' ' && ",:]}/\\\"[{;=#".indexOf(c) < 0) {
                /*
                    , comma     : colon     ] right bracket     } right curly bracket
                    / left slash        \\ right slash      \" double quote     [ left bracket
                    { left curly bracket        ; semicolon     = equal sign        # pound sign
                */
                sb.append(c);
                c = this.next();
            }
            // if reaching here without reading the EOF, we should back a character
            if (!this.eof) this.back();

            string = sb.toString().trim(); // convert to a string, removing the spaces
            if ("".equals(string)) throw new JSONException("Missing value");
            return JSONObject.stringToValue(string);
        }

        /* Return the characters up to the next close quote character.
         * Backslash processing is done. The formal JSON format does not allow strings in single quotes,
         * but an implementation is allowed to accept them.
         * TODO  返回到下一个关闭的引号字符为止 读取的字符
         *  已完成反斜杠处理
         *  标准JSON不允许单引号中的字符串，但允许实现接受它们 */
        public String nextString(char quote) throws JSONException {
            char c;
            StringBuilder sb = new StringBuilder();

            while (true) {
                c = this.next(); // read next character
                switch (c) {
                    case 0: // end of the text
                    case '\n':
                    case '\r': // Enter
                        throw new JSONException("Unterminated string. Character with int code "
                                + (int) c + " is not allowed within a quoted string.");
                    case '\\': // right slash
                        c = this.next();
                        switch (c) {
                            case 'b': // backspace
                                sb.append('\b');
                                break;
                            case 't': // Tab
                                sb.append('\t');
                                break;
                            case 'n': // new line
                                sb.append('\n');
                                break;
                            case 'f': // page break
                                sb.append('\f');
                                break;
                            case 'r': // Enter
                                sb.append('\r');
                                break;
                            case 'u': // unicode character in hexadecimal
                                String next = this.next(4);
                                try {
                                    sb.append((char) Integer.parseInt(next, 16)); // hexadecimal to binary
                                } catch (NumberFormatException e) {
                                    throw new JSONException("Illegal escape. \\u must be followed by a 4 digit hexadecimal number. \\"
                                            + next + " is not valid.", e);
                                }
                                break;
                            case '"':
                            case '\'':
                            case '\\':
                            case '/':
                                sb.append(c);
                                break;
                            default:
                                throw new JSONException("Illegal escape. Escape sequence  \\" + c + " is not valid.");
                        }
                        break;
                    default:
                        if (c == quote) { // represents the end of a string
                            return sb.toString();
                        }
                        sb.append(c); // add into the StringBuilder
                }
            }
        }

        /* Back up one character.
         * TODO  可在尝试解析下一个数字或标识符之前测试数字或字母 */
        public void back() throws JSONException {
            // backtrack at most one character. If it is already at the start of the string, no backtracking is required.
            if (this.usePrevious || this.index <= 0)
                throw new JSONException("Stepping back two steps is not supported");

            this.decrementIndexes();
            this.usePrevious = true;
            this.eof = false;
        }

        /* Checks if reaching the end of the input. */
        public boolean end() {
            return this.eof && !this.usePrevious;
        }

        public void close() throws IOException {
            if (reader != null) reader.close();
        }

        @Override
        public String toString() {
            return " at " + this.index + " [character " + this.character + " line " + this.line + "]";
        }
    }

    /**
     * Configuration base object (immutable) for parsers.
     */
    @SuppressWarnings({""}) // Suppress compiler warnings
    protected static class ParserConfiguration {
        /* Used to indicate no limit to the maximum nesting depth when parsing a document. */
        public static final int UNDEFINED_MAXIMUM_NESTING_DEPTH = -1;
        /* The default maximum nesting depth when parsing a document. */
        public static final int DEFAULT_MAXIMUM_NESTING_DEPTH = 512;
        /* Specifies if values should be kept as strings (true), or if they should try to be guessed into JSON values (numeric, boolean, string). */
        protected boolean keepStrings;
        /* The maximum nesting depth when parsing an object. */
        protected int maxNestingDepth;

        public ParserConfiguration() {
            this.keepStrings = false;
            this.maxNestingDepth = DEFAULT_MAXIMUM_NESTING_DEPTH;
        }

        /* Constructs a new ParserConfiguration with the specified settings. */
        protected ParserConfiguration(final boolean keepStrings, final int maxNestingDepth) {
            this.keepStrings = keepStrings;
            this.maxNestingDepth = maxNestingDepth;
        }

        /* Provides a new instance of the same configuration ("deep" clone). */
        @Override
        protected ParserConfiguration clone() {
            return new ParserConfiguration(this.keepStrings, this.maxNestingDepth);
        }

        /* Defines the maximum nesting depth that the parser will descend before throwing an exception
         * when parsing an object (e.g. Map, Collection) into JSON-related objects.
         * The default max nesting depth is 512. The parser will throw a JsonException if reaching the maximum depth.
         * Using any negative value as a parameter is equivalent to setting no limit to the nesting depth.
         * The parses will go as deep as the maximum call stack size allows. */
        @SuppressWarnings("unchecked")
        public <T extends ParserConfiguration> T withMaxNestingDepth(int maxNestingDepth) {
            T newConfig = (T) this.clone();
            if (maxNestingDepth > UNDEFINED_MAXIMUM_NESTING_DEPTH) {
                newConfig.maxNestingDepth = maxNestingDepth;
            } else {
                newConfig.maxNestingDepth = UNDEFINED_MAXIMUM_NESTING_DEPTH;
            }
            return newConfig;
        }
    }

    /**
     * Configuration object (immutable) for the JSON parser.
     */
    protected static class JSONParserConfiguration extends ParserConfiguration {
        private boolean overwriteDuplicateKey; // 是否覆盖key

        public JSONParserConfiguration() {
            super();
            this.overwriteDuplicateKey = false;
        }

        @Override
        protected JSONParserConfiguration clone() {
            JSONParserConfiguration clone = new JSONParserConfiguration();
            clone.overwriteDuplicateKey = overwriteDuplicateKey;
            clone.maxNestingDepth = maxNestingDepth;
            return clone;
        }

        @SuppressWarnings("unchecked")
        @Override
        public JSONParserConfiguration withMaxNestingDepth(final int maxNestingDepth) {
            JSONParserConfiguration clone = this.clone();
            clone.maxNestingDepth = maxNestingDepth;
            return clone;
        }

        /* Controls the parser's behavior when meeting duplicate keys.
         * If set to false, the parser will throw a JSONException when meeting a duplicate key.
         * Or the duplicate key's value will be overwritten. */
        public JSONParserConfiguration withOverwriteDuplicateKey(final boolean overwriteDuplicateKey) {
            JSONParserConfiguration clone = this.clone();
            clone.overwriteDuplicateKey = overwriteDuplicateKey;
            return clone;
        }

        /* when meeting duplicated keys, controlling the parser to overwrite */
        public boolean isOverwriteDuplicateKey() {
            return this.overwriteDuplicateKey;
        }
    }

    /**
     * Implement the writer interface method to implement StringBuilder data reading and writing
     */
    protected static class StringBuilderWriter extends Writer {
        private final StringBuilder builder;

        public StringBuilderWriter() {
            builder = new StringBuilder();
            lock = builder;
        }

        public StringBuilderWriter(int initialSize) {
            builder = new StringBuilder(initialSize);
            lock = builder;
        }

        @Override
        public void write(int c) {
            builder.append((char) c);
        }

        @Override
        public void write(char[] cbuf, int offset, int length) {
            if ((offset < 0) || (offset > cbuf.length) || (length < 0) ||
                    ((offset + length) > cbuf.length) || ((offset + length) < 0)) {
                throw new IndexOutOfBoundsException();
            } else if (length == 0) {
                return;
            }
            builder.append(cbuf, offset, length);
        }

        @Override
        public void write(String str) {
            builder.append(str);
        }

        @Override
        public void write(String str, int offset, int length) {
            builder.append(str, offset, offset + length);
        }

        @Override
        public StringBuilderWriter append(CharSequence csq) {
            write(String.valueOf(csq));
            return this;
        }

        @Override
        public StringBuilderWriter append(CharSequence csq, int start, int end) {
            if (csq == null) csq = "null";
            return append(csq.subSequence(start, end));
        }

        @Override
        public StringBuilderWriter append(char c) {
            write(c);
            return this;
        }

        @Override
        public String toString() {
            return builder.toString();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws IOException {
        }
    }

    protected static class JSONObject {
        private Map<String, Object> map;

        /* 1) JSONObject.NULL is equivalent to the null value in JavaScript.
         * 2) Java's null is equivalent to the undefined value in JavaScript. */
        private static final class Null {
            /* There is only a single instance of the NULL object. */
            @Override
            protected final Object clone() {
                return this;
            }

            /* A Null object is equal to the null value and to itself. */
            @Override
            public boolean equals(Object object) {
                return object == null || object == this;
            }

            /* Returns a hash code value for the object. A Null object is equal to the null value and to itself. */
            @Override
            public int hashCode() {
                return 0;
            }

            @Override
            public String toString() {
                return "null";
            }
        }

        public static final Object NULL = new Null();

        // Regular Expression Pattern that matches JSON Numbers
        public static final Pattern NUMBER_PATTERN = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

        /* Check whether the object is a NaN or infinite number. */
        public static void verify(Object o) throws JSONException {
            if (o instanceof Number && !IsFiniteNumber((Number) o))
                throw new JSONException("JSON doesn't allow non-finite numbers.");
        }

        private static boolean IsFiniteNumber(Number n) {
            if (n instanceof Double && (((Double) n).isInfinite() || ((Double) n).isNaN())) { // invalid Double type
                return false;
            } else if (n instanceof Float && (((Float) n).isInfinite() || ((Float) n).isNaN())) { // invalid Float type
                return false;
            }
            return true;
        }

        public JSONObject() {
            this.map = new HashMap<String, Object>();
        }

        protected JSONObject(int initialCapacity) {
            this.map = new HashMap<String, Object>(initialCapacity);
        }

        /* Construct a JSONObject from a source JSON text string (The mostly used constructor). */
        protected JSONObject(String source) throws JSONException {
            this(new JSONTokener(source));
        }

        /* Construct a JSONObject from a JSONTokener. */
        public JSONObject(JSONTokener x) throws JSONException {
            this(x, new JSONParserConfiguration());
        }

        /* Construct a JSONObject from a JSONTokener with custom json parse configurations. */
        public JSONObject(JSONTokener x, JSONParserConfiguration jsonParserConfiguration) throws JSONException {
            this();
            char c;
            String key;

            // If the first read character is {, meaning that it is the start of an object, otherwise throw an exception
            if (x.nextClean() != '{') throw new JSONException("A JSONObject text must start with '{'.");

            while (true) {
                c = x.nextClean(); // read next character
                switch (c) {
                    case 0: // read the end of the text
                        throw new JSONException("A JSONObject text must end with '}'.");
                    case '}': // the reading procedure is finished
                        return;
                    default: // get the key
                        key = x.nextSimpleValue(c).toString();
                }

                // key and value are separated by colon
                c = x.nextClean();
                if (c != ':') throw new JSONException("Expected a ':' after a key.");

                if (key != null) {
                    boolean keyExists = this.opt(key) != null;
                    // If there exists duplicated keys, throw an exception
                    if (keyExists && !jsonParserConfiguration.isOverwriteDuplicateKey())
                        throw new JSONException("\"" + key + "\" is a duplicate key.");

                    // If the next value is not null, then add it into the map
                    Object value = x.nextValue();
                    if (value != null) {
                        this.put(key, value);
                    }
                }

                // Key-value pairs are separated by commas
                switch (x.nextClean()) {
                    case ';':
                    case ',':
                        // Finish construction
                        if (x.nextClean() == '}') {
                            return;
                        }
                        // a JSONObject should use {} to wrap
                        if (x.end()) {
                            throw new JSONException("A JSONObject text must end with '}'.");
                        }
                        // We should go back one character and parse new key-value pairs
                        x.back();
                        break;
                    case '}': // Finish construction
                        return;
                    default:
                        throw new JSONException("Expected a ',' or '}'.");
                }
            }
        }

        /* Get an optional value associated with a key. */
        public Object opt(String key) {
            if (key == null) {
                return null;
            }
            return this.map.get(key);
        }

        /* Get an optional JSONArray associated with a key.
         * It returns null if there is no such key, or if its value is not a JSONArray. */
        public JSONArray optJSONArray(String key) {
            return this.optJSONArray(key, null);
        }

        public JSONArray optJSONArray(String key, JSONArray defaultValue) {
            Object object = this.opt(key);
            return object instanceof JSONArray ? (JSONArray) object : defaultValue;
        }

        /* Get an optional JSONObject associated with a key.
         * It returns null if there is no such key, or if its value is not a JSONObject. */
        public JSONObject optJSONObject(String key) {
            return this.optJSONObject(key, null);
        }

        public JSONObject optJSONObject(String key, JSONObject defaultValue) {
            Object object = this.opt(key);
            return object instanceof JSONObject ? (JSONObject) object : defaultValue;
        }

        /* Put a key/value pair in the JSONObject.
         * If the value is null, then the key will be removed from the JSONObject if it is present. */
        public JSONObject put(String key, Object value) throws JSONException {
            if (key == null) throw new NullPointerException("Null key.");
            if (value != null) {
                verify(value); // verify the value
                // using different map.put method according to the type of value
                if (value instanceof Boolean) { // 1.Boolean
                    this.map.put(key, (Boolean) value ? Boolean.TRUE : Boolean.FALSE);
                } else if (value instanceof Double) { // 2.Double
                    this.map.put(key, (Double) value);
                } else if (value instanceof Float) { // 3.Float
                    this.map.put(key, (Float) value);
                } else if (value instanceof Integer) { // 4.Integer
                    this.map.put(key, (Integer) value);
                } else if (value instanceof Long) { // 5.Long
                    this.map.put(key, (Long) value);
                } else if (value instanceof Collection<?>) {
                } else if (value instanceof Map<?, ?>) {
                } else { // 6.Object
                    this.map.put(key, value);
                }
            } else {
                this.remove(key);
            }
            return this;
        }

        public Object remove(String key) {
            return this.map.remove(key);
        }

        /* Produce a string in double quotes with backslash sequences in all the right places. */
        @SuppressWarnings("resource")
        public static String quote(String string) {
            // "" represents an empty string
            if (string == null || string.isEmpty()) {
                return "\"\"";
            }

            Writer sw = new StringBuilderWriter(string.length() + 2); // add the size of quotes
            try {
                return quote(string, sw).toString();
            } catch (IOException ignored) {
                return "";
            }
        }

        /* Quotes a string and appends the result to a given Writer. */
        public static Writer quote(String string, Writer w) throws IOException {
            // "" represents an empty string
            if (string == null || string.isEmpty()) {
                w.write("\"\"");
                return w;
            }

            char b; // last traversed character
            char c = 0; // current traversing character
            String hStr;
            int i;
            int len = string.length();

            w.write('"'); // write the left double quote

            // iterating over a string
            for (i = 0; i < len; i++) {
                b = c;
                c = string.charAt(i);
                switch (c) {
                    case '\\':
                    case '"':
                        w.write('\\');
                        w.write(c);
                        break;
                    case '/':
                        if (b == '<') { // avoid being considered the start of an HTML/XML tag
                            w.write('\\');
                        }
                        w.write(c);
                        break;
                    case '\b':
                        w.write("\\b");
                        break;
                    case '\t':
                        w.write("\\t");
                        break;
                    case '\n':
                        w.write("\\n");
                        break;
                    case '\f':
                        w.write("\\f");
                        break;
                    case '\r':
                        w.write("\\r");
                        break;
                    default:
                        if (c < ' ' || (c >= '\u0080' && c < '\u00a0') || (c >= '\u2000' && c < '\u2100')) { // characters after space in ASCII code table / Unicode characters
                            w.write("\\u");
                            hStr = Integer.toHexString(c); // convert to hexadecimal string
                            w.write("0000", 0, 4 - hStr.length()); // write (4 - hStr.length()) digit 0
                            w.write(hStr); // write characters rather than digit 0
                        } else { // ordinary characters
                            w.write(c);
                        }
                }
            }
            // write the right double quote
            w.write('"');
            return w;
        }

        public int length() {
            return this.map.size();
        }

        protected Set<Entry<String, Object>> entrySet() {
            return this.map.entrySet();
        }

        /* Convert a string into a number, boolean, or null. If the string can't be converted, return the string. */
        public static Object stringToValue(String string) {
            if ("".equals(string)) {
                return string;
            }
            if ("true".equalsIgnoreCase(string)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(string)) {
                return Boolean.FALSE;
            }
            if ("null".equalsIgnoreCase(string)) {
                return JSONObject.NULL;
            }

            // Convert String to a number.
            char initial = string.charAt(0);
            if ((initial >= '0' && initial <= '9') || initial == '-') {
                try {
                    return stringToNumber(string);
                } catch (Exception ignore) {
                }
            }
            return string;
        }

        /* Check whether the val is a decimal. */
        protected static boolean isDecimalNotation(final String val) {
            return val.indexOf('.') > -1 || val.indexOf('e') > -1 || val.indexOf('E') > -1 || "-0".equals(val);
        }

        /* convert the String to Number */
        protected static Number stringToNumber(final String val) throws NumberFormatException {
            char initial = val.charAt(0);
            if ((initial >= '0' && initial <= '9') || initial == '-') {
                if (isDecimalNotation(val)) { // decimal
                    try {
                        BigDecimal bd = new BigDecimal(val); // keep original representation
                        // Since BigDecimal does not support -0.0, decimals are forced to be used here
                        if (initial == '-' && BigDecimal.ZERO.compareTo(bd) == 0) {
                            return Double.valueOf(-0.0);
                        }
                        return bd;
                    } catch (NumberFormatException retryAsDouble) { // no need to implement in this project
                        System.out.println("Hex Floats related contents are not available now.");
                    }
                }
                // deal with integers
                BigInteger bi = new BigInteger(val);
                if (bi.bitLength() <= 31) {
                    return Integer.valueOf(bi.intValue());
                }
                if (bi.bitLength() <= 63) {
                    return Long.valueOf(bi.longValue());
                }
                return bi;
            }
            throw new NumberFormatException("Value [" + val + "] is not a valid number.");
        }

        /* Produce a string from a Number. */
        public static String numberToString(Number number) throws JSONException {
            if (number == null) throw new JSONException("Null pointer.");
            verify(number);

            String string = number.toString();
            if (string.indexOf('.') > 0 && string.indexOf('e') < 0 && string.indexOf('E') < 0) {
                while (string.endsWith("0")) { // remove trailing zeros
                    string = string.substring(0, string.length() - 1);
                }
                if (string.endsWith(".")) { // remove decimal point
                    string = string.substring(0, string.length() - 1);
                }
            }
            return string;
        }

        /* If { indentFactor > 0 } and the JSONObject has only one key, the object will be output on a single line
         * If an object has 2 or more keys, it will be output across multiple lines */
        @Override
        public String toString() {
            try {
                return this.toString(0);
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("resource")
        public String toString(int indentFactor) throws JSONException {
            // 6 characters are the minimum to serialise a key value pair
            int initialSize = map.size() * 6;
            Writer w = new StringBuilderWriter(Math.max(initialSize, 16));
            return this.write(w, indentFactor, 0).toString();
        }

        /* Returns a java.util.Map containing all of the entries in this object. */
        public Map<String, Object> toMap() {
            Map<String, Object> results = new HashMap<String, Object>();
            for (Entry<String, Object> entry : this.entrySet()) {
                Object value;
                if (entry.getValue() == null || NULL.equals(entry.getValue())) {
                    value = null;
                } else if (entry.getValue() instanceof JSONObject) {
                    value = ((JSONObject) entry.getValue()).toMap();
                } else if (entry.getValue() instanceof JSONArray) {
                    value = ((JSONArray) entry.getValue()).toList();
                } else {
                    value = entry.getValue();
                }
                results.put(entry.getKey(), value);
            }
            return results;
        }

        /* add indents */
        protected static void indent(Writer writer, int indent) throws IOException {
            for (int i = 0; i < indent; i++) {
                writer.write(' ');
            }
        }

        @SuppressWarnings("resource")
        public Writer write(Writer writer, int indentFactor, int indent) throws JSONException {
            try {
                boolean needsComma = false;
                final int length = this.length(); // length of the map

                writer.write('{');
                if (length == 1) {
                    final Entry<String, ?> entry = this.entrySet().iterator().next();
                    final String key = entry.getKey();
                    writer.write(quote(key)); // convert key to a double-quoted string and write
                    writer.write(':'); // : used to separate the key and value
                    if (indentFactor > 0) { // write intents
                        writer.write(' ');
                    }
                    try { // write values
                        writeValue(writer, entry.getValue(), indentFactor, indent);
                    } catch (Exception e) {
                        throw new JSONException("Unable to write JSONObject value for key: " + key, e);
                    }
                } else if (length != 0) { // display as multiple lines
                    final int newIndent = indent + indentFactor;
                    for (final Entry<String, ?> entry : this.entrySet()) {
                        if (needsComma) {
                            writer.write(',');
                        }
                        if (indentFactor > 0) { // start a new line and write intents
                            writer.write('\n');
                        }
                        indent(writer, newIndent);
                        final String key = entry.getKey();
                        writer.write(quote(key));
                        writer.write(':');
                        if (indentFactor > 0) {
                            writer.write(' ');
                        }
                        try {
                            writeValue(writer, entry.getValue(), indentFactor, newIndent);
                        } catch (Exception e) {
                            throw new JSONException("Unable to write JSONObject value for key: " + key, e);
                        }
                        needsComma = true; // every entry should have a comma at the end
                    }
                    if (indentFactor > 0) {
                        writer.write('\n');
                    }
                    indent(writer, indent);
                }
                writer.write('}'); // end
                return writer;
            } catch (IOException exception) {
                throw new JSONException(exception);
            }
        }

        @SuppressWarnings("resource")
        protected static Writer writeValue(Writer writer, Object value, int indentFactor, int indent) throws JSONException, IOException {
            if (value == null || value.equals(null)) {
                writer.write("null");
            } else if (value instanceof JSONString) {
                Object o;
                try {
                    o = ((JSONString) value).toJSONString();
                } catch (Exception e) {
                    throw new JSONException(e);
                }
                writer.write(o != null ? o.toString() : quote(value.toString()));
            } else if (value instanceof String) { // The mostly used branch
                quote(value.toString(), writer);
                return writer;
            } else if (value instanceof Number) {
                final String numberAsString = numberToString((Number) value);
                if (NUMBER_PATTERN.matcher(numberAsString).matches()) { // is a Number
                    writer.write(numberAsString);
                } else { // convert to a double-quoted string
                    quote(numberAsString, writer);
                }
            } else if (value instanceof Boolean) {
                writer.write(value.toString());
            } else if (value instanceof JSONObject) {
                ((JSONObject) value).write(writer, indentFactor, indent);
            } else { // other types, converting to strings
                quote(value.toString(), writer);
            }
            return writer;
        }
    }

    protected static class JSONArray implements Iterable<Object> {
        private final ArrayList<Object> myArrayList;

        public JSONArray() {
            this.myArrayList = new ArrayList<Object>();
        }

        public JSONArray(int initialCapacity) throws JSONException {
            if (initialCapacity < 0) throw new JSONException("JSONArray initial capacity cannot be negative.");
            this.myArrayList = new ArrayList<Object>(initialCapacity);
        }

        public JSONArray(JSONTokener x) throws JSONException {
            this();
            // the first character must be [
            if (x.nextClean() != '[') {
                throw new JSONException("A JSONArray text must start with '['.");
            }
            char nextChar = x.nextClean(); // read next character
            if (nextChar == 0) { // the end of the text is not ], then throw an exception
                throw new JSONException("Expected a ',' or ']'.");
            }
            if (nextChar != ']') {
                x.back(); // go back a character
                while (true) {
                    if (x.nextClean() == ',') { // meaning the array is empty
                        x.back();
                        this.myArrayList.add(JSONObject.NULL);
                    } else { // add next value
                        x.back();
                        this.myArrayList.add(x.nextValue());
                    }
                    // deal with the end
                    switch (x.nextClean()) {
                        case 0: // the end of the text is not ], then throw an exception
                            throw new JSONException("Expected a ',' or ']'.");
                        case ',':
                            nextChar = x.nextClean();
                            if (nextChar == 0) { // the end of the text is not ], then throw an exception
                                throw new JSONException("Expected a ',' or ']'.");
                            }
                            if (nextChar == ']') { // finish construction
                                return;
                            }
                            x.back();
                            break;
                        case ']': // finish construction
                            return;
                        default:
                            throw new JSONException("Expected a ',' or ']'.");
                    }
                }
            }
        }

        /* Construct a JSONArray from a source JSON text (The mostly used constructor). */
        public JSONArray(String source) throws JSONException {
            this(new JSONTokener(source));
        }

        /* Construct a JSONArray from another JSONArray (shallow copy). */
        public JSONArray(JSONArray array) {
            if (array == null) {
                this.myArrayList = new ArrayList<Object>();
            } else {
                this.myArrayList = new ArrayList<Object>(array.myArrayList);
            }
        }

        @Override
        public Iterator<Object> iterator() {
            return this.myArrayList.iterator();
        }

        public int length() {
            return this.myArrayList.size();
        }

        public void clear() {
            this.myArrayList.clear();
        }

        /* Get the optional object value associated with an index. */
        public Object opt(int index) {
            if (index < 0 || index >= this.length()) {
                return null;
            } else {
                return this.myArrayList.get(index);
            }
        }

        /* Append an object value. This increases the array's length by one. */
        public JSONArray put(Object value) {
            JSONObject.verify(value);
            // using different arrayList.add method according to the type of value
            if (value instanceof Boolean) { // 1.Boolean
                this.myArrayList.add((Boolean) value ? Boolean.TRUE : Boolean.FALSE);
            } else if (value instanceof Double) { // 2.Double
                this.myArrayList.add((Double) value);
            } else if (value instanceof Float) { // 3.Float
                this.myArrayList.add((Float) value);
            } else if (value instanceof Integer) { // 4.Integer
                this.myArrayList.add((Integer) value);
            } else if (value instanceof Long) { // 5.Long
                this.myArrayList.add((Long) value);
            } else if (value instanceof Collection<?>) {
            } else if (value instanceof Map<?, ?>) {
            } else { // 6.Object
                this.myArrayList.add(value);
            }
            return this;
        }

        /* Put or replace an object value in the JSONArray. */
        public JSONArray put(int index, Object value) throws JSONException {
            if (index < 0) throw new JSONException("JSONArray[" + index + "] not found.");
            // it is within the array index range
            if (index < this.length()) {
                JSONObject.verify(value);
                this.myArrayList.set(index, value);
                return this;
            }
            // if it is the last position, call put() to add a new element
            if (index == this.length()) {
                return this.put(value);
            }
            // if we are inserting past the length, we want to grow the array all at once instead of incrementally
            this.myArrayList.ensureCapacity(index + 1); // 指定容量+1
            // fulfil the remaining empty positions with JSONObject.NULL object
            while (index != this.length()) {
                this.myArrayList.add(JSONObject.NULL);
            }
            // otherwise, put value
            return this.put(value);
        }

        public Object remove(int index) {
            if (index >= 0 && index < this.length()) {
                return this.myArrayList.remove(index);
            } else {
                return null;
            }
        }

        /* Put a JSONArray's elements in to the JSONArray (shallow copy). */
        public JSONArray putAll(JSONArray array) {
            this.myArrayList.addAll(array.myArrayList);
            return this;
        }

        /* Returns a java.util.List containing all of the elements in this array.
         * If an element in the array is a JSONArray or JSONObject it will also be converted to a List and a Map respectively.
         * Warning: This method assumes that the data structure is acyclical. */
        public List<Object> toList() {
            List<Object> result = new ArrayList<Object>(this.myArrayList.size());
            for (Object element : this.myArrayList) {
                if (element == null || JSONObject.NULL.equals(element)) { // 1.NULL
                    result.add(null);
                } else if (element instanceof JSONArray) { // 2.JSONArray
                    result.add(((JSONArray) element).toList());
                } else if (element instanceof JSONObject) { // 3.JSONObject
                    result.add(((JSONObject) element).toMap());
                } else { // 4.other types
                    result.add(element);
                }
            }
            return result;
        }

        public boolean isEmpty() {
            return this.myArrayList.isEmpty();
        }

        /* Get the string associated with an index. */
        public String getString(int index) throws JSONException {
            Object object = this.opt(index);
            if (object instanceof String) {
                return (String) object;
            }
            throw new JSONException(index + " String " + object + " " + null + ".");
        }

        /* Produce a JSONObject by combining a JSONArray of names with the values of this JSONArray. */
        public JSONObject toJSONObject(JSONArray names) throws JSONException {
            if (names == null || names.isEmpty() || this.isEmpty()) return null;

            JSONObject jo = new JSONObject(names.length());
            for (int i = 0; i < names.length(); i++) {
                jo.put(names.getString(i), this.opt(i));
            }
            return jo;
        }

        /* If { indentFactor > 0 } and the JSONArray has only one element, the array will be output on a single line
         * If an array has 2 or more elements, then it will be output across multiple lines */
        @Override
        public String toString() {
            try {
                return this.toString(0);
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("resource")
        public String toString(int indentFactor) throws JSONException {
            int initialSize = myArrayList.size() * 2; // each value requires a comma
            Writer sw = new StringBuilderWriter(Math.max(initialSize, 16));
            return this.write(sw, indentFactor, 0).toString();
        }

        /* Write the contents of the JSONArray as JSON text to a writer. No whitespace is added. */
        public Writer write(Writer writer) throws JSONException {
            return this.write(writer, 0, 0);
        }

        @SuppressWarnings("resource")
        public Writer write(Writer writer, int indentFactor, int indent) throws JSONException {
            try {
                boolean needsComma = false;
                int length = this.length(); // the length of map
                // the start of an JSONArray
                writer.write('[');
                if (length == 1) {
                    try {
                        JSONObject.writeValue(writer, this.myArrayList.get(0), indentFactor, indent);
                    } catch (Exception e) {
                        throw new JSONException("Unable to write JSONArray value at index: 0", e);
                    }
                } else if (length != 0) {
                    // display as multiple lines
                    final int newIndent = indent + indentFactor;
                    for (int i = 0; i < length; i++) {
                        if (needsComma) {
                            writer.write(',');
                        }
                        // start a new line and write indents
                        if (indentFactor > 0) {
                            writer.write('\n');
                        }
                        JSONObject.indent(writer, newIndent);
                        // write values
                        try {
                            JSONObject.writeValue(writer, this.myArrayList.get(i),
                                    indentFactor, newIndent);
                        } catch (Exception e) {
                            throw new JSONException("Unable to write JSONArray value at index: " + i, e);
                        }
                        needsComma = true; // every element should have a comma at the end
                    }
                    if (indentFactor > 0) {
                        writer.write('\n');
                    }
                    JSONObject.indent(writer, indent);
                }
                writer.write(']'); // the end of the JSONArray
                return writer;
            } catch (IOException e) {
                throw new JSONException(e);
            }
        }
    }

    protected interface Tuple<A, B> {
        A getFirst();

        B getSecond();

        String toString();
    }

    protected interface Edge<E> {
        E getElement();
    }

    protected interface Vertex<V> {
        V getElement();
    }

    protected interface Graph<V, E> {
        int numVertices();

        int numEdges();

        Iterable<Vertex<V>> vertices();

        Iterable<Tuple<Edge<E>, List<Vertex<V>>>> edges();

        int outDegree(Vertex<V> v) throws IllegalArgumentException;

        int inDegree(Vertex<V> v) throws IllegalArgumentException;

        Iterable<Edge<E>> outgoingEdges(Vertex<V> v) throws IllegalArgumentException;

        Iterable<Edge<E>> incomingEdges(Vertex<V> v) throws IllegalArgumentException;

        Edge<E> getEdge(Vertex<V> u, Vertex<V> v) throws IllegalArgumentException;

        /* Returns the vertices of edge e as an array of length two.
         * If the graph is directed, the first vertex is the origin, and the second is the destination.
         * If the graph is undirected, the order is arbitrary. */
        Vertex<V>[] endVertices(Edge<E> e) throws IllegalArgumentException;

        /* Returns the vertex that is opposite vertex v on edge e. */
        Vertex<V> opposite(Vertex<V> v, Edge<E> e) throws IllegalArgumentException;

        Vertex<V> insertVertex(V element);

        Edge<E> insertEdge(Vertex<V> u, Vertex<V> v, E element) throws IllegalArgumentException;

        void removeVertex(Vertex<V> v) throws IllegalArgumentException;

        void removeEdge(Edge<E> e) throws IllegalArgumentException;
    }

    protected static class InnerTuple<A, B> implements Tuple<A, B> {
        private A first;
        private B second;

        public InnerTuple(A newFirst, B newSecond) {
            first = newFirst;
            second = newSecond;
        }

        public A getFirst() {
            return first;
        }

        public B getSecond() {
            return second;
        }

        @Override
        public String toString() {
            return "Tuple{ " +
                    "first=" + first +
                    ", second=" + second +
                    " }";
        }
    }

    protected interface Position<E> {
        /* Returns the element stored at this position. */
        E getElement() throws IllegalStateException;
    }

    protected interface PositionalList<E> extends Iterable<E> {
        int size();

        boolean isEmpty();

        Position<E> first();

        Position<E> last();

        /* return the position before position p */
        Position<E> before(Position<E> p) throws IllegalArgumentException;

        /* return the position after position p */
        Position<E> after(Position<E> p) throws IllegalArgumentException;

        Position<E> addFirst(E e);

        Position<E> addLast(E e);

        Position<E> addBefore(Position<E> p, E e) throws IllegalArgumentException;

        Position<E> addAfter(Position<E> p, E e) throws IllegalArgumentException;

        E get(Position<E> p) throws IllegalArgumentException;

        E set(Position<E> p, E e) throws IllegalArgumentException;

        E remove(Position<E> p) throws IllegalArgumentException;

        /* return an iterator over the elements stored in the list */
        Iterator<E> iterator();

        /* return the position of the returned list from the first to the last in an iterable form */
        Iterable<Position<E>> positions();
    }

    /**
     * doubly Linked List
     */
    protected static class LinkedPositionalList<E> implements PositionalList<E> {
        private static class Node<E> implements Position<E> {
            private E element;
            private Node<E> prev;
            private Node<E> next;

            public Node(E e, Node<E> p, Node<E> n) {
                element = e;
                prev = p;
                next = n;
            }

            public E getElement() throws IllegalStateException {
                if (next == null) throw new IllegalStateException("Position no longer valid");
                return element;
            }

            public Node<E> getPrev() {
                return prev;
            }

            public Node<E> getNext() {
                return next;
            }

            public void setElement(E e) {
                element = e;
            }

            public void setPrev(Node<E> p) {
                prev = p;
            }

            public void setNext(Node<E> n) {
                next = n;
            }
        }

        private Node<E> header;

        private Node<E> trailer;

        private int size = 0; // number of elements in the list (except the head and tail nodes)

        public LinkedPositionalList() {
            header = new Node<>(null, null, null);
            trailer = new Node<>(null, header, null);
            header.setNext(trailer); // header -> trailer
        }

        /* verify whether a position belongs to the corresponding category and cannot be deleted */
        private Node<E> validate(Position<E> p) throws IllegalArgumentException {
            if (!(p instanceof Node)) throw new IllegalArgumentException("Invalid p");
            Node<E> node = (Node<E>) p;
            if (node.getNext() == null) throw new IllegalArgumentException("p is no longer in the list");
            return node;
        }

        /* return the given node as a position. If it is a sentinel, return null. */
        private Position<E> position(Node<E> node) {
            if (node == header || node == trailer) return null;
            return node;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public Position<E> first() {
            return position(header.getNext());
        }

        @Override
        public Position<E> last() {
            return position(trailer.getPrev());
        }

        @Override
        public Position<E> before(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return position(node.getPrev());
        }

        @Override
        public Position<E> after(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return position(node.getNext());
        }

        private Position<E> addBetween(E e, Node<E> pred, Node<E> succ) {
            Node<E> newest = new Node<>(e, pred, succ);
            pred.setNext(newest);
            succ.setPrev(newest);
            size++;
            return newest;
        }

        @Override
        public Position<E> addFirst(E e) {
            return addBetween(e, header, header.getNext());
        }

        @Override
        public Position<E> addLast(E e) {
            return addBetween(e, trailer.getPrev(), trailer);
        }

        @Override
        public Position<E> addBefore(Position<E> p, E e) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return addBetween(e, node.getPrev(), node);
        }

        @Override
        public Position<E> addAfter(Position<E> p, E e) throws IllegalArgumentException {
            Node<E> node = validate(p);
            return addBetween(e, node, node.getNext());
        }

        @Override
        public E get(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            E answer = node.getElement();
            return answer;
        }

        @Override
        public E set(Position<E> p, E e) throws IllegalArgumentException {
            Node<E> node = validate(p);
            E answer = node.getElement();
            node.setElement(e);
            return answer;
        }

        @Override
        public E remove(Position<E> p) throws IllegalArgumentException {
            Node<E> node = validate(p);
            Node<E> predecessor = node.getPrev();
            Node<E> successor = node.getNext();
            predecessor.setNext(successor);
            successor.setPrev(predecessor);
            size--;

            E answer = node.getElement();
            node.setElement(null);
            node.setNext(null);
            node.setPrev(null);
            return answer;
        }

        /* return an iterable representation of the list positions
         * each instance contains an implicit reference to the containing list, allowing methods of the list to be called directly */
        private class PositionIterator implements Iterator<Position<E>> {
            private Position<E> cursor = first(); // point to next element to be traversed
            private Position<E> recent = null; // recently traversed element

            public boolean hasNext() {
                return (cursor != null);
            }

            public Position<E> next() throws NoSuchElementException {
                if (cursor == null) throw new NoSuchElementException("nothing left");
                recent = cursor; // go to the next position
                cursor = after(cursor); // point to next position
                return recent;
            }

            public void remove() throws IllegalStateException {
                if (recent == null) throw new IllegalStateException("nothing to remove");
                LinkedPositionalList.this.remove(recent); // remove the position visited recently
                recent = null; // avoid being removed again
            }
        }

        private class PositionIterable implements Iterable<Position<E>> {
            public Iterator<Position<E>> iterator() {
                return new PositionIterator();
            }
        }

        @Override
        public Iterable<Position<E>> positions() {
            return new PositionIterable();
        }

        /* return an iterable representation of the list elements */
        private class ElementIterator implements Iterator<E> {
            Iterator<Position<E>> posIterator = new PositionIterator();

            public boolean hasNext() {
                return posIterator.hasNext();
            }

            public E next() {
                return posIterator.next().getElement();
            } // return element!

            public void remove() {
                posIterator.remove();
            }
        }

        @Override
        public Iterator<E> iterator() {
            return new ElementIterator();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder("(");
            Node<E> walk = header.getNext();
            while (walk != trailer) {
                sb.append(walk.getElement());
                walk = walk.getNext();
                if (walk != trailer)
                    sb.append(", ");
            }
            sb.append(")");
            return sb.toString();
        }
    }

    protected static class AdjacencyListGraph<V, E> implements Graph<V, E> {
        private boolean isDirected;
        private PositionalList<Vertex<V>> vertices = new LinkedPositionalList<>();
        private PositionalList<Edge<E>> edges = new LinkedPositionalList<>();
        private PositionalList<Tuple<Edge<E>, List<Vertex<V>>>> edgesWithVertices = new LinkedPositionalList<>();

        protected static class MapEntry<K, V> implements Entry<K, V> {
            private K k; // key
            private V v; // value

            public MapEntry(K key, V value) {
                k = key;
                v = value;
            }

            public K getKey() {
                return k;
            }

            public V getValue() {
                return v;
            }

            public void setKey(K key) {
                k = key;
            }

            public V setValue(V value) {
                V old = v;
                v = value;
                return old;
            }

            public String toString() {
                return "<" + k + ", " + v + ">";
            }
        }

        /**
         * vertex ADT
         */
        private class InnerVertex<V> implements Vertex<V> {
            private V element;
            private Position<Vertex<V>> pos; // the position of vertex in the graph
            private List<Vertex<V>> adjVert; // adjacency list

            public InnerVertex(V elem, boolean graphIsDirected) {
                element = elem;
                adjVert = new ArrayList<>();
            }

            /* verify whether this vertex instance belongs to the given graph */
            public boolean validate(Graph<V, E> graph) {
                return (AdjacencyListGraph.this == graph && pos != null);
            }

            public V getElement() {
                return element;
            }

            /* store the position p of the vertex in the vertex list of the graph */
            public void setPosition(Position<Vertex<V>> p) {
                pos = p;
            }

            public Position<Vertex<V>> getPosition() {
                return pos;
            }

            public List<Vertex<V>> getAdjVert() {
                return adjVert;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Vertex<?> vertex = (Vertex<?>) o;
                return Objects.equals(element, vertex.getElement());
            }
        }

        /**
         * edge ADT
         */
        private class InnerEdge<E> implements Edge<E> {
            private E element;
            private Position<Edge<E>> pos;
            private Vertex<V>[] endpoints;

            @SuppressWarnings({"unchecked"})
            public InnerEdge(Vertex<V> u, Vertex<V> v, E elem) {
                element = elem;
                endpoints = (Vertex<V>[]) new Vertex[]{u, v}; // 存储 边的两个顶点
            }

            public E getElement() {
                return element;
            }

            public Vertex<V>[] getEndpoints() {
                return endpoints;
            }

            /* verify whether this edge instance belongs to the given graph */
            public boolean validate(Graph<V, E> graph) {
                return AdjacencyListGraph.this == graph && pos != null;
            }

            public void setPosition(Position<Edge<E>> p) {
                pos = p;
            }

            public Position<Edge<E>> getPosition() {
                return pos;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Edge<?> edge = (Edge<?>) o;
                return Objects.equals(element, edge.getElement());
            }
        }

        public AdjacencyListGraph(boolean directed) {
            isDirected = directed;
        }

        public int numVertices() {
            return vertices.size();
        }

        public Iterable<Vertex<V>> vertices() {
            return vertices;
        }

        public int numEdges() {
            return edges.size();
        }

        // TODO  reconstruct edge() method, outputting the edge with its end vertices
        public Iterable<Tuple<Edge<E>, List<Vertex<V>>>> edges() {
            for (Edge<E> edge : edges) {
                Vertex<V>[] vert = endVertices(edge);
                List<Vertex<V>> ends = new ArrayList<>();
                ends.add(vert[0]);
                ends.add(vert[1]);
                InnerTuple<Edge<E>, List<Vertex<V>>> innerTuple = new InnerTuple<>(edge, ends);
                edgesWithVertices.addLast(innerTuple);
            }
            return edgesWithVertices;
        }

        public int outDegree(Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> vert = validate(v);
            return vert.getAdjVert().size();
        }

        public Iterable<Edge<E>> outgoingEdges(Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> vert = validate(v);
            List<Vertex<V>> neighbours = vert.getAdjVert(); // get the adjacency list of vertex v
            List<Edge<E>> outgoings = new ArrayList<>();
            for (Vertex<V> neighbour : neighbours) {
                outgoings.add(getEdge(vert, neighbour));
            }
            return outgoings;
        }

        public int inDegree(Vertex<V> v) throws IllegalArgumentException {
            if (!isDirected) {
                return outDegree(v);
            }
            InnerVertex<V> vert = validate(v);
            int cnt = 0;
            for (Edge<E> edge : edges) {
                InnerEdge<E> innerEdge = (InnerEdge<E>) edge;
                Vertex<V>[] endpoints = innerEdge.getEndpoints();
                // TODO  verify whether the endpoint of an edge of a directed graph is the current vertex, if so, update the counter
                if (endpoints[1].equals(vert)) {
                    cnt++;
                }
            }
            return cnt;
        }

        public Iterable<Edge<E>> incomingEdges(Vertex<V> v) throws IllegalArgumentException {
            if (!isDirected) {
                return outgoingEdges(v);
            }

            InnerVertex<V> vert = validate(v);
            List<Edge<E>> incomings = new ArrayList<>();
            // iterate over every edge
            for (Edge<E> edge : edges) {
                InnerEdge<E> innerEdge = (InnerEdge<E>) edge;
                Vertex<V>[] endpoints = innerEdge.getEndpoints();
                // verify whether the endpoint of an edge of a directed graph is the current vertex, if so, add it into the edge list
                // TODO  检查有向图的边的终点是否是当前顶点
                if (endpoints[1].equals(vert)) {
                    incomings.add(innerEdge);
                }
            }
            return incomings;
        }

        public Edge<E> getEdge(Vertex<V> u, Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> origin = validate(u);
            InnerVertex<V> destination = validate(v);

            for (Edge<E> edge : edges) {
                InnerEdge<E> innerEdge = (InnerEdge<E>) edge;
                Vertex<V>[] endpoints = innerEdge.getEndpoints();
                if (isDirected) { // directed graph
                    if (endpoints[0].equals(origin) && endpoints[1].equals(destination)) {
                        return innerEdge;
                    }
                } else { // undirected graph
                    if (endpoints[0].equals(origin) && endpoints[1].equals(destination) || endpoints[0].equals(destination) && endpoints[1].equals(origin)) {
                        return innerEdge;
                    }
                }
            }
            return null; // if there is no edge between u and v, return null
        }

        public Vertex<V>[] endVertices(Edge<E> e) throws IllegalArgumentException {
            InnerEdge<E> edge = validate(e);
            return edge.getEndpoints();
        }

//        public boolean containsVertex(V element) {
//            for (Vertex<V> v : vertices()) {
//                InnerVertex<V> newV = new InnerVertex<>(element, isDirected);
//                System.out.println("01: " + newV + "; " + newV.getElement());
//                System.out.println("02: " + v + "; " + v.getElement());
//                System.out.println("03: " + newV.getElement() == v.getElement());
//                if (newV.getElement().equals(v.getElement())) return true; // 包含指定顶点
//            }
//            return false; // 不包含指定顶点
//        }

        public Vertex<V> insertVertex(V element) {
            InnerVertex<V> v = new InnerVertex<>(element, isDirected);
            v.setPosition(vertices.addLast(v));
            return v;
        }

        public Edge<E> insertEdge(Vertex<V> u, Vertex<V> v, E element) throws IllegalArgumentException {
            if (getEdge(u, v) == null) {
                InnerEdge<E> e = new InnerEdge<>(u, v, element);
                e.setPosition(edges.addLast(e));

                InnerVertex<V> origin = validate(u);
                InnerVertex<V> destination = validate(v);
                // add information into the adjacency list
                origin.getAdjVert().add(destination);
                if (!isDirected) {
                    destination.getAdjVert().add(origin);
                }
                return e;
            } else {
                throw new IllegalArgumentException("Edge from u to v exists");
            }
        }

        @SuppressWarnings({"unchecked"})
        private InnerVertex<V> validate(Vertex<V> v) {
            if (!(v instanceof InnerVertex)) throw new IllegalArgumentException("Invalid vertex");
            InnerVertex<V> vert = (InnerVertex<V>) v;
            if (!vert.validate(this)) throw new IllegalArgumentException("Invalid vertex");
            return vert;
        }

        @SuppressWarnings({"unchecked"})
        private InnerEdge<E> validate(Edge<E> e) {
            if (!(e instanceof InnerEdge)) throw new IllegalArgumentException("Invalid edge");
            InnerEdge<E> edge = (InnerEdge<E>) e;
            if (!edge.validate(this)) throw new IllegalArgumentException("Invalid edge");
            return edge;
        }

        public void removeVertex(Vertex<V> v) throws IllegalArgumentException {
            InnerVertex<V> vert = validate(v);
            // TODO  从图中删除所有关联边
            // remove all related edges from the graph
            if (outgoingEdges(vert) != null) {
                for (Edge<E> e : outgoingEdges(vert)) {
                    if (e != null) {
                        removeEdge(e);
                    }
                }
            }
            if (incomingEdges(vert) != null) {
                for (Edge<E> e : incomingEdges(vert)) {
                    if (e != null) {
                        removeEdge(e);
                    }
                }
            }
            // remove this vertex from the vertices list
            vertices.remove(vert.getPosition());
            vert.setPosition(null);
        }

        @SuppressWarnings({"unchecked"})
        public void removeEdge(Edge<E> e) throws IllegalArgumentException {
            InnerEdge<E> edge = validate(e);
            // get two vertices of the edge
            Vertex<V>[] endpoints = edge.getEndpoints();
            InnerVertex<V> u = validate(endpoints[0]);
            InnerVertex<V> v = validate(endpoints[1]);
            // remove the edge from the adjacency list
            u.getAdjVert().remove(v);
            if (!isDirected) {
                v.getAdjVert().remove(u);
            }
            // remove the edge from the edge list TODO  从边列表中移除边
            edges.remove(edge.getPosition());
            edge.setPosition(null); // invalidate edge object
        }

        /* return the vertex opposite to vertex v on edge e */
        public Vertex<V> opposite(Vertex<V> v, Edge<E> e) throws IllegalArgumentException {
            InnerEdge<E> edge = validate(e);
            Vertex<V>[] endpoints = edge.getEndpoints();
            if (endpoints[0] == v) {
                return endpoints[1];
            } else if (endpoints[1] == v) {
                return endpoints[0];
            } else {
                throw new IllegalArgumentException("v is not incident to this edge");
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Vertex<V> v : vertices) {
                sb.append("Vertex " + v.getElement() + "\n");
                if (isDirected) sb.append(" [outgoing]");
                sb.append(" " + outDegree(v) + " adjacencies:");
                for (Edge<E> e : outgoingEdges(v))
                    sb.append(String.format(" (%s, %s)", opposite(v, e).getElement(), e.getElement()));
                sb.append("\n");
                if (isDirected) {
                    sb.append(" [incoming]");
                    sb.append(" " + inDegree(v) + " adjacencies:");
                    for (Edge<E> e : incomingEdges(v))
                        sb.append(String.format(" (%s, %s)", opposite(v, e).getElement(), e.getElement()));
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }

    /**
     * get the input from the Scanner
     */
    protected static JSONObject getInput() throws IllegalArgumentException {
        // 1. Getting the input
        Scanner input = new Scanner(System.in);
        StringBuilder sb = new StringBuilder();
        while (input.hasNextLine()) {
            String nextLine = input.nextLine().trim(); // ignore the space
            if (nextLine.isEmpty()) break; // stop reading while reaching the empty line
            sb.append(nextLine); // add current line to the StringBuilder
        }
        input.close();

        // 2. Parsing Json String to JsonObject / JsonArray
        JSONObject jsonObject = new JSONObject(sb.toString());
        return jsonObject;
    }

    /**
     * preprocess data
     */
    protected static PreProcessData preProcessData(JSONObject rawData) throws IllegalArgumentException, IllegalAccessException, ParseException {
        // 1. Getting 'InstanceName', without considering 'Configuration'.
        String instanceName = (rawData.opt("InstanceName")).toString();

        // 2. Obtain info about the site(collect site and deliver site)
        JSONObject matrix = rawData.optJSONObject("Matrix");
        // obtain the locations
        JSONArray matrixData = new JSONArray(String.valueOf(matrix.opt("Locations")));
        // store the data after processing TODO   存放处理后的所有地点
        List<Site> locationList = new ArrayList<>();
        // get the distance and time from each site to other sites   TODO   获取每个地点到其他地点的距离\时间
        JSONArray LocationData = new JSONArray(String.valueOf(matrix.opt("Data")));
        JSONArray tmpData = new JSONArray(String.valueOf(LocationData.getString(0))); // 1d-array representing distance time

        for (int i = 0; i < matrixData.length(); i++) {
            String[] coordinates = ((String) matrixData.opt(i)).split(","); // split by comma to get the coordinates
            JSONArray tmp = new JSONArray(String.valueOf(tmpData.getString(i))); // get the distance and time from i-th site
            Site site = new Site(coordinates, tmp); // create a dto
            locationList.add(site);
        }
        System.out.println("location List: " + locationList.toString()); // test
        System.out.println("There are " + locationList.size() + " locations in this input.\n"); // test

        // 3. Obtain info about the vehicles
        JSONArray vehicles = rawData.optJSONArray("Vehicles");
        List<Vehicle> vehicleList = new ArrayList<>();
        for (Object o : vehicles.toList()) { // each item in the list is a HashMap
            Map<String, Object> v;
            if (o instanceof HashMap) {
                v = (Map<String, Object>) o;
            } else {
                throw new JSONException("This is not a HashMap object.");
            }

            // create a dto about vehicles
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            // get vehicleId and weight             TODO  车辆标识码
            List<Map<String, Object>> tmp = (ArrayList) v.get("VehicleCapacity");
            Map<String, Object> capacityMap = tmp.get(0);

            // create Vehicle DTO
            Vehicle dto = new Vehicle(Long.parseLong((String) capacityMap.get("CompartmentId")),
                    Integer.parseInt((String) v.get("StartSite")),
                    sdf.parse((String) v.get("StartTime")),
                    (Integer) capacityMap.get("Weight"),
                    Integer.parseInt((String) v.get("EndSite")),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), Integer.parseInt((String) v.get("StartSite")));
            vehicleList.add(dto);
        }
        System.out.println("Vehicle list: " + vehicleList.toString()); // test
        System.out.println("There are " + vehicleList.size() + " vehicles in this input.\n"); // test

        // 4. Obtain info about the customers
        JSONArray orders = rawData.optJSONArray("Orders");
        List<Customer> customerList = new ArrayList<>();
        if (orders.toList() instanceof ArrayList) { // the type is ArrayList
            for (Object o : orders.toList()) {
                Map<String, Object> mapO = (HashMap) o; // the type of o is HashMap

                // find CollectSite and DeliverSite DTO according to the siteId
                Site collectSite = new Site(), deliverSite = new Site();
                for (Site site : locationList) {
                    if (mapO.get("CollectSiteId").equals(String.valueOf(site.getId()))) {
                        collectSite = site;
                    }
                    if (mapO.get("DeliverSiteId").equals(String.valueOf(site.getId()))) {
                        deliverSite = site;
                    }
                }

                // process CollectTime DTO
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                Date earliestCollect = sdf.parse((String) mapO.get("EarliestCollect1"));
                Date latestCollect = sdf.parse((String) mapO.get("LatestCollect1"));
                long diff = latestCollect.getTime() - earliestCollect.getTime();
                Time collect = new Time(earliestCollect, latestCollect, diff / (1000.0 * 60 * 60),
                        (String) mapO.get("CollectId"), 0, 0, 0);

                // process DeliverTime DTO
                Date earliestDeliver = sdf.parse((String) mapO.get("EarliestDeliver1"));
                Date latestDeliver = sdf.parse((String) mapO.get("LatestDeliver1"));
                diff = latestDeliver.getTime() - earliestDeliver.getTime();
                Time deliver = new Time(earliestDeliver, latestDeliver, diff / (1000.0 * 60 * 60),
                        (String) mapO.get("DeliverId"), 0, 0, 0);

                // create Customer DTO
                Customer customer = new Customer(
                        (String) mapO.get("CollectId"), (String) mapO.get("DeliverId"),
                        collectSite, deliverSite, collect, deliver, // the site and time of collect and deliver  TODO 取送货的地点、时间
                        ((Integer) mapO.get("CollectTimeInMinutes")).longValue(), // time in collect
                        ((Integer) mapO.get("DeliverTimeInMinutes")).longValue(), // time in deliver
                        (Integer) mapO.get("Weight"), // weight
                        false, // default value, no collect at the beginning
                        -1); // assigned route id  TODO  分配到的路线的id
                customerList.add(customer);
            }
        }
        System.out.println("There are " + customerList.size() + " customers in this input."); // test
        System.out.println("Contents in the customerList: \n" + customerList.toString()); // test

        // TODO 放入report，这是测试数据的一个方法
        // 查看CollectTimeWindow、DeliverTimeWindow的时间数据
//        Set<Date> s = new HashSet<>();
//        List<Date> l = new ArrayList<>();
//        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//        for (Customer c : customerList) {
//            s.add(c.getDeliverTimeWindow().end);
//            l.add(c.getDeliverTimeWindow().end);
//        }
//        for (Date d : s) {
//            System.out.println("m: " + sdf.format(d.getTime()));
//        }
//        System.out.println("Set size: " + s.size());
//        System.out.println("List size: " + l.size());

        // 6. create Route DTO
        AdjacencyListGraph<SiteGraph, Integer> graph = new AdjacencyListGraph(true); // graph containing all locations and routes TODO 包含所有地点及路程的图

        // traverse 'locationList' and create all locations (vertex) TODO 遍历locationList,创建所有地点(vertex)
        List<Vertex<SiteGraph>> allVerticesList = new ArrayList<>();
        for (Site site : locationList) {
            SiteGraph siteGraph = new SiteGraph(site.getCoordinates());
            Vertex<SiteGraph> v = graph.insertVertex(siteGraph);
            allVerticesList.add(v);
        }

        // traverse every vertex and create edges TODO 遍历所有顶点,创建边(edge)
        for (Site v : locationList) {
            JSONArray disAndTime = v.getDisAndTime();
            for (int i = 0; i < disAndTime.length(); i++) {
                if (i != v.getId()) {
                    // as long as it is not null, it means the distance/time to other places, then add an edge
                    // TODO 只要不为null,则表示是到其他地点的距离\时间,则添加边
                    JSONArray arr = new JSONArray((String) disAndTime.opt(i));
                    // get the head of a directed arc TODO  获取有向弧的狐头
                    Vertex<SiteGraph> startV = allVerticesList.get((int) v.getId());
                    // get the tail of a directed arc TODO  获取有向弧的狐尾
                    Vertex<SiteGraph> endV = allVerticesList.get(i);
                    // arr.opt(0): distance; arr.opt(1): time
//                    graph.insertEdge(v, endV, (Integer) arr.opt(0));
                    graph.insertEdge(startV, endV, (Integer) arr.opt(1));
                }
            }
        }

        // 查看图的顶点\边信息 // test TODO  放入report
//        System.out.println("After constructing the graph 'graph': \n" + graph);
//        System.out.println("Edges in the graph 'graph': ");
//        // 获取元组，包括边、边的两个顶点
//        for (Tuple<Edge<Integer>, List<Vertex<SiteGraph>>> tuple : graph.edges()) {
//            Edge<Integer> edge = tuple.getFirst();
//            List<Vertex<SiteGraph>> endVertices = tuple.getSecond();
//            System.out.println("Vertices(Sites): { " + endVertices.get(0).getElement() + ", " +
//                    endVertices.get(1).getElement() + " }, Edge(Distance): " + edge.getElement());
//        }

        // 7. assign global variables and parameters
        return new PreProcessData(instanceName, locationList, vehicleList, customerList,
                graph, new Random(20717331L)); // input the random with seed
    }

    /**
     * time DTO
     */
    protected static class Time {
        private long id;
        private Date start; // 开始时间
        private Date end; // 结束时间
        private double duration; // 时长
        private String jobId; // 任务id
        private int distance; // 行驶距离
        private Vehicle vehicle; // 车辆（暂存使用）
        private long vehicleId; // 车辆id
        private long customerId; // 请求id

        private static int next = 0; // 自增id

        public Time() {
            this.id = 0;
            this.start = new Date();
            this.end = new Date();
            this.duration = 0;
            this.jobId = "";
            this.distance = 0;
            this.vehicleId = 0;
            this.customerId = 0;
        }

        // constructor for temporarily storage
        public Time(Date start, Date end, double duration, String jobId, int distance, Vehicle vehicle, long customerId) {
            this.id = next++;
            this.start = start;
            this.end = end;
            this.duration = duration;
            this.jobId = jobId;
            this.distance = distance;
            this.vehicle = vehicle;
            this.customerId = customerId;
        }

        public Time(Date start, Date end, double duration, String jobId, int distance, long vehicleId, long customerId) {
            this.id = next++;
            this.start = start;
            this.end = end;
            this.duration = duration;
            this.jobId = jobId;
            this.distance = distance;
            this.vehicleId = vehicleId;
            this.customerId = customerId;
        }

        public long getId() {
            return id;
        }

        public Date getStart() {
            return start;
        }

        public void setStart(Date start) {
            this.start = start;
        }

        public Date getEnd() {
            return end;
        }

        public void setEnd(Date end) {
            this.end = end;
        }

        public double getDuration() {
            return duration;
        }

        public void setDuration(double duration) {
            this.duration = duration;
        }

        public String getJobId() {
            return jobId;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public int getDistance() {
            return distance;
        }

        public void setDistance(int distance) {
            this.distance = distance;
        }

        public Vehicle getVehicle() {
            return vehicle;
        }

        public void setVehicle(Vehicle vehicle) {
            this.vehicle = vehicle;
        }

        public long getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(long vehicleId) {
            this.vehicleId = vehicleId;
        }

        public long getCustomerId() {
            return customerId;
        }

        public void setCustomerId(long customerId) {
            this.customerId = customerId;
        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            if (vehicleId >= 0) {
                return "TimeDto{" +
                        "id=" + id +
                        ", start='" + sdf.format(start) + '\'' +
                        ", end='" + sdf.format(end) + '\'' +
                        ", duration=" + duration +
                        ", jobId='" + jobId + '\'' +
                        ", distance=" + distance +
                        ", vehicleId=" + vehicleId +
                        ", customerId=" + customerId +
                        '}';
            } else {
                return "TimeDto{" +
                        "id=" + id +
                        ", start='" + sdf.format(start) + '\'' +
                        ", end='" + sdf.format(end) + '\'' +
                        ", duration=" + duration +
                        ", jobId='" + jobId + '\'' +
                        ", distance=" + distance +
                        ", vehicle=" + vehicle +
                        ", customerId=" + customerId +
                        '}';
            }
        }
    }

    /**
     * Vehicle DTO
     */
    protected static class Vehicle {
        private long id; // 车辆识别码
        private int startSite; // 出发地id
        private Date startTime; // 车辆开始工作的时间
        private int weight; // 车辆容量
        private int endSite; // 目的地id

        private final double mContinuousDrivingInHours = 4.5; // 最大持续驾驶时间
        private final int mDailyDriveInHours = 9; // 每日最大驾驶时间
        private final int mDurationInHours = 13; // 最大路线时长

        // time list for the output
        private List<Time> driveTimeList;
        private List<Time> otherTimeList;
        private List<Time> breakTimeList;
        private List<Time> waitTimeList;
        private List<Time> delayTimeList;

        private int curSiteId; // 当前所在地id

        public Vehicle() {
            this.id = 0;
            this.startSite = 0;
            this.startTime = new Date();
            this.weight = 0;
            this.endSite = 0;
            this.driveTimeList = new ArrayList<>();
            this.otherTimeList = new ArrayList<>();
            this.breakTimeList = new ArrayList<>();
            this.waitTimeList = new ArrayList<>();
            this.delayTimeList = new ArrayList<>();
            this.curSiteId = 0;
        }

        public Vehicle(long id, int startSite, Date startTime, int weight, int endSite,
                       List<Time> driveTimeList, List<Time> otherTimeList, List<Time> breakTimeList,
                       List<Time> waitTimeList, List<Time> delayTimeList, int curSiteId) {
            this.id = id; // id设为compartmentId
            this.startSite = startSite;
            this.startTime = startTime;
            this.weight = weight;
            this.endSite = endSite;
            this.driveTimeList = driveTimeList;
            this.otherTimeList = otherTimeList;
            this.breakTimeList = breakTimeList;
            this.waitTimeList = waitTimeList;
            this.delayTimeList = delayTimeList;
            this.curSiteId = curSiteId; // 初始值应为发车地id，即startSite
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id; // 将id设置为车辆唯一标识码
        }

        public int getStartSite() {
            return startSite;
        }

        public void setStartSite(int startSite) {
            this.startSite = startSite;
        }

        public Date getStartTime() {
            return startTime;
        }

        public void setStartTime(Date startTime) {
            this.startTime = startTime;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public int getEndSite() {
            return endSite;
        }

        public void setEndSite(int endSite) {
            this.endSite = endSite;
        }

        public double getMContinuousDrivingInHours() {
            return mContinuousDrivingInHours;
        }

        public int getmDailyDriveInHours() {
            return mDailyDriveInHours;
        }

        public int getMDurationInHours() {
            return mDurationInHours;
        }

        public void setDriveTimeList(List<Time> driveTimeList) {
            this.driveTimeList = driveTimeList;
        }

        public List<Time> getDriveTimeList() {
            return driveTimeList;
        }

        public void setOtherTimeList(List<Time> otherTimeList) {
            this.otherTimeList = otherTimeList;
        }

        public List<Time> getOtherTimeList() {
            return otherTimeList;
        }

        public void setBreakTimeList(List<Time> breakTimeList) {
            this.breakTimeList = breakTimeList;
        }

        public List<Time> getBreakTimeList() {
            return breakTimeList;
        }

        public void setWaitTimeList(List<Time> waitTimeList) {
            this.waitTimeList = waitTimeList;
        }

        public List<Time> getWaitTimeList() {
            return waitTimeList;
        }

        public void setDelayTimeList(List<Time> delayTimeList) {
            this.delayTimeList = delayTimeList;
        }

        public List<Time> getDelayTimeList() {
            return delayTimeList;
        }

        public int getCurSiteId() {
            return curSiteId;
        }

        public void setCurSiteId(int curSiteId) {
            this.curSiteId = curSiteId;
        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return "VehicleDto{" +
                    "id=" + id +
                    ", startSite='" + startSite + '\'' +
                    ", startTime='" + sdf.format(startTime) + '\'' +
                    ", weight=" + weight +
                    ", endSite='" + endSite + '\'' +
                    ", mContinuousDrivingInHours=" + mContinuousDrivingInHours +
                    ", mDailyDriveInHours=" + mDailyDriveInHours +
                    ", mDurationInHours=" + mDurationInHours +
                    ", driveTimeList='" + driveTimeList.toString() + '\'' +
                    ", otherTimeList='" + otherTimeList.toString() + '\'' +
                    ", breakTimeList='" + breakTimeList.toString() + '\'' +
                    ", waitTimeList='" + waitTimeList.toString() + '\'' +
                    ", delayTimeList='" + delayTimeList.toString() + '\'' +
                    ", curSiteId=" + curSiteId +
                    "}\n";
        }
    }

    /**
     * Site DTO
     */
    protected static class Site {
        private long id; // （取货/送货）地点Id，自增实现
        private String[] coordinates; // （取货/送货）地点坐标
        private JSONArray disAndTime; // 到其他地点的距离/时间

        private static int next = 0; // 自增id

        public Site() {
            this.id = 0;
            this.coordinates = new String[]{};
            this.disAndTime = new JSONArray();
        }

        public Site(String[] coordinates, JSONArray disAndTime) {
            this.id = next++;
            this.coordinates = coordinates;
            this.disAndTime = disAndTime;
        }

        public long getId() {
            return id;
        }

        public String[] getCoordinates() {
            return coordinates;
        }

        public void setCoordinates(String[] coordinates) {
            this.coordinates = coordinates;
        }

        public JSONArray getDisAndTime() {
            return disAndTime;
        }

        public void setDisAndTime(JSONArray disAndTime) {
            this.disAndTime = disAndTime;
        }

        @Override
        public String toString() {
            return "SiteDto{" +
                    "id=" + id +
                    ", coordinates=" + Arrays.toString(coordinates) +
                    ", disAndTime=" + disAndTime.toString() +
                    '}';
        }
    }

    /**
     * SiteGraph (for test) DTO
     */
    protected static class SiteGraph {
        private long id; // （取货/送货）地点Id，自增实现
        private String[] coordinates; // （取货/送货）地点坐标

        private static int next = 0; // 自增id

        public SiteGraph() {
            this.id = 0;
            this.coordinates = new String[]{};
//            this.disAndTime = new JSONArray();
        }

        public SiteGraph(String[] coordinates) {
            this.id = next++;
            this.coordinates = coordinates;
        }

        public long getId() {
            return id;
        }

        public String[] getCoordinates() {
            return coordinates;
        }

        public void setCoordinates(String[] coordinates) {
            this.coordinates = coordinates;
        }

        @Override
        public String toString() {
            return "SiteDto{" +
                    "id=" + id +
                    ", coordinates=" + Arrays.toString(coordinates) +
                    '}';
        }
    }

    /**
     * Customer DTO
     */
    protected static class Customer {
        private long id;
        private String collectId; // collect id
        private String deliverId; // deliver id
        private Site collectSite; // collect site Dto
        private Site deliverSite; // deliver site Dto
        private Time collectTimeWindow; // collect time window
        private Time deliverTimeWindow; // deliver time window
        private long collectTimeinMinutes; // collect time
        private long deliverTimeinMinutes; // deliver time
        private int weight; // weight of customers
        private boolean isDelivered; // whether is delivered
        private long routeId; // allocated route id

        private static int next = 0; // auto-increment id

        public Customer() {
            this.id = 0;
            this.collectId = "";
            this.deliverId = "";
            this.collectSite = new Site();
            this.deliverSite = new Site();
            this.collectTimeWindow = new Time();
            this.deliverTimeWindow = new Time();
            this.collectTimeinMinutes = 0;
            this.deliverTimeinMinutes = 0;
            this.weight = 0;
            this.isDelivered = false;
            this.routeId = -1;
        }

        public Customer(String collectId, String deliverId,
                        Site collectSite, Site deliverSite,
                        Time collectTimeWindow, Time deliverTimeWindow,
                        long collectTimeinMinutes, long deliverTimeinMinutes,
                        int weight, boolean isDelivered, long routeId) {
            this.id = next++;
            this.collectId = collectId;
            this.deliverId = deliverId;
            this.collectSite = collectSite;
            this.deliverSite = deliverSite;
            this.collectTimeWindow = collectTimeWindow;
            this.deliverTimeWindow = deliverTimeWindow;
            this.collectTimeinMinutes = collectTimeinMinutes;
            this.deliverTimeinMinutes = deliverTimeinMinutes;
            this.weight = weight;
            this.isDelivered = isDelivered;
            this.routeId = routeId;
        }

        public long getId() {
            return id;
        }

        public void setCollectId(String collectId) {
            this.collectId = collectId;
        }

        public String getCollectId() {
            return collectId;
        }

        public void setDeliverId(String deliverId) {
            this.deliverId = deliverId;
        }

        public String getDeliverId() {
            return deliverId;
        }

        public Site getCollectSite() {
            return collectSite;
        }

        public void setCollectSite(Site collectSite) {
            this.collectSite = collectSite;
        }

        public Site getDeliverSite() {
            return deliverSite;
        }

        public void setDeliverSite(Site deliverSite) {
            this.deliverSite = deliverSite;
        }

        public Time getCollectTimeWindow() {
            return collectTimeWindow;
        }

        public void setCollectTimeWindow(Time collectTimeWindow) {
            this.collectTimeWindow = collectTimeWindow;
        }

        public Time getDeliverTimeWindow() {
            return deliverTimeWindow;
        }

        public void setDeliverTimeWindow(Time deliverTimeWindow) {
            this.deliverTimeWindow = deliverTimeWindow;
        }

        public long getCollectTimeinMinutes() {
            return collectTimeinMinutes;
        }

        public void setCollectTimeinMinutes(long collectTimeinMinutes) {
            this.collectTimeinMinutes = collectTimeinMinutes;
        }

        public long getDeliverTimeinMinutes() {
            return deliverTimeinMinutes;
        }

        public void setDeliverTimeinMinutes(long deliverTimeinMinutes) {
            this.deliverTimeinMinutes = deliverTimeinMinutes;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public boolean isDelivered() {
            return isDelivered;
        }

        public void setDelivered(boolean isDelivered) {
            this.isDelivered = isDelivered;
        }

        public long getRouteId() {
            return routeId;
        }

        public void setRouteId(long routeId) {
            this.routeId = routeId;
        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return "CustomerDto{" +
                    "id=" + id +
                    ", collectId='" + collectId + '\'' +
                    ", deliverId='" + deliverId + '\'' +
                    ", collectSite='" + collectSite.toString() + '\'' +
                    ", deliverSite='" + deliverSite.toString() + '\'' +
                    ", collectTimeWindow='" + collectTimeWindow.toString() + '\'' +
                    ", deliverTimeWindow='" + deliverTimeWindow.toString() + '\'' +
                    ", collectTimeinMinutes=" + collectTimeinMinutes + "min" +
                    ", deliverTimeinMinutes=" + deliverTimeinMinutes + "min" +
                    ", weight=" + weight +
                    ", isDelivered=" + isDelivered +
                    ", routeId=" + routeId +
                    "}\n";
        }
    }

    /**
     * Route(gene) DTO
     */
    protected static class Route {
        private long id;
        private Vehicle vehicle; // 车辆信息
        private List<Customer> customers; // 客户列表
        private Date startTime; // 路线开始时间
        private Date endTime; // 路线结束时间
        private long overallDuration; // 总工作时长
        private int overallDistance; // 总路程
        private int overallWeight; // 总重量
        private long overallBreak; // 总休息时长
        private int randN; // 随机获取的车辆下标

        private Map<Long, Customer> pairMap; // 用于取送货的配对

        private static int next = 0; // 自增id

        public Route() {
            this.id = 0;
            this.vehicle = new Vehicle();
            this.customers = new ArrayList<>();
            this.startTime = new Date();
            this.endTime = new Date();
            this.overallDuration = 0;
            this.overallDistance = 0;
            this.overallWeight = 0;
            this.overallBreak = 0;
            this.randN = 0;
            this.pairMap = new HashMap<>();
        }

        public Route(Vehicle vehicle, List<Customer> customers,
                     Date startTime, Date endTime,
                     long overallDuration, int overallDistance,
                     int overallWeight, int overallBreak, int randN, Map<Long, Customer> pairMap) {
            this.id = next++;
            this.vehicle = vehicle;
            this.customers = customers;
            this.startTime = startTime;
            this.endTime = endTime;
            this.overallDuration = overallDuration;
            this.overallDistance = overallDistance;
            this.overallWeight = overallWeight;
            this.overallBreak = overallBreak;
            this.randN = randN;
            this.pairMap = pairMap;
        }

        public long getId() {
            return id;
        }

        public void setVehicle(Vehicle vehicle) {
            this.vehicle = vehicle;
        }

        public Vehicle getVehicle() {
            return vehicle;
        }

        public void setCustomers(List<Customer> customers) {
            this.customers = customers;
        }

        public List<Customer> getCustomers() {
            return customers;
        }

        public void setStartTime(Date startTime) {
            this.startTime = startTime;
        }

        public Date getStartTime() {
            return startTime;
        }

        public void setEndTime(Date endTime) {
            this.endTime = endTime;
        }

        public Date getEndTime() {
            return endTime;
        }

        public void setOverallDuration(long overallDuration) {
            this.overallDuration = overallDuration;
        }

        public long getOverallDuration() {
            return overallDuration;
        }

        public void setOverallDistance(int overallDistance) {
            this.overallDistance = overallDistance;
        }

        public int getOverallDistance() {
            return overallDistance;
        }

        public void setOverallWeight(int overallWeight) {
            this.overallWeight = overallWeight;
        }

        public int getOverallWeight() {
            return overallWeight;
        }

        public void setOverallBreak(long overallBreak) {
            this.overallBreak = overallBreak;
        }

        public long getOverallBreak() {
            return overallBreak;
        }

        public void setRandN(int randN) {
            this.randN = randN;
        }

        public int getRandN() {
            return randN;
        }

        public void setPairMap(Map<Long, Customer> pairMap) {
            this.pairMap = pairMap;
        }

        public Map<Long, Customer> getPairMap() {
            return pairMap;
        }

        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return "RouteDto{" +
                    "id=" + id +
                    "\n, vehicle='" + vehicle.toString() + '\'' +
                    "\n, customers='" + customers.toString() + '\'' +
                    "\n, startTime='" + sdf.format(startTime) + '\'' +
                    ", endTime='" + sdf.format(endTime) + '\'' +
                    ", overallDuration=" + overallDuration +
                    ", overallDistance=" + overallDistance +
                    ", overallWeight=" + overallWeight +
                    ", overallBreak=" + overallBreak +
                    ", pairMap=" + pairMap.toString() +
                    '}';
        }

        /**
         * determine whether it needs a break, return a list containing updated breakTime and driveTime
         */
        protected List<Long> needBreaks(long startTime, long overallDuration, long breakTime,
                                        long otherTime, long driveTime, Customer customer) {
            System.out.println("休息startTime： " + new Date(startTime));
            System.out.println("休息overallDuration： " + overallDuration / (1000 * 60.0) + " min");
            System.out.println("休息breakTime： " + breakTime / (1000 * 60.0) + " min");
            System.out.println("休息otherTime： " + otherTime / (1000 * 60.0) + " min");
            System.out.println("休息driveTime： " + driveTime / (1000 * 60.0) + " min");
            List<Long> res = new ArrayList<>();
//            long tmp=breakTime; // 记录更新前的breakTime
            boolean flag = false; // 判断是否重置drive time

            // 1. 驾驶4.5h，至少休息45min（分两段）
            if (driveTime >= 4.5 * 60 * 60 * 1000) {
//                // TODO  尝试枚举判断30min/45min，选用用时更少的休息规则
                if (breakTime < 45 * 60 * 1000) { // 休息45min
                    breakTime += 15 * 60 * 1000; // take a 15-minute-break
                    createTimeNode(getVehicle(),
                            startTime,
                            15 * 60 * 1000,
                            "break",
                            0,
                            customer.getId(),
                            "temp"); // 创建时间节点
                    startTime += 15 * 60 * 1000; // for update
                    breakTime += 30 * 60 * 1000; // then, take a 30-minute-break
                    createTimeNode(getVehicle(),
                            startTime,
                            30 * 60 * 1000,
                            "break",
                            0,
                            customer.getId(),
                            "temp"); // 创建时间节点
                    startTime += 30 * 60 * 1000; // for update
//                    driveTime = 0; // 重置驾驶时间
                    flag = true;
                }
            }
            // 2. 总工作时间6~9h，应休息30min，这里休息45min重置drive Time
            if (overallDuration >= 6 * 60 * 60 * 1000 && overallDuration < 9 * 60 * 60 * 1000) {
//                if (breakTime < 45 * 60 * 1000) {
//                    breakTime += 30 * 60 * 1000;
                breakTime += 45 * 60 * 1000;
                createTimeNode(getVehicle(),
                        startTime,
//                            30 * 60 * 1000,
                        45 * 60 * 1000,
                        "break",
                        0,
                        customer.getId(),
                        "temp"); // 创建时间节点
//                    startTime += 30 * 60 * 1000;
                startTime += 45 * 60 * 1000;
                flag = true;
//                }
            }
            // 3. 总工作时间超过9h，应休息45min
            if (overallDuration >= 9 * 60 * 60 * 1000) {
                if (breakTime < 45 * 60 * 1000) {
                    breakTime += 45 * 60 * 1000;
                    createTimeNode(getVehicle(),
                            startTime,
                            45 * 60 * 1000,
                            "break",
                            0,
                            customer.getId(),
                            "temp"); // 创建时间节点
                    startTime += 45 * 60 * 1000;
                    flag = true;
                }
            }
            // 4. 重置drive time
            if (flag) driveTime = 0;
            // 5. 返回更新后的时间数据
            res.add(breakTime); // first item
            res.add(driveTime); // second item
            res.add(startTime); // third item(start time as next job out of this function)
            res.add(otherTime); // fourth item
            return res;
        }

        private List<Time> tmpTimeList = new ArrayList<>(); // 暂存取货的时间节点

        /**
         * create a time node for the output
         */
        public void createTimeNode(Vehicle vehicle, long startTime, long duration, String type,
                                   int distance, long customerId, String controlType) {
            if (vehicle != null) {
                Date start = new Date(startTime);
                Date end = new Date(startTime + duration);

                if (controlType.equals("temp")) {
                    // 暂存到tmpTimeList中
                    if (type.equals("drive")) {
                        // 创建Time时传入Vehicle实体
                        Time time = new Time(start, end, duration, "drive", distance, vehicle, customerId);
                        tmpTimeList.add(time);
//                        System.out.println("tmp storaging driveTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDriveTimeList());
                    } else if (type.equals("break")) {
                        Time time = new Time(start, end, duration, "break", distance, vehicle, customerId);
                        tmpTimeList.add(time);
//                        System.out.println("tmp storaging breakTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getBreakTimeList());
                    } else if (type.equals("wait")) {
                        Time time = new Time(start, end, duration, "wait", distance, vehicle, customerId);
                        tmpTimeList.add(time);
//                        System.out.println("tmp storaging waitTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getWaitTimeList());
                    } else if (type.equals("delay")) {
                        Time time = new Time(start, end, duration, "delay", distance, vehicle, customerId);
                        tmpTimeList.add(time);
//                        System.out.println("tmp storaging delayTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDelayTimeList());
                    } else if (type.equals("return")) { // return to the depot
                        Time time = new Time(start, end, duration, "return", distance, vehicle, customerId);
                        tmpTimeList.add(time);
//                        System.out.println("tmp storaging driveTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDriveTimeList());
                    } else { // collect/deliver
                        Time time = new Time(start, end, duration, type, distance, vehicle, customerId);
                        tmpTimeList.add(time);
//                        System.out.println("tmp storaging otherTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getOtherTimeList());
                    }
                } else if (controlType.equals("create")) {
                    // 执行
                    if (type.equals("drive")) {
                        // 创建Time时传入Vehicle的四位ID
                        Time time = new Time(start, end, duration, "drive", distance, vehicle.getId(), customerId);
                        List<Time> driveList = vehicle.getDriveTimeList();
                        driveList.add(time);
                        vehicle.setDriveTimeList(driveList);
//                        System.out.println("driveTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDriveTimeList());
                    } else if (type.equals("break")) {
                        Time time = new Time(start, end, duration, "break", distance, vehicle.getId(), customerId);
                        List<Time> breakList = vehicle.getBreakTimeList();
                        breakList.add(time);
                        vehicle.setBreakTimeList(breakList);
//                        System.out.println("breakTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getBreakTimeList());
                    } else if (type.equals("wait")) {
                        Time time = new Time(start, end, duration, "wait", distance, vehicle.getId(), customerId);
                        List<Time> waitList = vehicle.getWaitTimeList();
                        waitList.add(time);
                        vehicle.setWaitTimeList(waitList);
//                        System.out.println("waitTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getWaitTimeList());
                    } else if (type.equals("delay")) {
                        Time time = new Time(start, end, duration, "delay", distance, vehicle.getId(), customerId);
                        List<Time> delayList = vehicle.getDelayTimeList();
                        delayList.add(time);
                        vehicle.setDelayTimeList(delayList);
//                        System.out.println("delayTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDelayTimeList());
                    } else if (type.equals("return")) { // return to the depot
                        Time time = new Time(start, end, duration, "return", distance, vehicle.getId(), customerId);
                        List<Time> driveList = vehicle.getDriveTimeList(); // store in the driveList
                        driveList.add(time);
                        vehicle.setDriveTimeList(driveList);
//                        System.out.println("driveTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getDriveTimeList());
                    } else { // collect/deliver
                        Time time = new Time(start, end, duration, type, distance, vehicle.getId(), customerId);
                        List<Time> otherList = vehicle.getOtherTimeList();
                        otherList.add(time);
                        vehicle.setOtherTimeList(otherList);
//                        System.out.println("otherTimeList: vehicle " + vehicle.getId() + "; " + vehicle.getOtherTimeList());
                    }
                }
            }
        }

        public Tuple<Boolean, GlobalData> canAddACustomerBySeparation(Customer customer, PreProcessData data,
                                                                      boolean collectFirst) {
            long dailyDriveTime = 0; // 每日累积驾驶时间
            int totalDistance = 0; // 每日累计路程
            InnerTuple<Boolean, GlobalData> res;

            if (customer != null) {
                // 1. 每个请求的取货地点必须在对应的送货地点前被访问
                if (collectFirst) { // 取货
                    for (Customer otherCustomer : getCustomers()) {
                        // 已经送货但还没取货，则不满足
                        if (otherCustomer.getDeliverSite().getId() == customer.getCollectSite().getId()) {
                            if (getCustomers().indexOf(otherCustomer) < getCustomers().indexOf(customer)) {
                                System.out.println("The customer " + customer.getId() + " should meet the precedence constraints: " +
                                        "the collect site must be the predecessor of the delivered site.");
                                return new InnerTuple<>(false, new GlobalData());
                            }
                        }
                    }
                } else { // 送货
                    for (Customer otherCustomer : getCustomers()) {
                        // 已经送了，就不满足
                        if (otherCustomer.getDeliverSite().getId() == customer.getDeliverSite().getId()) {
                            if (getCustomers().indexOf(otherCustomer) < getCustomers().indexOf(customer)) {
                                System.out.println("The customer " + customer.getId() + " should meet the precedence constraints: " +
                                        "the collect site must be the predecessor of the delivered site.");
                                return new InnerTuple<>(false, new GlobalData());
                            }
                        }
                    }
                }

                // 2. 客户容量不超过车辆总承重
                if (collectFirst
                        && (getOverallWeight() + customer.getWeight() > getVehicle().getWeight())) {
                    System.out.println("The capacity of vehicle " + getVehicle().getId() +
                            " is full and new customers cannot be added.");
                    return new InnerTuple<>(false, new GlobalData());
                }

                // 3. 车辆必须在取货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
                long curTime;
                if (!getCustomers().isEmpty()) {
                    curTime = (getVehicle().getStartTime()).getTime() + getOverallDuration(); // 上一次请求的总时间作为当前请求的开始时间
                } else {
                    curTime = (getVehicle().getStartTime()).getTime(); // vehicle的发车时间作为起始时间
                }
                System.out.println("new Date " + new Date(curTime));

                // 获取当前Route的发车时间、累计duration
                Date date = data.getOverallDeliverTime();
                long duration = 0;
                if (date != null) duration = date.getTime();
                long vehicleStartTime = (getVehicle().getStartTime()).getTime();
                System.out.println("DURATION: " + date + "; " + duration);
                System.out.println("发车：" + new Date(vehicleStartTime) + "; " + vehicleStartTime);
                System.out.println("差值： " + (duration - vehicleStartTime));

//                long breakTime = 0, otherTime = 0, driveTime = 0;
//                if (data.getBreakTime()!=null){
//                    Map<Long, Long> map1 = data.getBreakTime();
//                    for (long routeId : map1.keySet()) {
//                        if (routeId == getId()) breakTime = map1.get(routeId);
//                    }
//                }
//                if (data.getOtherTime()!=null){
//                    Map<Long, Long> map2 = data.getOtherTime();
//                    for (long routeId : map2.keySet()) {
//                        if (routeId == getId()) otherTime = map2.get(routeId);
//                    }
//                }
//                if (data.getDriveTime()!=null){
//                    Map<Long, Long> map3 = data.getDriveTime();
//                    for (long routeId : map3.keySet()) {
//                        if (routeId == getId()) driveTime = map3.get(routeId);
//                    }
//                }
                // 获取当前Route的累计break、other work、drive time
                long breakTime = data.getBreakTime(),
                        otherTime = data.getOtherTime(),
                        driveTime = data.getDriveTime();

//                // 从data获取overall duration time，用于计算是否需要休息
//                long vehicleStartTime=(getVehicle().getStartTime()).getTime();
//                Date date=data.getOverallDeliverTime();
//                long overallTime=0;
//                if (date!=null) overallTime=date.getTime();
//                System.out.println("测试overall： "+date);
//                System.out.println("测试overall： "+overallTime);
//                System.out.println("测试curTime： "+curTime);
//                System.out.println("测试发车时间："+vehicleStartTime);
//                System.out.println("测试差值："+(overallTime-vehicleStartTime));

                System.out.println("每次请求开始时间！！：" + new Date(curTime));

                // 4. 取货
                if (collectFirst) {
                    // 车辆的发车地点（包含到其他地点的距离\时间）
                    Site startSite = data.getLocationList().get(getVehicle().getCurSiteId()); // 每次请求，都从当前位置开始
                    System.out.println("startSite: " + startSite.toString());
                    // 取货地点，计算车辆从发车地点到取货地点的时间
                    Site collectSite = data.getLocationList().get((int) customer.getCollectSite().getId());
                    System.out.println("collectSite: " + collectSite.toString());

                    // 判断发车地点、取货地点是否是同个地点
                    if (startSite == collectSite) {
                        // 若是，则判断发车时间是否在时间窗口内
                        long tmp = curTime; // 记录取货更新操作前的当前时间
                        System.out.println("问题: " + customer.getCollectTimeWindow().getStart());
                        System.out.println("问题: " + customer.getCollectTimeWindow().getEnd());
                        if (curTime >= customer.getCollectTimeWindow().getStart().getTime()
                                && curTime <= customer.getCollectTimeWindow().getEnd().getTime()) {
                            System.out.println("!!!!!!directly COLLECT!!"); // test
                            // 在时间窗口内，则直接取货，计算取货用时
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            List<Long> breakResult = needBreaks(curTime,
//                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    curTime - tmp,
//                                    curTime-vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else if (curTime < customer.getCollectTimeWindow().getStart().getTime()) {
                            System.out.println("!!!!!!wait and directly COLLECT!!"); // test
                            // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                            long wait = Math.abs(customer.getCollectTimeWindow().getStart().getTime() - curTime);
//                            breakTime += wait;
                            curTime += wait;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    wait,
                                    "wait",
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + wait,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            List<Long> breakResult = needBreaks(curTime,
//                                    wait + customer.getCollectTimeinMinutes() * 60 * 1000,
                                    curTime - tmp,
//                                    curTime-vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else {
                            // 不满足时间窗口约束
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the collect time window.");
                            return new InnerTuple<>(false, new GlobalData());
                        }

//                        // check again
//                        List<Long> breakResult = needBreaks(curTime,
//                                curTime - tmp,
//                                breakTime, otherTime, driveTime, customer); // check whether a break is need
//                        if (!breakResult.isEmpty()) {
//                            breakTime = breakResult.get(0);
//                            driveTime = breakResult.get(1);
//                            curTime = breakResult.get(2);
//                            otherTime = breakResult.get(3);
//                        }
                    } else {
                        JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) collectSite.getId()));
                        long collectRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上发车地点->取货地点的时间
                        int collectRouteDistance = (Integer) arr.opt(0); // distance between start site and collect site
                        long tmp = curTime; // 记录更新操作前的当前时间

                        System.out.println("collectRouteTime: " + collectRouteTime + "s");
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                        Date collectWindowStart = new Date(customer.getCollectTimeWindow().getStart().getTime());
                        Date collectWindowEnd = new Date(customer.getCollectTimeWindow().getEnd().getTime());
                        System.out.println("collectWindow: " + sdf.format(collectWindowStart) + "; " + sdf.format(collectWindowEnd));
                        System.out.println("collectTime: " + customer.getCollectTimeinMinutes() + "min");
                        System.out.println("carStartTime: " + sdf.format(getVehicle().getStartTime()));

                        if ((curTime + collectRouteTime * 1000)
                                >= customer.getCollectTimeWindow().getStart().getTime()
                                && (curTime + collectRouteTime * 1000)
                                <= customer.getCollectTimeWindow().getEnd().getTime()) {
                            System.out.println("!!!!!!go to another site to COLLECT!!"); // test

                            // 取货，计算取货用时
//                            curTime += collectRouteTime * 1000
//                                    + customer.getCollectTimeinMinutes() * 60 * 1000;
                            driveTime += collectRouteTime * 1000;
                            curTime += collectRouteTime * 1000;
                            dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += collectRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    collectRouteTime * 1000,
                                    "drive",
                                    collectRouteDistance,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            List<Long> breakResult = needBreaks(curTime,
//                                    collectRouteTime * 1000,
                                    curTime - tmp,
//                                    curTime-vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp + collectRouteTime * 1000,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            breakResult = needBreaks(curTime,
//                                    collectRouteTime * 1000 + customer.getCollectTimeinMinutes() * 60 * 1000,
                                    curTime - tmp,
//                                    curTime-vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else if ((curTime + collectRouteTime * 1000)
                                < customer.getCollectTimeWindow().getStart().getTime()) {
                            System.out.println("!!!!!!go to another site to COLLECT!!"); // test

                            // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                            long wait = Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000));
//                            breakTime += wait;
                            curTime += wait;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    wait,
                                    "wait",
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            driveTime += collectRouteTime * 1000;
                            curTime += collectRouteTime * 1000;
                            dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += collectRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + wait,
                                    collectRouteTime * 1000,
                                    "drive",
                                    collectRouteDistance,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            List<Long> breakResult = needBreaks(curTime,
//                                    wait + collectRouteTime * 1000,
                                    curTime - tmp,
//                                    curTime-vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                            otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + wait
                                            + collectRouteTime * 1000,
                                    customer.getCollectTimeinMinutes() * 60 * 1000,
                                    customer.getCollectId(),
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            breakResult = needBreaks(curTime,
//                                    wait + collectRouteTime * 1000 + customer.getCollectTimeinMinutes() * 60 * 1000,
                                    curTime - tmp,
//                                    curTime-vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else {
                            // 不满足时间窗口约束
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the collect time window.");
                            return new InnerTuple<>(false, new GlobalData());
                        }

//                        // check again
//                        List<Long> breakResult = needBreaks(curTime,
//                                curTime - tmp,
//                                breakTime, otherTime, driveTime, customer); // check whether a break is need
//                        if (!breakResult.isEmpty()) {
//                            breakTime = breakResult.get(0);
//                            driveTime = breakResult.get(1);
//                            curTime = breakResult.get(2);
//                            otherTime = breakResult.get(3);
//                        }
                    }

                    // 测试总用时
                    Date overallCollectTime = new Date(curTime);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    System.out.println("Overall Time: " + sdf.format(overallCollectTime));
                    System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
                    System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
                    System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min\n");

                    // 将成功创建使用的参数设置到全局data中
                    GlobalData globalData = new GlobalData(
                            curTime - (getVehicle().getStartTime()).getTime(),
                            data.getLocationList().get((int) customer.getCollectSite().getId()),
                            breakTime, overallCollectTime,
                            getId(), customer.getId(),
                            dailyDriveTime, totalDistance);
                    res = new InnerTuple<>(true, globalData);
                    data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
                    data.setDeliverSite(data.getLocationList().get((int) customer.getCollectSite().getId()));
                    data.setOverallDeliverTime(overallCollectTime);

                    // 设置breakTime、driveTime、otherTime
                    data.setBreakTime(breakTime);
                    data.setDriveTime(driveTime);
                    data.setOtherTime(otherTime);

                    // 当前请求加入全局列表
//                    assignedCustomer.add(customer);
                    List<Customer> customerList = assignedCustomer.get(getId());
                    if (customerList != null) { // 有元素，则添加
                        customerList.add(customer);
                        assignedCustomer.put(getId(), customerList);
                    } else { // 没元素，则新建列表
                        List<Customer> newList = new ArrayList<>();
                        newList.add(customer);
                        assignedCustomer.put(getId(), newList);
                    }
                    return res;
                }

                // 5. 只在送货时/完成所有取货后,检查送货时间窗口
                Date overallDeliverTime = new Date();
                Site deliverSite = new Site();
                if (!collectFirst || (collectFirst && getCustomers().size() == data.getCustomerList().size())) {
                    Site startSite = data.getLocationList().get(getVehicle().getCurSiteId());
                    deliverSite = data.getLocationList().get((int) customer.getDeliverSite().getId()); // 送货地点
                    System.out.println("deliverSite1: " + customer.getId() + "; " + deliverSite.toString());

                    if (startSite != deliverSite) { // 取货地点、送货地点不同
                        // 判断到达送货地点的时间是否在时间窗口内
                        JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) deliverSite.getId()));
                        long deliverRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上取货地点->送货地点的时间
                        int deliverRouteDistance = (Integer) arr.opt(0); // distance between collect site and deliver site
                        long tmp = curTime; // 记录取货后，送货更新操作前的当前时间

                        System.out.println("deliverRouteTime: " + deliverRouteTime + "s");
//                    System.out.println("22: " + curTime + "ms; " + customer.getDeliverTimeWindow().getStart().getTime() + "ms");
//                    System.out.println("222: " + ((customer.getDeliverTimeWindow().getStart().getTime()) - curTime) + "ms");

                        SimpleDateFormat sdf;
                        sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                        Date deliverWindowStart = new Date(customer.getDeliverTimeWindow().getStart().getTime());
                        Date deliverWindowEnd = new Date(customer.getDeliverTimeWindow().getEnd().getTime());
                        System.out.println("deliverWindow: " + sdf.format(deliverWindowStart) + "; " + sdf.format(deliverWindowEnd));
                        System.out.println("deliverTime: " + customer.getDeliverTimeinMinutes() + "ms");

                        if ((curTime + deliverRouteTime * 1000)
                                >= customer.getDeliverTimeWindow().getStart().getTime()
                                && (curTime + deliverRouteTime * 1000)
                                <= customer.getDeliverTimeWindow().getEnd().getTime()) {
                            System.out.println("!!go to another site to DELIVER!!"); // test

                            // 送货，计算送货用时
                            driveTime += deliverRouteTime * 1000;
                            curTime += deliverRouteTime * 1000;
                            dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += deliverRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    deliverRouteTime * 1000,
                                    "drive",
                                    deliverRouteDistance,
                                    customer.getId(),
                                    "temp"); // 创建时间节点4
                            List<Long> breakResult = needBreaks(curTime,
//                                    deliverRouteTime * 1000,
//                                    curTime-tmp,
                                    duration - vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            curTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp + (deliverRouteTime * 1000),
                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
                                    customer.getDeliverId(),
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            breakResult = needBreaks(curTime,
//                                    deliverRouteTime * 1000 + customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                    curTime-tmp,
                                    duration - vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else if ((curTime + deliverRouteTime * 1000)
                                < customer.getDeliverTimeWindow().getStart().getTime()) {
                            System.out.println("!!go to another site to DELIVER!!"); // test

                            // 若需要等待直到送货时间窗口开放，则需要加上等待窗口开放的时间
                            long wait = Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000));
//                            breakTime += wait;
                            curTime += wait;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    wait,
                                    "wait",
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            driveTime += deliverRouteTime * 1000;
                            curTime += deliverRouteTime * 1000;
                            dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                            totalDistance += deliverRouteDistance;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + wait,
                                    deliverRouteTime * 1000,
                                    "drive",
                                    deliverRouteDistance,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            List<Long> breakResult = needBreaks(curTime,
//                                    wait + deliverRouteTime * 1000,
//                                    curTime-tmp,
                                    duration - vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            curTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp
                                            + wait
                                            + deliverRouteTime * 1000,
                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
                                    customer.getDeliverId(),
                                    0,
                                    customer.getId(),
                                    "temp"); // 创建时间节点
                            breakResult = needBreaks(curTime,
//                                    wait + deliverRouteTime * 1000 + customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                    curTime-tmp,
                                    duration - vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else {
                            // 不满足时间窗口约束
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the deliver time window.");
                            return new InnerTuple<>(false, new GlobalData());
                        }

//                        // check again
//                        List<Long> breakResult = needBreaks(curTime,
//                                curTime - tmp,
//                                breakTime, otherTime, driveTime, customer); // check whether a break is need
//                        if (!breakResult.isEmpty()) {
//                            breakTime = breakResult.get(0);
//                            driveTime = breakResult.get(1);
//                            curTime = breakResult.get(2);
//                            otherTime = breakResult.get(3);
//                        }
                    } else {
                        long tmp = curTime;
                        if (curTime >= customer.getDeliverTimeWindow().getStart().getTime()
                                && curTime <= customer.getDeliverTimeWindow().getEnd().getTime()) {
                            System.out.println("!!directly DELIVER!!");
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            curTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
                                    customer.getDeliverId(),
                                    0,
                                    customer.getId(),
                                    "temp");
                            List<Long> breakResult = needBreaks(curTime,
//                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                    curTime-tmp,
                                    duration - vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else if (curTime < customer.getDeliverTimeWindow().getStart().getTime()) {
                            System.out.println("!!wait DELIVER!!");
//                            curTime += (customer.getDeliverTimeWindow().getStart().getTime() - curTime)
//                                    + customer.getDeliverTimeinMinutes() * 60 * 1000;
                            long wait = (customer.getDeliverTimeWindow().getStart().getTime() - curTime);
//                            breakTime += wait;
                            curTime += wait;
                            createTimeNode(getVehicle(),
                                    tmp,
                                    wait,
                                    "wait",
                                    0,
                                    customer.getId(),
                                    "temp");
                            otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            curTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                            createTimeNode(getVehicle(),
                                    tmp + wait,
                                    customer.getDeliverTimeinMinutes() * 60 * 1000,
                                    customer.getDeliverId(),
                                    0,
                                    customer.getId(),
                                    "temp");
                            List<Long> breakResult = needBreaks(curTime,
//                                    wait + customer.getDeliverTimeinMinutes() * 60 * 1000,
//                                    curTime-tmp,
                                    duration - vehicleStartTime,
                                    breakTime, otherTime, driveTime, customer); // check whether a break is need
                            if (!breakResult.isEmpty()) {
                                breakTime = breakResult.get(0);
                                driveTime = breakResult.get(1);
                                curTime = breakResult.get(2);
                                otherTime = breakResult.get(3);
                            }
                        } else {
                            System.out.println("The vehicle " + getVehicle().getId() +
                                    " should arrive between the deliver time window.");
                            return new InnerTuple<>(false, new GlobalData());
                        }

//                        // check again
//                        List<Long> breakResult = needBreaks(curTime,
//                                curTime - tmp,
//                                breakTime, otherTime, driveTime, customer); // check whether a break is need
//                        if (!breakResult.isEmpty()) {
//                            breakTime = breakResult.get(0);
//                            driveTime = breakResult.get(1);
//                            curTime = breakResult.get(2);
//                            otherTime = breakResult.get(3);
//                        }
                    }

                    // 测试总用时
                    overallDeliverTime = new Date(curTime);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    System.out.println("Overall Time': " + sdf.format(overallDeliverTime));
                    System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
                    System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
                    System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min");
                    System.out.println("Overall Distance: " + totalDistance + "\n");
                }

                // 6. 只有在所有取货和送货都完成后才进行Maximum约束检查
//                if (!collectFirst && getCustomers().size() == data.getCustomerList().size()) {
                if (!collectFirst) {
                    System.out.println("curTime: " + curTime + " ; " + new Date(curTime));
                    System.out.println("vehicle start time: " + (getVehicle().getStartTime()).getTime() + " ; " + new Date((getVehicle().getStartTime()).getTime()));
                    System.out.println("daily drive time: " + (dailyDriveTime / (1000 * 60.0)) + "min");
                    System.out.println("ROUTE duration: " +
                            ((curTime - (getVehicle().getStartTime()).getTime()) / (1000 * 60 * 60.0)) + "h");

                    // 1) route duration
                    long mDuration = (long) getVehicle().getMDurationInHours() * 60 * 60 * 1000;
                    if ((curTime - (getVehicle().getStartTime()).getTime()) > mDuration) {
                        // 不满足约束
                        System.out.println("The vehicle " + getVehicle().getId() + " has worked more than 13 hours in one day.");
//                    return false;
                        return new InnerTuple<>(false, new GlobalData());
                    }
                    // 2) daily drive time
                    long mDailyDrive = (long) getVehicle().getmDailyDriveInHours() * 60 * 60 * 1000;
                    if ((dailyDriveTime - (getVehicle().getStartTime()).getTime()) > mDailyDrive) {
                        // 不满足约束
                        System.out.println("The vehicle " + getVehicle().getId() + " has driven more than 9 hours in one day.");
//                    return false;
                        return new InnerTuple<>(false, new GlobalData());
                    }

                    // 7. 将成功创建使用的参数设置到全局data中
                    GlobalData globalData = new GlobalData(
                            curTime - (getVehicle().getStartTime()).getTime(),
//                            data.getLocationList().get((int) customer.getDeliverSite().getId()),
                            deliverSite,
                            breakTime, overallDeliverTime,
                            getId(), customer.getId(),
                            dailyDriveTime,
                            totalDistance);
                    res = new InnerTuple<>(true, globalData);

                    // 8. 设置到全局
//                    System.out.println("更新: " + new Date(curTime) + "; 更新: " + getVehicle().getStartTime());
                    data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
                    data.setDeliverSite(deliverSite);
                    data.setOverallDeliverTime(overallDeliverTime);

                    // 9. 设置breakTime、otherTime、driveTime
                    data.setBreakTime(breakTime);
                    data.setOtherTime(otherTime);
                    data.setDriveTime(driveTime);

                    // 10. 当前请求加入全局列表
//                    assignedCustomer.add(customer);
                    List<Customer> customerList = assignedCustomer.get(getId());
                    if (customerList != null && !customerList.isEmpty()) { // 有元素，则添加
                        customerList.add(customer);
                        assignedCustomer.put(getId(), customerList);
                    } else { // 没元素，则新建列表
                        List<Customer> newList = new ArrayList<>();
                        newList.add(customer);
                        assignedCustomer.put(getId(), newList);
                    }

                    // 11. 标记为已送货
                    customer.setDelivered(true);
                } else {
                    res = new InnerTuple<>(true, new GlobalData());
                }
                return res;
            } else {
                System.out.println("Please ensure that the new request is valid.");
                return new InnerTuple<>(true, new GlobalData());
            }
        }

        public Tuple<Boolean, GlobalData> canAddACustomerByCombination(Customer customer, PreProcessData data) {
            long dailyDriveTime = 0; // 每日累积驾驶时间
            int totalDistance = 0;
            InnerTuple<Boolean, GlobalData> res;

            if (customer != null) {
                // 1. 每个请求的取货地点必须在对应的送货地点前被访问
                for (Customer otherCustomer : getCustomers()) {
                    // 找到当前请求的取货地点，已在该路线中存在，则比较顺序
                    if (otherCustomer.getDeliverSite().getId() == customer.getCollectSite().getId()) {
                        // 由于新请求的indexOf返回-1，若只要已存在的请求在当前请求的前面，则不满足
                        if (getCustomers().indexOf(otherCustomer) < getCustomers().indexOf(customer)) {
                            System.out.println("The customer " + customer.getId() + " should meet the precedence constraints: " +
                                    "the collect site must be the predecessor of the delivered site.");
                            return new InnerTuple<>(false, new GlobalData());
                        }
                    }
                }

                // 2. 客户容量不超过车辆总承重
                if (getOverallWeight() + customer.getWeight() > getVehicle().getWeight()) {
                    System.out.println("The capacity of vehicle " + getVehicle().getId() +
                            " is full and new customers cannot be added.");
                    return new InnerTuple<>(false, new GlobalData());
                }

                // 3. 车辆必须在取货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
                long curTime;
                if (!getCustomers().isEmpty()) {
                    curTime = (getVehicle().getStartTime()).getTime() + getOverallDuration(); // 上一次请求的总时间作为当前请求的开始时间
                } else {
                    curTime = (getVehicle().getStartTime()).getTime(); // vehicle的发车时间作为起始时间
                }

                // 获取当前Route的发车时间、累计duration
                Date date = data.getOverallDeliverTime();
                long duration = 0;
                if (date != null) duration = date.getTime();
                long vehicleStartTime = (getVehicle().getStartTime()).getTime();
                System.out.println("DURATION: " + date + "; " + duration);
                System.out.println("发车：" + new Date(vehicleStartTime) + "; " + vehicleStartTime);
                System.out.println("差值： " + (duration - vehicleStartTime));
                System.out.println("当前：" + getVehicle().getId());

//                // 获取车辆发车时间
//                long vehicleStartTime = (getVehicle().getStartTime()).getTime();

                // 获取当前Route的累计break、other work、drive time
//                long breakTime = 0, otherTime = 0, driveTime = 0;
//                Map<Long, Long> map1 = data.getBreakTime();
//                Map<Long, Long> map2 = data.getOtherTime();
//                Map<Long, Long> map3 = data.getDriveTime();
//                if (map1 != null) {
//                    for (long routeId : map1.keySet()) {
//                        if (routeId == getId()) breakTime = map1.get(routeId);
//                    }
//                }
//                if (map2 != null) {
//                    for (long routeId : map2.keySet()) {
//                        if (routeId == getId()) otherTime = map2.get(routeId);
//                    }
//                }
//                if (map3 != null) {
//                    for (long routeId : map3.keySet()) {
//                        if (routeId == getId()) driveTime = map3.get(routeId);
//                    }
//                }
                long breakTime = data.getBreakTime(),
                        otherTime = data.getOtherTime(),
                        driveTime = data.getDriveTime();

//                System.out.println("每次请求开始时间：" + new Date(curTime));

                // 4. 获取发车地点、取货地点
                // 发车地点（包含到其他地点的距离\时间）
//                Site startSite = data.getLocationList().get(getVehicle().getStartSite());
                Site startSite = data.getLocationList().get(getVehicle().getCurSiteId()); // 每次请求，都从当前位置开始
//                System.out.println("startSite: " + startSite.toString());
                // 取货地点，计算车辆从发车地点到取货地点的时间
                Site collectSite = data.getLocationList().get((int) customer.getCollectSite().getId());
//                System.out.println("collectSite: " + collectSite.toString());

                // 5. 判断发车地点、取货地点是否是同个地点
                if (startSite == collectSite) {
                    // 若是，则判断发车时间是否在时间窗口内
                    long tmp = curTime; // 记录取货更新操作前的当前时间
                    if (curTime >= customer.getCollectTimeWindow().getStart().getTime()
                            && curTime <= customer.getCollectTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!directly COLLECT!!"); // test
                        // 在时间窗口内，则直接取货，计算取货用时
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点

                        System.out.println("这是测试：" + new Date(tmp) + "; " + tmp);
                        System.out.println("这是测试：" + new Date(curTime) + "; " + curTime);

                        List<Long> breakResult = needBreaks(curTime,
//                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                curTime - tmp,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                    } else if (curTime < customer.getCollectTimeWindow().getStart().getTime()) {
//                        System.out.println("!!directly COLLECT!!"); // test
                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                        long wait = Math.abs(customer.getCollectTimeWindow().getStart().getTime() - curTime);
//                        breakTime += wait;
                        curTime += wait;
                        createTimeNode(getVehicle(),
                                tmp,
                                wait,
                                "wait",
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp + wait,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        List<Long> breakResult = needBreaks(curTime,
//                                wait + customer.getCollectTimeinMinutes() * 60 * 1000,
                                curTime - tmp,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                    } else {
                        // 不满足时间窗口约束
                        System.out.println("The vehicle " + getVehicle().getId() +
                                " should arrive between the collect time window." + " cus: " + customer.getId());
                        return new InnerTuple<>(false, new GlobalData());
                    }

//                    // check again
//                    List<Long> breakResult = needBreaks(curTime,
//                            curTime - tmp,
//                            breakTime, otherTime, driveTime, customer); // check whether a break is need
//                    if (!breakResult.isEmpty()) {
//                        breakTime = breakResult.get(0);
//                        driveTime = breakResult.get(1);
//                        curTime = breakResult.get(2);
//                        otherTime = breakResult.get(3);
//                    }
                } else {
                    // 否则，需要加上行驶路程的时间、距离，判断发车时间是否在时间窗口内
                    JSONArray arr = new JSONArray((String) startSite.getDisAndTime().toList().get((int) collectSite.getId()));
                    long collectRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上发车地点->取货地点的时间
                    int collectRouteDistance = (Integer) arr.opt(0); // distance between start site and collect site
                    long tmp = curTime; // 记录更新操作前的当前时间

//                    System.out.println("collectRouteTime: " + collectRouteTime + "s");
//                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                    Date collectWindowStart = new Date(customer.getCollectTimeWindow().getStart().getTime());
//                    Date collectWindowEnd = new Date(customer.getCollectTimeWindow().getEnd().getTime());
//                    System.out.println("collectWindow: " + sdf.format(collectWindowStart) + "; " + sdf.format(collectWindowEnd));
//                    System.out.println("collectTime: " + customer.getCollectTimeinMinutes() + "min");
//                    System.out.println("carStartTime: " + sdf.format(getVehicle().getStartTime()));

                    if ((curTime + collectRouteTime * 1000)
                            >= customer.getCollectTimeWindow().getStart().getTime()
                            && (curTime + collectRouteTime * 1000)
                            <= customer.getCollectTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!go to another site to COLLECT!!"); // test

                        // 取货，计算取货用时
                        driveTime += collectRouteTime * 1000;
                        curTime += collectRouteTime * 1000;
                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += collectRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp,
                                collectRouteTime * 1000,
                                "drive",
                                collectRouteDistance,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        List<Long> breakResult = needBreaks(curTime,
//                                collectRouteTime * 1000,
                                curTime - tmp,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp + collectRouteTime * 1000,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        breakResult = needBreaks(curTime,
//                                collectRouteTime * 1000 + customer.getCollectTimeinMinutes() * 60 * 1000,
                                curTime - tmp,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                    } else if ((curTime + collectRouteTime * 1000)
                            < customer.getCollectTimeWindow().getStart().getTime()) {
//                        System.out.println("!!go to another site to COLLECT!!"); // test

                        // 若需要等待直到取货时间窗口开放，则需要加上等待窗口开放的时间
                        long wait = Math.abs(customer.getCollectTimeWindow().getStart().getTime() - (curTime + collectRouteTime * 1000));
//                        breakTime += wait;
                        curTime += wait;
                        createTimeNode(getVehicle(),
                                tmp,
                                wait,
                                "wait",
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        driveTime += collectRouteTime * 1000;
                        curTime += collectRouteTime * 1000;
                        dailyDriveTime += collectRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += collectRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp
                                        + wait,
                                collectRouteTime * 1000,
                                "drive",
                                collectRouteDistance,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        List<Long> breakResult = needBreaks(curTime,
//                                wait + collectRouteTime * 1000,
                                curTime - tmp,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                        otherTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        curTime += customer.getCollectTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp
                                        + wait
                                        + collectRouteTime * 1000,
                                customer.getCollectTimeinMinutes() * 60 * 1000,
                                customer.getCollectId(),
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        breakResult = needBreaks(curTime,
//                                wait + collectRouteTime * 1000 + customer.getCollectTimeinMinutes() * 60 * 1000,
                                curTime - tmp,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                    } else {
                        // 不满足时间窗口约束
                        System.out.println("The vehicle " + getVehicle().getId() +
                                " should arrive between the collect time window." + " cus: " + customer.getId());
                        return new InnerTuple<>(false, new GlobalData());
                    }

//                    // check again
//                    List<Long> breakResult = needBreaks(curTime,
//                            curTime - tmp,
//                            breakTime, otherTime, driveTime, customer); // check whether a break is need
//                    if (!breakResult.isEmpty()) {
//                        breakTime = breakResult.get(0);
//                        driveTime = breakResult.get(1);
//                        curTime = breakResult.get(2);
//                        otherTime = breakResult.get(3);
//                    }
                }

                // 测试总用时
//                Date overallCollectTime = new Date(curTime);
//                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                System.out.println("Overall Time: " + sdf.format(overallCollectTime));
//                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
//                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
//                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min\n");

                // 6. 获取送货地点
                Site deliverSite = data.getLocationList().get((int) customer.getDeliverSite().getId()); // 送货地点
//                System.out.println("deliverSite: " + deliverSite.toString());

                // 7. 车辆必须在送货时间窗口内到达（若早于，则等待；若晚于，则拒绝添加该请求）
                if (collectSite != deliverSite) { // 取货地点、送货地点不同
                    // 判断到达送货地点的时间是否在时间窗口内
                    JSONArray arr = new JSONArray((String) collectSite.getDisAndTime().toList().get((int) deliverSite.getId()));
                    long deliverRouteTime = ((Integer) arr.opt(1)).longValue(); // 加上取货地点->送货地点的时间
                    int deliverRouteDistance = (Integer) arr.opt(0); // distance between collect site and deliver site
                    long tmp = curTime; // 记录取货后，送货更新操作前的当前时间

//                    System.out.println("deliverRouteTime: " + deliverRouteTime + "s");
//                    System.out.println("22: " + curTime + "ms; " + customer.getDeliverTimeWindow().getStart().getTime() + "ms");
//                    System.out.println("222: " + ((customer.getDeliverTimeWindow().getStart().getTime()) - curTime) + "ms");
//
//                    sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
//                    Date deliverWindowStart = new Date(customer.getDeliverTimeWindow().getStart().getTime());
//                    Date deliverWindowEnd = new Date(customer.getDeliverTimeWindow().getEnd().getTime());
//                    System.out.println("deliverWindow: " + sdf.format(deliverWindowStart) + "; " + sdf.format(deliverWindowEnd));
//                    System.out.println("deliverTime: " + customer.getDeliverTimeinMinutes() + "ms");

                    if ((curTime + deliverRouteTime * 1000)
                            >= customer.getDeliverTimeWindow().getStart().getTime()
                            && (curTime + deliverRouteTime * 1000)
                            <= customer.getDeliverTimeWindow().getEnd().getTime()) {
//                        System.out.println("!!go to another site to DELIVER!!"); // test

                        // 送货，计算送货用时
                        driveTime += deliverRouteTime * 1000;
                        curTime += deliverRouteTime * 1000;
                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += deliverRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp,
                                deliverRouteTime * 1000,
                                "drive",
                                deliverRouteDistance,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        List<Long> breakResult = needBreaks(curTime,
//                                deliverRouteTime * 1000,
                                curTime - vehicleStartTime,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                        curTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp + (deliverRouteTime * 1000),
                                customer.getDeliverTimeinMinutes() * 60 * 1000,
                                customer.getDeliverId(),
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        breakResult = needBreaks(curTime,
//                                deliverRouteTime * 1000 + customer.getDeliverTimeinMinutes() * 60 * 1000,
                                curTime - vehicleStartTime,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                    } else if ((curTime + deliverRouteTime * 1000)
                            < customer.getDeliverTimeWindow().getStart().getTime()) {
//                        System.out.println("!!go to another site to DELIVER!!"); // test

                        // 若需要等待直到送货时间窗口开放，则需要加上等待窗口开放的时间
                        long wait = Math.abs(customer.getDeliverTimeWindow().getStart().getTime() - (curTime + deliverRouteTime * 1000));
//                        breakTime += wait;
                        curTime += wait;
                        createTimeNode(getVehicle(),
                                tmp,
                                wait,
                                "wait",
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        driveTime += deliverRouteTime * 1000;
                        curTime += deliverRouteTime * 1000;
                        dailyDriveTime += deliverRouteTime * 1000; // 更新每日累积驾驶时间
                        totalDistance += deliverRouteDistance;
                        createTimeNode(getVehicle(),
                                tmp
                                        + wait,
                                deliverRouteTime * 1000,
                                "drive",
                                deliverRouteDistance,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        List<Long> breakResult = needBreaks(curTime,
//                                wait + deliverRouteTime * 1000,
                                curTime - vehicleStartTime,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                        otherTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                        curTime += customer.getDeliverTimeinMinutes() * 60 * 1000;
                        createTimeNode(getVehicle(),
                                tmp
                                        + wait
                                        + deliverRouteTime * 1000,
                                customer.getDeliverTimeinMinutes() * 60 * 1000,
                                customer.getDeliverId(),
                                0,
                                customer.getId(),
                                "temp"); // 创建时间节点
                        breakResult = needBreaks(curTime,
//                                wait + deliverRouteTime * 1000 + customer.getDeliverTimeinMinutes() * 60 * 1000,
                                curTime - vehicleStartTime,
                                breakTime, otherTime, driveTime, customer); // check whether a break is need
                        if (!breakResult.isEmpty()) {
                            breakTime = breakResult.get(0);
                            driveTime = breakResult.get(1);
                            curTime = breakResult.get(2);
                            otherTime = breakResult.get(3);
                        }
                    } else {
                        // 不满足时间窗口约束
                        System.out.println("The vehicle " + getVehicle().getId() +
                                " should arrive between the deliver time window." + " cus: " + customer.getId());
                        return new InnerTuple<>(false, new GlobalData());
                    }

//                    // check again
//                    List<Long> breakResult = needBreaks(curTime,
//                            curTime - tmp,
//                            breakTime, otherTime, driveTime, customer); // check whether a break is need
//                    if (!breakResult.isEmpty()) {
//                        breakTime = breakResult.get(0);
//                        driveTime = breakResult.get(1);
//                        curTime = breakResult.get(2);
//                        otherTime = breakResult.get(3);
//                    }
                }

                // 测试总用时
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                Date overallDeliverTime = new Date(curTime);
                System.out.println("OverallDeliverTime: " + sdf.format(overallDeliverTime));
//                System.out.println("Overall Drive Time: " + (driveTime / (1000 * 60)) + "min");
//                System.out.println("Overall Other work Time: " + (otherTime / (1000 * 60)) + "min");
//                System.out.println("Overall Break Time: " + (breakTime / (1000 * 60)) + "min");
//                System.out.println("Overall Distance: " + totalDistance + "\n");

                // 8. 判断是否满足Maximum约束，并计算成本（overall duration time）
//                System.out.println("route duration: " +
//                        ((curTime - (getVehicle().getStartTime()).getTime()) / (1000 * 60 * 60.0)) + "h");
//                System.out.println("daily drive time: " + (dailyDriveTime / (1000 * 60.0)) + "min");
                // 1) route duration
                long mDuration = (long) getVehicle().getMDurationInHours() * 60 * 60 * 1000;
                if ((curTime - (getVehicle().getStartTime()).getTime()) > mDuration) {
                    // 不满足约束
                    System.out.println("The vehicle " + getVehicle().getId() + " has worked more than 13 hours in one day.");
                    return new InnerTuple<>(false, new GlobalData());
                }
                // 2) daily drive time
                long mDailyDrive = (long) getVehicle().getmDailyDriveInHours() * 60 * 60 * 1000;
                if ((dailyDriveTime - (getVehicle().getStartTime()).getTime()) > mDailyDrive) {
                    // 不满足约束
                    System.out.println("The vehicle " + getVehicle().getId() + " has driven more than 9 hours in one day.");
                    return new InnerTuple<>(false, new GlobalData());
                }

                // 9. 将成功创建使用的参数设置到全局data中
                GlobalData globalData = new GlobalData(
                        curTime - (getVehicle().getStartTime()).getTime(),
                        deliverSite, breakTime, overallDeliverTime,
                        getId(), customer.getId(),
                        dailyDriveTime,
                        totalDistance);
                res = new InnerTuple<>(true, globalData);
                data.setCurTime(curTime - (getVehicle().getStartTime()).getTime());
                data.setDeliverSite(deliverSite);
                data.setOverallDeliverTime(overallDeliverTime);
//                data.setBreakTime(breakTime);

                // 10. 设置breakTime、driveTime、otherTime
//                if (map1 != null) {
//                    map1.put(getId(), breakTime);
//                }
//                if (map2 != null) {
//                    map2.put(getId(), driveTime);
//                }
//                if (map3 != null) {
//                    map3.put(getId(), otherTime);
//                }
//                data.setBreakTime(map1);
//                data.setDriveTime(map2);
//                data.setOtherTime(map3);
                data.setBreakTime(breakTime);
                data.setDriveTime(driveTime);
                data.setOtherTime(otherTime);

                // 11. 标记为已送货
                customer.setDelivered(true);
                System.out.println("cusId: " + customer.getId() + " can. carID: " + getVehicle().getId());
            } else {
                System.out.println("Please ensure that the new request is valid.");
                return new InnerTuple<>(true, new GlobalData());
            }
            return res;
        }

        public long addACustomer(Customer customer, PreProcessData data, GlobalData globalData,
                                 boolean collectFirst, String type) {
            if (customer != null) {
                // 1. 获取数据
                long curTime = data.getCurTime();
                Site deliverSite = data.getDeliverSite(); // collectSite or deliverSite
                Date overallDeliverTime = data.getOverallDeliverTime();
                int totalDistance = globalData.getOverallDistance();

                // 获取当前Route的累计break
                long breakTime = data.getBreakTime();
//                long breakTime = 0;
//                Map<Long, Long> map1 = data.getBreakTime();
//                if (map1 != null) {
//                    for (long routeId : map1.keySet()) {
//                        if (routeId == getId()) breakTime = map1.get(routeId);
//                    }
//                }

//                System.out.println("data.getCurTime：" + data.getCurTime());
//                System.out.println("deliverSite: " + deliverSite);
//                long routeId=globalData.getRouteId();
//                long customerId=globalData.getCustomerId();
//                long curTime = globalData.getCurTime();
//                Site deliverSite = globalData.getDeliverSite();
//                long breakTime = globalData.getBreakTime();
//                Date overallDeliverTime = globalData.getOverallDeliverTime();

                // 2. 添加请求customer
                getCustomers().add(customer);

//                if (type.equals("combination")) {
//                    for (Entry<Long, List<Customer>> entry : assignedCustomer.entrySet()) {
//                        long routeIdx = entry.getKey(); // obtain the index of each route
////                        System.out.println("rid: " + routeIdx);
//                        List<Customer> customerList = entry.getValue();
////                        System.out.println("cusList: "+customerList.toString());
//                        for (Customer c : customerList) {
//                            if (!c.isDelivered()) System.out.println("999");
//                        }
//                    }
//                }

                // 3. 每执行addCustomer，就创建Time节点，创建后将全局列表tmpTimeList清零
                List<Time> timeList = tmpTimeList;
                for (Time time : timeList) {
                    long start = time.getStart().getTime();
                    createTimeNode(time.getVehicle(), start, (long) time.getDuration(), time.getJobId(),
                            time.getDistance(), time.getCustomerId(), "create");
                }
                tmpTimeList.clear(); // tmpTimeList列表清零

                // 4. 设置当前所在地点的id
//                getVehicle().setCurSiteId((int) deliverSite.getId());
                Vehicle curVehicle = getVehicle(); // 获取当前route的车辆
                curVehicle.setCurSiteId((int) deliverSite.getId());
                setVehicle(curVehicle);
//                System.out.println("vehicle " + getVehicle().getId() + "; cursiteid after: " + getVehicle().getCurSiteId()); // test

//                // 测试
//                System.out.println("OOO weight: " + getOverallWeight());
//                System.out.println("weight: " + customer.getWeight());
//                System.out.println("OOO duration: " + getOverallDuration());
//                System.out.println("duration: " + curTime);
//                System.out.println("OOO deliver: " + overallDeliverTime);
//                System.out.println("startTime: " + getVehicle().getStartTime());

                // 5. 遇到最后一个订单的送货，才设置overall weight
                if (customer.isDelivered() && !collectFirst) {
                    int newOverallWeight = getOverallWeight() + customer.getWeight(); // 设置车辆总重量
                    setOverallWeight(newOverallWeight);
                }

                // 6. 设置其他参数
//                long newOverallDuration = getOverallDuration() + curTime;
                long newOverallDuration = curTime; // 设置overall route duration until now
                setOverallDuration(newOverallDuration);
                long newOverallBreak = getOverallBreak() + breakTime; // TODO  待验证
                setOverallBreak(newOverallBreak);
                setOverallDistance(getOverallDistance() + totalDistance);
                setStartTime(getVehicle().getStartTime()); // 设置为路线开始时间
                setEndTime(overallDeliverTime); // 设置为送货结束的时间（有新请求加入路线时，会更新为最新的结束时间）
                customer.setRouteId(getId()); // 给当前请求设置分配的路线id
//                System.out.println("添加时的curTime：" + curTime);
//                System.out.println("添加时的new overall duration：" + newOverallDuration);  // TODO  1130 1020
//               尝试 startTime+getOverallDuration()
//                setStartTime(newStartTime); // 设置为路线开始时间 TODO  1130 1043
//                setOverallDuration(getOverallDuration() + curTime); // 设置overall route duration

                // 7. 更新data中的车辆信息
                List<Vehicle> sourceVehicleList = data.getVehicleList(); // 获取全局列表Vehicle List
                curVehicle = sourceVehicleList.get(getRandN()); // 获取全局列表中当前操作的车辆
                curVehicle.setCurSiteId((int) deliverSite.getId()); // 更新该车辆的信息
                sourceVehicleList.set(getRandN(), curVehicle); // 更新后的车辆设置回原全局列表中
                data.setVehicleList(sourceVehicleList);
//                data.setCurTime(curTime);
//                data.setOverallDeliverTime(overallDeliverTime);

                // 8. 全局变量中的driveTime、otherTime、breakTime清零
                if (customer.isDelivered() && !collectFirst) {
                    data.setDriveTime(0);
                    data.setBreakTime(0);
                    data.setOtherTime(0);
                }

                // 9. 返回route id
                return getId();
            }
            return -1;
        }

        public Boolean returnToDepot(Customer customer, PreProcessData data, long dailyDriveTime, long overallDuration) {
            if (customer != null) {
                // 1. 获取全局data中需要使用的数据
//                long curTime = data.getCurTime(); // 送完货的耗时
                long curTime = overallDuration;
//                System.out.println("返程！： " + overallDuration + " ms; " + new Date(overallDuration));
                Site deliverSite = data.getDeliverSite();
//                System.out.println("return back time：" + data.getCurTime());

                // 2. 获取终点到起点的时间
                Site endSite = data.getLocationList().get(getVehicle().getEndSite()); // 车辆的终点
                long returnTime;
                int returnDistance;
                if (endSite == deliverSite) { // 车辆终点=送货终点
                    returnTime = 0;
                    returnDistance = 0;
                } else { // 车辆终点!=送货终点
                    JSONArray arr = new JSONArray((String) deliverSite.getDisAndTime().toList().get((int) endSite.getId()));
                    returnTime = ((Integer) arr.opt(1)).longValue(); // 送货终点->起点的时间
                    returnDistance = (Integer) arr.opt(0); // distance between deliver site and start site
                }
//                System.out.println("end site:" + endSite.toString());
//                System.out.println("deliver site: " + deliverSite.getId() + ";\n content: " + deliverSite.toString());
//                System.out.println("vehicle id: " + getVehicle().getStartSite());
//                System.out.println("return time: " + returnTime * 1000);

                // 3. 获取总时间,创建时间节点
                try {
                    curTime += returnTime * 1000;
                    createTimeNode(getVehicle(),
                            overallDuration, // input parameter
                            returnTime * 1000, // unit: ms
                            "return",
                            returnDistance,
                            customer.getId(),
                            "create"); // 创建时间节点

                    // 4. 获取drive time
                    dailyDriveTime += returnTime * 1000;
//                    System.out.println("daily: " + dailyDriveTime);

                    // 5. 设置当前所在地点的id
                    Vehicle curVehicle = getVehicle(); // 获取当前route的车辆
                    curVehicle.setCurSiteId((int) endSite.getId());
                    setVehicle(curVehicle);
//                    System.out.println("vehicle " + getVehicle().getId() + "; cursiteid after: " + getVehicle().getCurSiteId()); // test

                    // 6. 判断约束,不满足则抛出异常,由initializePopulation()拒绝该请求
//                long newOverallDuration = getOverallDuration() + curTime;
                    long newOverallDuration = curTime;
                    // 1) route duration
                    long mDuration = (long) curVehicle.getMDurationInHours() * 60 * 60 * 1000;
//                    if (newOverallDuration > mDuration) {
                    if ((newOverallDuration - (curVehicle.getStartTime()).getTime()) > mDuration) {
                        throw new RuntimeException("The vehicle " + curVehicle.getId() + " has worked more than 13 hours in one day.");
                    }
                    // 2) daily drive time (should add the start time of vehicle and then do subtraction)
                    long mDailyDrive = (long) curVehicle.getmDailyDriveInHours() * 60 * 60 * 1000;
//                    System.out.println("测试测试1："+(dailyDriveTime+curVehicle.getStartTime().getTime())+" ; "+new Date((dailyDriveTime+curVehicle.getStartTime().getTime())));
//                    System.out.println("测试测试2："+(curVehicle.getStartTime()).getTime()+" ; "+new Date((curVehicle.getStartTime()).getTime()));
//                    System.out.println("测试测试3："+((dailyDriveTime+curVehicle.getStartTime().getTime()) - (curVehicle.getStartTime()).getTime()));
//                    if ((dailyDriveTime - (curVehicle.getStartTime()).getTime()) > mDailyDrive) {
                    if (((dailyDriveTime + curVehicle.getStartTime().getTime()) - (curVehicle.getStartTime()).getTime()) > mDailyDrive) {
                        throw new RuntimeException("The vehicle " + curVehicle.getId() + " has driven more than 9 hours in one day.");
                    }

                    // 7. update parameters
                    System.out.println("返程函数里overall duration: " + new Date(newOverallDuration) + "; vehicleId: " + getVehicle().getId());
                    setOverallDuration(newOverallDuration); // 设置overall route duration
                    setOverallDistance(getOverallDistance() + returnDistance); // 设置overall travel distance
                    setEndTime(new Date(getStartTime().getTime() + newOverallDuration)); // 设置为回到起点的时间
//                    System.out.println("new curTime：" + curTime);
//                    System.out.println("new overall duration：" + newOverallDuration);
//                    setEndTime(new Date(getEndTime().getTime()+newOverallDuration)); // 设置为回到起点的时间
                } catch (RuntimeException e) {
                    throw new RuntimeException("The customer cannot meet the constraints.");
                }
            } else {
                throw new RuntimeException("The customer should not be empty.");
            }
            return true;
        }
    }

    /**
     * Individual(chromosome) DTO
     */
    protected static class Individual {
        private long id;
        private List<Route> routes; // 路线列表
        private double fitness; // 适应度值(选择：overall duration time)

        private static int next = 0; // 自增id

        public Individual() {
            this.id = 0;
            this.routes = new ArrayList<>();
            this.fitness = 0.0;
        }

        public Individual(List<Route> routes, double fitness) {
            this.id = next++;
            this.routes = routes;
            this.fitness = fitness;
        }

        public long getId() {
            return id;
        }

        public void setRoutes(List<Route> routes) {
            this.routes = routes;
        }

        public List<Route> getRoutes() {
            return routes;
        }

        public void setFitness(double fitness) {
            this.fitness = fitness;
        }

        public double getFitness() {
            return fitness;
        }

        @Override
        public String toString() {
            return "IndividualDto{" +
                    "id=" + id +
                    "\n, routes=" + routes.toString() +
                    "\n, fitness=" + fitness +
                    "}\n";
        }
    }

    /**
     * Population DTO
     */
    protected static class Population {
        private long id;
        private List<Individual> individuals; // 个体列表
        private int size; // 种群大小
        private double overallFitness; // 个体的总适应度
        private List<Double> pointers; // SUS算法的指针位置
        private List<Integer> pointerToIndividual; // SUS的指针对应的个体列表

        private static int next = 0; // 自增id

        public Population() {
            this.id = 0;
            this.individuals = new ArrayList<>();
            this.size = 0;
            this.overallFitness = 0.0;
            this.pointers = new ArrayList<>();
            this.pointerToIndividual = new ArrayList<>();
        }

        public Population(List<Individual> individuals, int size, double overallFitness,
                          List<Double> pointers, List<Integer> pointerToIndividual) {
            this.id = next++;
            this.individuals = individuals;
            this.size = size;
            this.overallFitness = overallFitness;
            this.pointers = pointers;
            this.pointerToIndividual = pointerToIndividual;
        }

        public long getId() {
            return id;
        }

        public void setIndividuals(List<Individual> individuals) {
            this.individuals = individuals;
        }

        public List<Individual> getIndividuals() {
            return individuals;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public int getSize() {
            return size;
        }

        public void setOverallFitness(double overallFitness) {
            this.overallFitness = overallFitness;
        }

        public double getOverallFitness() {
            return overallFitness;
        }

        public void setPointers(List<Double> pointers) {
            this.pointers = pointers;
        }

        public List<Double> getPointers() {
            return pointers;
        }

        public void setPointerToIndividual(List<Integer> pointerToIndividual) {
            this.pointerToIndividual = pointerToIndividual;
        }

        public List<Integer> getPointerToIndividual() {
            return pointerToIndividual;
        }

        @Override
        public String toString() {
            return "PopulationDto{" +
                    "id=" + id +
                    ", individuals=" + individuals.toString() +
                    ", size=" + size +
                    ", overallFitness=" + overallFitness +
                    ", pointers=" + pointers.toString() +
                    ", pointerToIndividual=" + pointerToIndividual.toString() +
                    '}';
        }
    }

    /**
     * Global Data DTO
     */
    protected static class GlobalData {
        private long id;
        private long curTime;
        private Site deliverSite;
        private Date overallDeliverTime; // overall deliver time
        private long routeId;
        private long customerId;
        private long dailyDriveTime; // daily drive time
        private int overallDistance; // overall distance
        private long breakTime;

        private static int next = 0; // 自增id

        public GlobalData() {
            this.id = 0;
            this.curTime = 0;
            this.deliverSite = new Site();
            this.breakTime = 0;
            this.overallDeliverTime = new Date();
            this.routeId = 0;
            this.customerId = 0;
            this.dailyDriveTime = 0;
        }

        public GlobalData(long curTime, Site deliverSite, long breakTime, Date overallDeliverTime,
                          long routeId, long customerId, long dailyDriveTime, int overallDistance) {
            this.id = next++;
            this.curTime = curTime;
            this.deliverSite = deliverSite;
            this.breakTime = breakTime;
            this.overallDeliverTime = overallDeliverTime;
            this.routeId = routeId;
            this.customerId = customerId;
            this.dailyDriveTime = dailyDriveTime;
            this.overallDistance = overallDistance;
        }

        public long getId() {
            return id;
        }

        public void setCurTime(long curTime) {
            this.curTime = curTime;
        }

        public long getCurTime() {
            return curTime;
        }

        public void setDeliverSite(Site deliverSite) {
            this.deliverSite = deliverSite;
        }

        public Site getDeliverSite() {
            return deliverSite;
        }

        public void setBreakTime(long breakTime) {
            this.breakTime = breakTime;
        }

        public long getBreakTime() {
            return breakTime;
        }

        public void setOverallDeliverTime(Date overallDeliverTime) {
            this.overallDeliverTime = overallDeliverTime;
        }

        public Date getOverallDeliverTime() {
            return overallDeliverTime;
        }

        public void setRouteId(long routeId) {
            this.routeId = routeId;
        }

        public long getRouteId() {
            return routeId;
        }

        public void setCustomerId(long customerId) {
            this.customerId = customerId;
        }

        public long getCustomerId() {
            return customerId;
        }

        public void setDailyDriveTime(long dailyDriveTime) {
            this.dailyDriveTime = dailyDriveTime;
        }

        public long getDailyDriveTime() {
            return dailyDriveTime;
        }

        public void setOverallDistance(int overallDistance) {
            this.overallDistance = overallDistance;
        }

        public int getOverallDistance() {
            return overallDistance;
        }

        @Override
        public String toString() {
            return "GlobalDataDto{" +
                    "id=" + id +
                    "\n curTime=" + curTime +
                    "\n deliverSite=" + deliverSite.toString() +
                    "\n breakTime=" + breakTime +
                    "\n overallDeliverTime=" + overallDeliverTime.toString() +
                    "\n routeId=" + routeId +
                    "\n customerId=" + customerId +
                    "\n dailyDriveTime=" + dailyDriveTime +
                    "\n overallDistance=" + overallDistance +
                    "}\n";
        }
    }

    /**
     * Pre Process Data DTO
     */
    protected static class PreProcessData {
        private String instanceName;
        private List<Site> locationList;
        private List<Vehicle> vehicleList;
        private List<Customer> customerList;
        private AdjacencyListGraph<SiteGraph, Integer> graph; // 所有地点的图

        /* 分配请求customer的数据 */
        private Site deliverSite;
        private long curTime;
        private Date overallDeliverTime;
        private long breakTime;
        private long driveTime;
        private long otherTime;

        private Random random; // 生成随机数

        public PreProcessData() {
            this.instanceName = "";
            this.locationList = new ArrayList<>();
            this.vehicleList = new ArrayList<>();
            this.customerList = new ArrayList<>();
            this.graph = new AdjacencyListGraph<>(true);

            this.deliverSite = new Site();
            this.curTime = 0;
            this.overallDeliverTime = new Date();
            this.breakTime = 0;
            this.driveTime = 0;
            this.otherTime = 0;

            this.random = new Random(20717331L); // set student id as seed
        }

        public PreProcessData(String instanceName, List<Site> locationList,
                              List<Vehicle> vehicleList, List<Customer> customerList,
                              AdjacencyListGraph<SiteGraph, Integer> graph, Random random) {
            this.instanceName = instanceName;
            this.locationList = locationList;
            this.vehicleList = vehicleList;
            this.customerList = customerList;
            this.graph = graph;
            this.random = random;
        }

        public void setInstanceName(String instanceName) {
            this.instanceName = instanceName;
        }

        public String getInstanceName() {
            return instanceName;
        }

        public void setLocationList(List<Site> locationList) {
            this.locationList = locationList;
        }

        public List<Site> getLocationList() {
            return locationList;
        }

        public void setVehicleList(List<Vehicle> vehicleList) {
            this.vehicleList = vehicleList;
        }

        public List<Vehicle> getVehicleList() {
            return vehicleList;
        }

        public void setCustomerList(List<Customer> customerList) {
            this.customerList = customerList;
        }

        public List<Customer> getCustomerList() {
            return customerList;
        }

        public void setGraph(AdjacencyListGraph<SiteGraph, Integer> graph) {
            this.graph = graph;
        }

        public AdjacencyListGraph<SiteGraph, Integer> getGraph() {
            return graph;
        }

        public void setDeliverSite(Site deliverSite) {
            this.deliverSite = deliverSite;
        }

        public Site getDeliverSite() {
            return deliverSite;
        }

        public void setCurTime(long curTime) {
            this.curTime = curTime;
        }

        public long getCurTime() {
            return curTime;
        }

        public void setOverallDeliverTime(Date overallDeliverTime) {
            this.overallDeliverTime = overallDeliverTime;
        }

        public Date getOverallDeliverTime() {
            return overallDeliverTime;
        }

        public void setRandom(Random random) {
            this.random = random;
        }

        public Random getRandom() {
            return random;
        }

        public void setBreakTime(long breakTime) {
            this.breakTime = breakTime;
        }

        public long getBreakTime() {
            return breakTime;
        }

        public void setDriveTime(long driveTime) {
            this.driveTime = driveTime;
        }

        public long getDriveTime() {
            return driveTime;
        }

        public void setOtherTime(long otherTime) {
            this.otherTime = otherTime;
        }

        public long getOtherTime() {
            return otherTime;
        }

        // factory function
        public static PreProcessData initialize(PreProcessData sourceData) {
            return new PreProcessData(sourceData.getInstanceName(), sourceData.getLocationList(),
                    sourceData.getVehicleList(), sourceData.getCustomerList(),
                    sourceData.getGraph(), sourceData.getRandom());
        }
    }

    /**
     * DTO for storing successful added customer
     */
    protected static class SucCustomerDto {
        private long id;
        private Customer customer;
        private Individual individual;
        private int individualIdx;
        private Route route;
        private int routeIdx;
        private GlobalData globalData;
        private boolean isReturned; // 判断是否已经处理过返回仓库

        private static int next = 0; // 自增id

        public SucCustomerDto() {
            this.id = 0;
            this.customer = new Customer();
            this.individual = new Individual();
            this.route = new Route();
            this.individualIdx = 0;
            this.routeIdx = 0;
            this.globalData = new GlobalData();
            this.isReturned = false;
        }

        public SucCustomerDto(Customer customer, Individual individual, Route route,
                              int individualIdx, int routeIdx, GlobalData globalData,
                              boolean isReturned) {
            this.id = next++;
            this.customer = customer;
            this.individual = individual;
            this.route = route;
            this.individualIdx = individualIdx;
            this.routeIdx = routeIdx;
            this.globalData = globalData;
            this.isReturned = isReturned;
        }

        public void setCustomer(Customer customer) {
            this.customer = customer;
        }

        public Customer getCustomer() {
            return customer;
        }

        public void setIndividual(Individual individual) {
            this.individual = individual;
        }

        public Individual getIndividual() {
            return individual;
        }

        public void setRoute(Route route) {
            this.route = route;
        }

        public Route getRoute() {
            return route;
        }

        public void setIndividualIdx(int individualIdx) {
            this.individualIdx = individualIdx;
        }

        public int getIndividualIdx() {
            return individualIdx;
        }

        public void setRouteIdx(int routeIdx) {
            this.routeIdx = routeIdx;
        }

        public int getRouteIdx() {
            return routeIdx;
        }

        public void setGlobalData(GlobalData globalData) {
            this.globalData = globalData;
        }

        public GlobalData getGlobalData() {
            return globalData;
        }

        public void setReturned(boolean isReturned) {
            this.isReturned = isReturned;
        }

        public boolean isReturned() {
            return isReturned;
        }

        @Override
        public String toString() {
            return "SucCustomerDto{" +
                    "id=" + id +
                    "\n, customer=" + customer.toString() +
                    "\n, individual=" + individual.toString() +
                    "\n, individualIdx=" + individualIdx +
                    "\n, route=" + route.toString() +
                    "\n, routeIdx=" + routeIdx +
                    "\n, globalData=" + globalData +
                    "\n, isReturned=" + isReturned +
                    '}';
        }
    }

    //    protected static List<Customer> assignedCustomer = new CopyOnWriteArrayList<>(); // assigned customers
    protected static Map<Long, List<Customer>> assignedCustomer = new HashMap<>(); // assigned customers, key=routeId, value=customerList
    protected static List<Customer> unassignedCustomer = new ArrayList<>(); // unassigned customers

    protected static Tuple<List<Individual>, Map<Integer, SucCustomerDto>> assignCustomers(
            List<Individual> individuals, PreProcessData data, boolean method,
            Map<Integer, SucCustomerDto> sucCustomers, List<Customer> customerList) {
        Tuple<List<Individual>, Map<Integer, SucCustomerDto>> res;
        Map<Integer, SucCustomerDto> tmp = sucCustomers; // TODO 1204 1949注释
//        Map<Integer, SucCustomerDto> tmp = new HashMap<>();

        // 1. 对于每个要插入的请求，检查当前（部分）解决方案中所有现有路线的所有可行插入点
        //      测试路线中取货和送货节点的所有可能插入位置，同时考虑优先级、容量和时间约束
        if (!method) { // 先取货后送货
            // 1.对所有订单按照取货顺序排序
            List<Customer> collectCustomers = new ArrayList<>(customerList);
            collectCustomers.sort((o1, o2) -> Long.compare(o1.getCollectSite().getId(), o2.getCollectSite().getId()));
//            System.out.println("Sorted collectCustomers: " + collectCustomers);

            // 2.遍历取货
            for (Customer customer : collectCustomers) {
                long min = Long.MAX_VALUE;
                Route bestRoute = null;
                Individual bestIndividual = null;
                int routeIdx = -1, individualIdx = -1;
                GlobalData globalData = new GlobalData();

                // 全局变量中的driveTime、otherTime、breakTime清零
//                data.setDriveTime(0);
//                data.setBreakTime(0);
//                data.setOtherTime(0);

                for (Individual individual : individuals) {
                    for (Route r : individual.getRoutes()) {
                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerBySeparation(customer, data, true);
                        System.out.println("Can add this customer?? " + canAdd.getFirst());
                        if (canAdd != null && canAdd.getFirst()) {
                            long cost = canAdd.getSecond().getCurTime();
                            System.out.println("The cost of this route::: " + cost);
                            System.out.println("min cost::: " + min);
                            if (cost < min) {
                                min = cost;
                                bestRoute = r;
                                bestIndividual = individual;
//                                individualIdx = i;
//                                routeIdx = j;
                                individualIdx = individuals.indexOf(individual);
//                                routeIdx = individual.getRoutes().indexOf(bestRoute);
                                routeIdx = (int) bestRoute.getId();
                                System.out.println("取货cost<min内的routeIdx：" + routeIdx + "; \nroute: " + bestRoute);
                                globalData = canAdd.getSecond();
                            }
                        }
                    }
                }

                if (bestRoute != null) {
                    long routeId = bestRoute.addACustomer(customer, data, globalData, true, "separation");
                    if (routeId == -1) {
                        unassignedCustomer.add(customer);
                    } else {
                        // 构造DTO加入列表
                        SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData, false);
//                                sucCustomers.put(routeIdx, dto);
                        tmp.put((int) bestRoute.getId(), dto);
                        // 更新fitness等属性
                        double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
                        bestIndividual.setFitness(newFitness);
                        List<Route> rawRoutes = bestIndividual.getRoutes();
                        rawRoutes.set(routeIdx, bestRoute);
                        bestIndividual.setRoutes(rawRoutes);
                        individuals.set(individualIdx, bestIndividual);
                        // 当前请求加入map
                        Map<Long, Customer> pairMap = bestRoute.getPairMap();
                        pairMap.put(customer.getId(), customer);
                        bestRoute.setPairMap(pairMap);
                    }
                } else {
                    unassignedCustomer.add(customer);
                }
            }

            // 3.对所有订单按照送货顺序进行排序
            List<Customer> deliverCustomers = new ArrayList<>(customerList);
            deliverCustomers.sort((o1, o2) -> Long.compare(o1.getDeliverSite().getId(), o2.getDeliverSite().getId()));
//            System.out.println("Sorted deliverCustomers: " + deliverCustomers);

            // 4.遍历送货
            for (Customer customer : deliverCustomers) {
//                if (customer.isDelivered()) continue; // 若已被分配，则跳过
                long min = Long.MAX_VALUE;
                Route bestRoute = null;
                Individual bestIndividual = null;
                int routeIdx = -1, individualIdx = -1;
                GlobalData globalData = new GlobalData();

                for (Individual individual : individuals) {
                    for (Route r : individual.getRoutes()) {
                        // 若当前路线已对当前遍历的请求进行取货,则对当前请求进行送货
                        if (r.getPairMap().containsKey(customer.getId())) {

                            Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerBySeparation(customer, data, false);
                            if (canAdd != null && canAdd.getFirst()) {
                                long cost = canAdd.getSecond().getCurTime();
                                System.out.println("The cost of this route::！ " + cost);
                                System.out.println("min cost::！ " + min);
                                if (cost < min) {
                                    min = cost;
                                    bestRoute = r;
                                    bestIndividual = individual;
//                                individualIdx = i;
//                                routeIdx = j;
                                    individualIdx = individuals.indexOf(individual);
//                                routeIdx = individual.getRoutes().indexOf(bestRoute);
                                    routeIdx = (int) bestRoute.getId();
                                    System.out.println("送货cost<min内的routeIdx：" + routeIdx + "; \nroute: " + bestRoute);
                                    globalData = canAdd.getSecond();
                                }
                            }
                        }
                    }
                }

                if (bestRoute != null) {
                    long routeId = bestRoute.addACustomer(customer, data, globalData, false, "separation");
                    if (routeId == -1) {
                        unassignedCustomer.add(customer);
                    } else {
                        // 构造DTO加入列表
                        SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData, false);
//                                sucCustomers.put(routeIdx, dto);
                        tmp.put((int) bestRoute.getId(), dto);
                        // 更新
                        double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
                        bestIndividual.setFitness(newFitness);
                        List<Route> rawRoutes = bestIndividual.getRoutes();
                        rawRoutes.set(routeIdx, bestRoute);
                        bestIndividual.setRoutes(rawRoutes);
                        individuals.set(individualIdx, bestIndividual);
                    }
                } else {
                    unassignedCustomer.add(customer);
                }
            }
        } else { // 以订单为单位,遍历所有请求Customer，为当前请求Customer分配一辆新车Vehicle，并为该车临时初始化一条新路线Route
            // 1. 遍历每个个体的路线
            // TODO 1206 1107注释👇随机分配实现前，可运行的代码
//            for (Individual individual : individuals) {
//                for (Route route : individual.getRoutes()) {
//                    long min = Long.MAX_VALUE; // 设为最大值
//                    Customer bestCustomer = null;
//                    GlobalData globalData = new GlobalData();
////                    if (customerList.isEmpty() || customerList.size()==0) break;
//                    // 遍历请求列表
//                    for (Customer customer : customerList) {
//                        if (customer != null && customer.isDelivered()) continue; // 若已送货，则跳过当前订单
//
//                        Tuple<Boolean, GlobalData> canAdd = route.canAddACustomerByCombination(customer, data); // 在Route中检查是否满足约束，并计算成本
////                        System.out.println("Can add this customer? " + canAdd.getFirst());
//                        boolean flag = canAdd.getFirst();
//                        if (canAdd != null && canAdd.getFirst()) {
//                            // 能分配
//                            long cost = canAdd.getSecond().getCurTime();
////                            System.out.println("The cost of this route: " + cost);
//                            if (cost < min) {
//                                // 更新最小值
//                                min = cost;
//                                bestCustomer = customer;
//                                globalData = canAdd.getSecond();
//                                // 标记为可以分配
//                                flag = true;
//                            }
//                        }
//                        // 找到能插入的请求就退出循环
//                        if (flag) break;
//                    }
//
//                    // 找到成本最低的分配路线
//                    if (bestCustomer != null) {
//                        long routeId = route.addACustomer(bestCustomer, data, globalData, false); // 这里设为false
//                        if (routeId == -1) {
//                            unassignedCustomer.add(bestCustomer);
//                        } else {
//                            // 1. 更新
//                            double newFitness = route.getOverallDuration() / (1000 * 60 * 60.0);
//                            individual.setFitness(newFitness); // fitness
//                            List<Route> rawRoutes = individual.getRoutes();
//                            rawRoutes.set((int) routeId, route);
//                            individual.setRoutes(rawRoutes); // 更新route
//                            individuals.set(individuals.indexOf(individual), individual); // 更新individual
//                            // 2. 构造DTO加入列表
//                            SucCustomerDto dto = new SucCustomerDto(bestCustomer, individual, route, individuals.indexOf(individual), (int) routeId, globalData, false);
//                            tmp.put((int) route.getId(), dto);
//                            // 3. 标记为已送货
//                            bestCustomer.setDelivered(true);
////                            // 4. 执行返程  TODO  1206 0109临时注释
////                            addReturnRoute(tmp, data, individuals);
//                        }
//                    } else {
//                        System.out.println("这里吗");
////                        unassignedCustomer.add(bestCustomer);
//                    }
//                }
//            }
            // TODO 1206 1107注释👆随机分配实现前，可运行的代码
            double sumCost = 0.0;
            double sumDuration = 0.0;

            for (Customer customer : customerList) {
                long min = Long.MAX_VALUE;
                Route bestRoute = null;
                Individual bestIndividual = null;
                int routeIdx = -1, individualIdx = -1;
                GlobalData globalData = new GlobalData();
                boolean flag = false; // 判断是否成功分配

                for (Individual individual : individuals) {
                    for (Route r : individual.getRoutes()) {
                        if (customer != null && customer.isDelivered()) continue; // 若已被分配，则跳过
                        // 判断能否添加当前请求
                        Tuple<Boolean, GlobalData> canAdd = r.canAddACustomerByCombination(customer, data);
//                        System.out.println("cus: " + customer.getId() + " " + canAdd.getFirst());
                        if (canAdd != null && canAdd.getFirst()) {
                            long cost = canAdd.getSecond().getCurTime();
                            System.out.println("The cost of this route::！ " + cost + "; " + cost / (1000 * 60.0) + " min");
                            System.out.println("min cost::！ " + min);
                            if (cost < min) {
                                min = cost;
                                bestRoute = r;
                                bestIndividual = individual;
//                                individualIdx = i;
//                                routeIdx = j;
                                individualIdx = individuals.indexOf(individual);
//                                routeIdx = individual.getRoutes().indexOf(bestRoute);
                                routeIdx = (int) bestRoute.getId();
                                System.out.println("送货cost<min内的routeIdx：" + routeIdx + "; \nroute: " + bestRoute);
                                globalData = canAdd.getSecond();
                                // 成功分配
                                flag = true;

                                sumCost += cost;
                                sumDuration += bestRoute.getOverallDuration();
                            }
                        } else {
                        }
//                        }
                    }
                }

                if (!flag) { // 不能成功分配，加入unassignedCustomer
//                    System.out.println("不能分配：" + customer.getId());
                    unassignedCustomer.add(customer);
                    continue;
                }

                if (bestRoute != null) {
                    long routeId = bestRoute.addACustomer(customer, data, globalData, false, "combination");
                    if (routeId == -1) {
                        unassignedCustomer.add(customer);
                    } else {
                        // 构造DTO加入列表
                        SucCustomerDto dto = new SucCustomerDto(customer, bestIndividual, bestRoute, individualIdx, routeIdx, globalData, false);
//                                sucCustomers.put(routeIdx, dto);
                        tmp.put((int) bestRoute.getId(), dto);
                        // 更新
                        double newFitness = bestRoute.getOverallDuration() / (1000 * 60 * 60.0);
                        bestIndividual.setFitness(newFitness);
                        List<Route> rawRoutes = bestIndividual.getRoutes();
                        rawRoutes.set(routeIdx, bestRoute);
                        bestIndividual.setRoutes(rawRoutes);
                        individuals.set(individualIdx, bestIndividual);
                    }
                } else {
                    System.out.println("????");
//                    unassignedCustomer.add(customer);
                }
            }

            System.out.println("Final cost: " + (sumCost / (1000.0 * 60 * 60)) + " min"); // test
            System.out.println("Final duration: " + sumDuration + " min"); // test
//            // 执行每条路线的返程  TODO  1206 0132
//            System.out.println("SuC: " + sucCustomers.keySet());
//            System.out.println("SS: "+sucCustomers.get(45).getRoute().getCustomers().toString());
//            System.out.println("S: "+sucCustomers.get(45).isReturned());
        }
        // 赋值给sucCustomers，并返回
        sucCustomers = tmp;
        res = new InnerTuple<>(individuals, sucCustomers);
        return res;
    }

    /**
     * remove time nodes related to deleted customers from the time list
     */
    protected static List<Time> removeRelatedTime(List<Time> sourceList, int customerId) {
        List<Time> res = new ArrayList<>();
        if (sourceList != null) {
            for (Time time : sourceList) {
                if (time.getCustomerId() != customerId) { // 被删除的请求对应的Time不加入新列表
                    res.add(time);
                }
            }
        }
        return res; // 返回更新后的列表
    }

    protected static List<Individual> initialIndividualsByLargeData(List<Individual> individuals, int nPop,
                                                                    int len, Random random, PreProcessData data) {
        for (int i = 0; i < nPop; i++) {
            List<Route> routeList = new ArrayList<>();
            System.out.println("len: " + len);
            Collections.shuffle(data.getVehicleList(), random); // 随机打乱，指定seed
            for (int j = 0; j < len; j++) {
                // 1. 将j作为获取车辆的下标(每次先获取第一辆车作为要创建的新路线的用车)
                Vehicle vehicle = data.getVehicleList().get(j);

                // 2. 创建一条新路线(请求(Customer)列表为空)，随机分配一辆车
                Route newRoute = new Route(vehicle,
                        new ArrayList<>(), new Date(), new Date(),
                        0, 0, 0, 0, j, new HashMap<>());
                routeList.add(newRoute);
            }
            // 3. 创建个体，配置路线列表等属性(fitness初始化为0)
            Individual individual = new Individual(routeList, 0.0);
            individuals.add(individual);
        }
        return individuals;
    }

    protected static List<Individual> initialIndividualsBySmallData(List<Individual> individuals, int nPop,
                                                                    int len, Random random, PreProcessData data) {
        for (int i = 0; i < nPop; i++) {
            List<Route> routeList = new ArrayList<>();
            System.out.println("len: " + len);
            int randSize = random.nextInt(len); // 随机获取生成的路线总数，[0, len)
//            System.out.println("random.nextInt(len): " + randSize);
//            if (randSize == 0 && len == 1) { // 若只有一辆车，则设置为1
//                randSize = 1;
//            }

//            for (int j = 0; j < randSize; j++) { // TODO  1204 1346修改
            for (int j = 0; j < 1; j++) {
                // 1)将j作为获取车辆的下标(每次先获取第一辆车作为要创建的新路线的用车)
                Vehicle vehicle = data.getVehicleList().get(j);

                // 2)创建一条新路线(请求(Customer)列表为空)，分配车辆
                Route newRoute = new Route(vehicle,
                        new ArrayList<>(), new Date(), new Date(),
                        0, 0, 0, 0, j, new HashMap<>());
                routeList.add(newRoute);
            }
            // 2. 创建个体，配置路线列表等属性(fitness初始化为0)
            Individual individual = new Individual(routeList, 0.0);
            individuals.add(individual);
        }
        return individuals;
    }

    protected static void addReturnRoute(Map<Integer, SucCustomerDto> sucCustomers, PreProcessData data, List<Individual> individuals) {
        // 遍历每个路线,分别添加返程
        double sum = 0.0;

        for (Entry<Integer, SucCustomerDto> entry : sucCustomers.entrySet()) {
            // 1. 从dto获取数据
            SucCustomerDto dto = entry.getValue();
            Route route = dto.getRoute();
            Individual individual = dto.getIndividual();
            int routeIdx = dto.getRouteIdx();
            int individualIdx = dto.getIndividualIdx();
            Customer customer = dto.getCustomer();
            GlobalData globalData = dto.getGlobalData();
            long dailyDriveTime = globalData.getDailyDriveTime();
//            System.out.println("DTO: "+dto);
//            System.out.println("entry: "+entry.toString());
//            System.out.println("route: "+route.toString());
//            System.out.println("返程 routeIdx: " + routeIdx);

            long overallDuration = route.getOverallDuration(); // 获取每个路线在返程前的总时长
            long vehicleStartTime = dto.getRoute().getVehicle().getStartTime().getTime(); // 获取路线的发车时间
//            System.out.println("11: " + new Date(overallDuration));
//            System.out.println("22: " + dto.getRoute().getVehicle().getStartTime());

            // 2. 若 有订单且未处理过返回仓库，则尝试返程
            List<Customer> customerList = route.getCustomers();
            if (!customerList.isEmpty() && !dto.isReturned()) {
                boolean canReturn; // 是否能返程
                try {
                    canReturn = route.returnToDepot(customer, data,
                            dailyDriveTime,
                            overallDuration + vehicleStartTime); // input daily overall drive time
                } catch (RuntimeException e) {
                    continue;
                }
                if (canReturn) {
                    // 1)更新route
                    List<Route> rawRoutes = individual.getRoutes();
                    rawRoutes.set(routeIdx, route);
                    individual.setRoutes(rawRoutes);

                    // 2)更新individual
                    individuals.set(individualIdx, individual);

                    // 3)计算每个路线的overall duration time
//                    System.out.println("CAR: " + vehicleStartTime + " ; " + new Date(vehicleStartTime));
//                    System.out.println("ROUTE: " + route.getOverallDuration() + " ; " + new Date(route.getOverallDuration()));
                    sum += (route.getOverallDuration() - vehicleStartTime);

                    // 4)更新dto是否处理返回仓库的标记
                    dto.setReturned(true);
                }
            }
        }
        // 3. 更新fitness
        individuals.get(0).setFitness(sum / (1000.0 * 60 * 60)); // units: h
        System.out.println("总的2： " + individuals.get(0).getFitness());
    }

    // 全局data，用于重新分配能成功分配的请求使用
    protected static PreProcessData tmpData = new PreProcessData();

    /**
     * Initialize the population with the Greedy Algorithm
     */
    protected static Population initializePopulationWithGreedy(PreProcessData data, int nPop) {
        // 1. initialization
        Population population = new Population(new ArrayList<>(), nPop, 0.0,
                new ArrayList<>(), new ArrayList<>());
        List<Individual> individuals = new ArrayList<>(nPop); // maximum number of individuals are 200 TODO 种群的个体数最多200个
        Random random = data.getRandom(); // use studentID as the seed for the random number generator TODO 获取随机数种子为studentID的随机数生成类
        int len = data.getVehicleList().size(); // the number of vehicles

        // 2. generate nPop number of individuals
        if (len >= data.getCustomerList().size() && len >= 50) { // large dataset, request-based TODO 大数据量，按照请求customer为单位
            individuals = initialIndividualsByLargeData(individuals, nPop, len, random, data);
        } else { // small dataset, collect-deliver-order-based TODO 小数据量，按照取货送货先后进行
            individuals = initialIndividualsBySmallData(individuals, nPop, len, random, data);
        }
//        System.out.println("initial individuals: " + individuals);

        // 3. using random number to determine whether to collect the goods first and then deliver them in batches,
        //    or in units of orders  TODO  用 随机数 确定是先取货后送货，还是以订单为单位
        Map<Integer, SucCustomerDto> sucCustomers = new HashMap<>(); // store successful added customers, key=routeId, value=DTO
        boolean method = random.nextBoolean();

//        // 3. 先获取70%的customer，进行分配（大数据量测试集）
//        int initialSize = (int) Math.ceil(data.getCustomerList().size() * 0.7);
//        List<Customer> initialCustomerList=new ArrayList<>(); // 初始请求列表
//        List<Customer> extraCustomerList=new ArrayList<>(); // 其他请求列表
//        for (int i=0;i<initialSize;i++){
//            initialCustomerList.add(data.getCustomerList().get(i));
//        }
//        for (int i=initialSize;i<data.getCustomerList().size();i++){
//            extraCustomerList.add(data.getCustomerList().get(i));
//        }
////        System.out.println("initial size: "+initialCustomerList.size()+"\n content: "+initialCustomerList.toString());
////        System.out.println("extra size: "+extraCustomerList.size()+"\n content: "+extraCustomerList.toString());

        // 4. for each customer to be added, check all feasible insertion points for all existing routes in the current solution
        //    consider the priority, capacity and time constraints
        // TODO  对于每个要插入的请求，检查当前（部分）解决方案中所有现有路线的所有可行插入点
        //      测试路线中取货和送货节点的所有可能插入位置，同时考虑优先级、容量和时间约束
        Tuple<List<Individual>, Map<Integer, SucCustomerDto>> res;
        if (len >= data.getCustomerList().size() && len >= 50) { // large dataset
            // 1)assign customers
            res = assignCustomers(individuals, data, true, sucCustomers, data.getCustomerList());
            if (res != null) {
                individuals = res.getFirst(); // individual list
                sucCustomers = res.getSecond(); // successful customers
            }

//            // 2)更新种群的总fitness
//            double sum = 0.0;
//            for (Individual individual : individuals) {
//                sum += individual.getFitness();
//            }
//            population.setOverallFitness(sum / (60.0 * 60 * 1000));
//
//            // 3)给种群设置个体列表
//            population.setIndividuals(individuals);
//            System.out.println("Overall fitness is :  " + population.getOverallFitness() + " hours");
//            return population;
        } else { // small dataset
            res = assignCustomers(individuals, data, method, sucCustomers, data.getCustomerList());
            if (res != null) {
                individuals = res.getFirst(); // individual list
                sucCustomers = res.getSecond(); // successful customers
            }
        }
//        System.out.println("individuals: " + individuals);
//        System.out.println("分配后获得的sucCustomers: " + sucCustomers);
//        System.out.println("unassigned: " + unassignedCustomer);

        // 5. process 'unassignedCustomer' and assign the unassigned customers to other routes
        //    if there is only one vehicle, add a new one
        // TODO  处理unassignedCustomer，把unassigned 的车辆分配给其他车，如果只有一辆车，则新增一辆
        if (!unassignedCustomer.isEmpty()) {
            List<Customer> rewritableList = new CopyOnWriteArrayList<>(); // store customers to be reassigned later
            // TODO  用于重新分配成功分配的请求
            //       [写入report] ArrayList 的线程安全变体，其中所有可变操作（添加、设置等）都是通过复制底层数组来实现的
            List<Integer> routeIdxs = new ArrayList<>(); // store the index of routes that will be removed
            // TODO  存放要被移除的routeIdx

            // 1)delete the customer (delivery/pickup) corresponding to the request (pickup/delivery) that was not successfully assigned
            // TODO  删除未成功分配的请求（取货/送货）对应的请求（送货/取货）
            for (Customer unassigned : unassignedCustomer) {
                if (!sucCustomers.isEmpty()) { // there is a customer allocated TODO  有请求被成功分配
                    System.out.println("assignedCustomer: " + assignedCustomer.size() + "; " + assignedCustomer);
                    System.out.println("unassignedCustomer: " + unassignedCustomer.size() + "; " + unassignedCustomer);
                    System.out.println("sucCustomers: " + sucCustomers.size() + "; " + sucCustomers + "\n");

                    // Only the last customer would be added into the 'sucCustomers'
                    // find the collect/deliver jobs corresponding to current traverse customer from the 'sucCustomers',
                    // then remove those collect/deliver jobs that had been added
                    // TODO  目前只有最后一个顶点加入sucCustomers。尝试添加全局列表，把成功的加入，然后需要的话在下面代码中从列表中移除它
                    //       从sucCustomers找到该customer对应的取货/送货任务，然后移除该已经加入的取货/送货任务
                    for (Entry<Long, List<Customer>> entry : assignedCustomer.entrySet()) {
                        long routeIdx = entry.getKey(); // obtain the index of each route

                        // traverse the customer list to find all customers stored in the list
                        // TODO  遍历该route的所有已保存在列表中的请求
                        List<Customer> list = entry.getValue();
                        rewritableList = new CopyOnWriteArrayList<>(list);
                        for (Customer assigned : rewritableList) {
                            if (assigned.getId() == unassigned.getId()) {
                                // get the subscript of the requested customer to be removed in the 'assigned'
                                // TODO  获取要删除的请求customer在assigned中的下标
                                int removedIdx = rewritableList.indexOf(assigned);
//                                System.out.println("removedCustomerIdx: " + removedIdx);

                                // remove assigned from the assignedCustomer
                                rewritableList.remove(assigned);
//                                System.out.println("after remove: " + rewritableList.size() + "; " + rewritableList);

                                // if it will remove the last customer, the last one after removing will become the DTO
                                // replacing the DTO of the current route in the 'sucCustomers' list
                                // TODO  若删除的是最后一个请求，则将assigned经过删除元素后的最后一个，更新成sucCustomers列表中当前路线的DTO
                                SucCustomerDto dto = sucCustomers.get((int) routeIdx);
                                // if the DTO is not null, keep going
                                if (dto != null) {
//                                    System.out.println("removed DTO: " + dto);
                                    // 1. get the latest customer in 'SucCustomerDto'
                                    //    [get the latest job after removing]
                                    // TODO  获取SucCustomerDto中最新的的customer（获取remove操作后最新的最后一个job）
                                    Customer newLastCus = rewritableList.get(rewritableList.size() - 1);

                                    // 2. assign the updated route
                                    // TODO  更新SucCustomerDto中最新的的路线
                                    Route newRoute = dto.getRoute();
//                                    System.out.println("删除1newRoute: " + newRoute);
                                    List<Customer> customers = newRoute.getCustomers();
                                    // 2-1.remove the corresponding assigned customer TODO  将对应的已分配的请求删除
                                    customers.remove(assigned);
                                    // 2-2.update the customer list back to the route TODO  把该请求列表更新回到route中
                                    newRoute.setCustomers(customers);
                                    // 2-3.remove every time node related to removed customer TODO 删除newRoute中与已删除请求相关的Time
                                    List<Time> driveList = newRoute.getVehicle().getDriveTimeList();
                                    List<Time> breakList = newRoute.getVehicle().getBreakTimeList();
                                    List<Time> otherList = newRoute.getVehicle().getOtherTimeList();
                                    List<Time> delayList = newRoute.getVehicle().getDelayTimeList();
                                    List<Time> waitList = newRoute.getVehicle().getWaitTimeList();
                                    driveList = removeRelatedTime(driveList, removedIdx);
                                    breakList = removeRelatedTime(breakList, removedIdx);
                                    otherList = removeRelatedTime(otherList, removedIdx);
                                    delayList = removeRelatedTime(delayList, removedIdx);
                                    waitList = removeRelatedTime(waitList, removedIdx);
                                    newRoute.getVehicle().setDriveTimeList(driveList);
                                    newRoute.getVehicle().setBreakTimeList(breakList);
                                    newRoute.getVehicle().setOtherTimeList(otherList);
                                    newRoute.getVehicle().setDelayTimeList(delayList);
                                    newRoute.getVehicle().setWaitTimeList(waitList);
//                                    System.out.println("删除2newRoute: " + newRoute);

                                    // 3.get the latest individual in 'SucCustomerDto'
                                    Individual individual = dto.getIndividual();
//                                    System.out.println("删除所在个体： " + individual.toString());
                                    // 3-1.update the route of this individual TODO  更新该个体的路线
                                    List<Route> routes = individual.getRoutes();
//                                    System.out.println("删除所在路线： " + routes.toString());
                                    for (Route route : routes) {
                                        if (route.getId() == routeIdx) { // find the currently processed route, set the 'newRoute' TODO 找到当前处理的route，并更新回routes
                                            routes.set((int) routeIdx, newRoute);
                                        }
                                    }
                                    individual.setRoutes(routes);
//                                    System.out.println("删除所在路线2： " + routes.toString());

                                    // 4.construct a new dto
                                    SucCustomerDto newDto = new SucCustomerDto(newLastCus, individual,
                                            newRoute, dto.getIndividualIdx(), (int) routeIdx, dto.getGlobalData(), false);
//                                    sucCustomers.put(removedIdx, newDto);
                                    sucCustomers.put((int) routeIdx, newDto); // replace the old route with an index of 'routeIdx'

                                    // 5.update the routeIdx
                                    routeIdxs.add(dto.getRouteIdx());
//                                    System.out.println("新的sucCustomers：" + sucCustomers);
//                                    System.out.println("现在unassigned：" + unassignedCustomer);
                                }
                            }
                        }
//                        System.out.println("更新后的customerList： " + rewritableList);

                        // De-duplicate 'rewritableList'
                        List<Customer> deDuplicated = new CopyOnWriteArrayList<>();
                        for (Customer customer : rewritableList) {
                            if (!deDuplicated.contains(customer)) deDuplicated.add(customer);
                        }
                        rewritableList = deDuplicated;
//                        System.out.println("去重后的customerList： " + rewritableList);
                    }
                }
            }
//            System.out.println("现在车辆：" + data.getVehicleList().toString());

            // 2)initialize the individual list, reassign customers that had been successful added
            // TODO  初始化个体列表，重新分配成功分配的请求
            List<Route> routes = individuals.get(0).getRoutes(); // current size of the population is 1
            for (int idx : routeIdxs) {
                // initialize the route
                for (Route route : routes) {
                    if (idx == route.getId()) {
                        System.out.println("idx: " + idx);
                        System.out.println("routeId: " + route.getId());
                        route.setCustomers(new ArrayList<>());
                        route.setEndTime(new Date());
                        route.setStartTime(new Date());
                        route.setOverallBreak(0);
                        route.setOverallDistance(0);
                        route.setOverallDuration(0);
                        route.setOverallWeight(0);
                        // do not reset the vehicle and randN
                    }
                }
                individuals.get(0).setRoutes(routes);
                // initialize the list of successfully assigned customers TODO  初始化成功分配的请求列表
                sucCustomers = new HashMap<>();
                // initialize the global data 'data'
                data = PreProcessData.initialize(tmpData);
                for (int i = 0; i < data.getVehicleList().size(); i++) {
                    Vehicle vehicle = data.getVehicleList().get(i);
                    vehicle.setDriveTimeList(new ArrayList<>());
                    vehicle.setBreakTimeList(new ArrayList<>());
                    vehicle.setOtherTimeList(new ArrayList<>());
                    vehicle.setDelayTimeList(new ArrayList<>());
                    vehicle.setWaitTimeList(new ArrayList<>());
                    vehicle.setCurSiteId(vehicle.getStartSite());
                    data.getVehicleList().set(i, vehicle);
                }
                data = tmpData; // global data is only readable, except in the main function TODO 全局数据仅可读，主函数除外
                data.setOverallDeliverTime(new Date());
                // reinsert customers that assigned successfully(order by customers) TODO 重新插入能成功分配的请求(以订单为顺序)
                res = assignCustomers(individuals, data, true, sucCustomers, rewritableList); // the third parameter means the control of different allocation modes
                // TODO 第三个参数表示控制不同的分配方式
                if (res != null) {
                    individuals = res.getFirst(); // individual list
                    sucCustomers = res.getSecond(); // successful customers
                }
            }
//            System.out.println("individuals3: " + individuals);
//            System.out.println("susCustomers3: " + sucCustomers);
        }

        // 6. add a return route for each successful allocated route
        if (!sucCustomers.isEmpty()) {
            addReturnRoute(sucCustomers, data, individuals);
        }

        // 7. 将未分配的请求分配给其他车辆
        if (!unassignedCustomer.isEmpty()) {
            // 个体初始化时，只有一个路线，此时需要创建新路线
            for (Individual individual : individuals) {
                System.out.println("现有车辆数：" + individual.getRoutes().size());

                // 1)unassignedCustomer去重
                List<Customer> deDuplicated = new CopyOnWriteArrayList<>();
                for (Customer customer : unassignedCustomer) { // unassignedCustomer列表去重
                    if (!deDuplicated.contains(customer)) deDuplicated.add(customer);
                }
                System.out.println("deDuplicated: " + deDuplicated.size());

                // 2)遍历所有未成功分配的请求
                for (Customer unassigned : deDuplicated) {
                    // choose a free vehicle for the new route
                    Vehicle newV = new Vehicle();
                    for (Vehicle v : data.getVehicleList()) {
                        System.out.println("车辆:" + v.toString());
                        if (v.getDriveTimeList().isEmpty() && v.getBreakTimeList().isEmpty()
                                && v.getOtherTimeList().isEmpty() && v.getWaitTimeList().isEmpty()
                                && v.getDelayTimeList().isEmpty()) {
                            newV = v;
                            break;
                        }
                    }
                    System.out.println("new V: " + newV);
                    // TODO 1204 1347修改👇
//                    Route newR = null;
//                    for (Route r : individual.getRoutes()) {
//                        if (r.getVehicle() == newV) { // 空闲车辆已经在路线中，则使用该路线
//                            newR = r;
//                            break;
//                        }
//                    }
//                    if (newR == null) { // create a new route  TODO  12041042将下面移到了这里
                    // create a new route
                    Route newR = new Route(newV, new ArrayList<>(), new Date(), new Date(),
                            0, 0, 0, 0,
                            individual.getRoutes().get(0).getRandN() + 1, new HashMap<>());
                    // set new route
                    List<Route> newRoutes = individual.getRoutes();
                    newRoutes.add(newR);
                    System.out.println("new R: " + newR);
                    // set new routes to the individual
                    individual.setRoutes(newRoutes);
                    System.out.println("new I: " + individual);
                    // update the individual
                    individuals.set(individuals.indexOf(individual), individual);
//                    }

                    // 将未分配的请求分配给新路线
                    Tuple<List<Individual>, Map<Integer, SucCustomerDto>> res2 = //TODO  1204 1357改为了true
                            assignCustomers(individuals, data, true, sucCustomers,
                                    Collections.singletonList(unassigned));
                    System.out.println("res2的sucCustomers: " + res.getSecond().toString());
                    if (res2 != null) {
                        individuals = res2.getFirst(); // individual list
                        sucCustomers = res2.getSecond(); // successful customers
                    }
                    System.out.println("未分配的在分配新路线后：" + individuals.toString());

                    // 执行每条路线的返程
                    if (!sucCustomers.isEmpty()) {
                        addReturnRoute(sucCustomers, data, individuals);
                    }

                    // 移除unassignedCustomer中已分配的请求 TODO 1204 1408实现
                    System.out.println("前： " + unassignedCustomer.size() + "\n" + unassignedCustomer);
                    deDuplicated.remove(unassigned); // 移除当前处理的（之前未成功分配的）请求
                    System.out.println("后： " + deDuplicated);
                    //                    List<Customer> tmp = new CopyOnWriteArrayList<>();
//                    for (Customer customer : unassignedCustomer) {
//                        if (customer!=null){
//                            System.out.println("customer.id: "+customer.id);
//                            System.out.println("unassigned.id: "+unassigned.id);
//                            if (customer.getId() == unassigned.getId()) {
//                                tmp.add(customer);
//                            }
//                        }
//                    }
//                    unassignedCustomer = tmp;
//                    deDuplicated.clear();
//                    for (Customer customer : unassignedCustomer) { // unassignedCustomer列表去重
//                        if (!deDuplicated.contains(customer)) deDuplicated.add(customer);
//                    }
                }

//                // TODO 1204 1024添加
//                // 移除unassignedCustomer中已分配的请求
//                for (SucCustomerDto assigned : sucCustomers.values()) {
//                    unassignedCustomer.remove(assigned.getCustomer());
//                }
            }
            System.out.println("新增路线后：" + individuals.toString());

            // TODO 1204 0028 临时注释  处理多车辆👇
//            // TODO 1203 1327 临时注释  处理未分配的请求👇
//            // 4.将未分配的请求分配给新路线
//            Tuple<List<Individual>, Map<Integer, SucCustomerDto>> res2 =
//                    assignCustomers(individuals, data, method, sucCustomers, unassignedCustomer);
//            System.out.println("res2的sucCustomers: " + res.getSecond().toString());
//            if (res2 != null) {
//                individuals = res2.getFirst(); // individual list
//                sucCustomers = res2.getSecond(); // successful customers
//            }
//            System.out.println("未分配的在分配新路线后：" + individuals.toString());
////            unassignedCustomer.clear(); // 清空列表
//            // TODO 1203 1327 临时注释  处理未分配的请求👆
            // TODO 1204 0028 临时注释  处理多车辆👆
        }
//        System.out.println("sucCustomers：" + sucCustomers.toString());
        System.out.println("unassignedCustomer: " + unassignedCustomer.toString());

        // 8. 更新种群的总fitness
        double sum = 0.0;
        for (Individual individual : individuals) {
            sum += individual.getFitness();
        }
        population.setOverallFitness(sum);

        // 9. 给种群设置个体列表
        population.setIndividuals(individuals);
        System.out.println("Overall fitness:  " + population.getOverallFitness() + " hours");
//        int num=0;
//        for (Route route:population.getIndividuals().get(0).getRoutes()){
//            if (route.getCustomers()!=null && !route.getCustomers().isEmpty()) num++;
//        }
//        System.out.println("总共使用："+num+"个路线");
        return population;
    }

    /**
     * Selection part of the GGA
     */
    protected static List<Individual> selection(PreProcessData data, Population population) {
        List<Individual> res = new ArrayList<>();

        if (population != null) {
            List<Individual> individuals = population.getIndividuals();
            if (individuals != null) {
                // 1. 计算指针的间距
                double F = population.getOverallFitness();
                int N = population.getIndividuals().size();
                double P = F / (N * 1.0);
//                System.out.println("F: " + F);
//                System.out.println("N: " + N);
//                System.out.println("P: " + P);

                // 2. 随机生成起始指针的位置
                Random random = data.getRandom();
                if (random != null) {
                    int start = random.nextInt((int) Math.floor(P) + 1); // [0, P)   Math.floor()向下取值
//                    System.out.println("start: " + start);

                    // 3. 计算各指针的位置
                    List<Double> pointers = new ArrayList<>();
                    for (int i = 0; i < N; i++) {
                        pointers.add(start + i * P);
                    }
                    population.setPointers(pointers);
//                    System.out.println("pointers: " + population.getPointers().toString());

                    // 4. 计算每个个体的fitness的与其前面所有个体fitness的累积和
                    List<Double> sumFitness = new ArrayList<>();
                    sumFitness.add(population.getIndividuals().get(0).getFitness()); // 第一个元素
                    // 每个元素的值=当前值+前一个位置的累积和
                    for (int i = 1; i < N; i++) {
                        double sum = population.getIndividuals().get(i).getFitness() + sumFitness.get(i - 1);
                        sumFitness.add(sum);
                    }
//                    System.out.println("sumFitness: " + sumFitness.toString());

                    // 5. 由指针位置找到个体
                    List<Integer> selectedIndividual = new ArrayList<>();
                    int i = 0, j = 0; // 遍历的指针
                    while (i < N && j < N) {
//                        System.out.println("j=" + j + ", i=" + i + ", pointers.get(j)=" + pointers.get(j) + ", sumFitness.get(i)=" + sumFitness.get(i) + ", " + (pointers.get(j) <= sumFitness.get(i)));
                        if (pointers.get(j) <= sumFitness.get(i)) {
                            selectedIndividual.add(i); // 符合条件的位置i加入列表
                            j++; // 遍历下一个pointer
                        } else {
                            i++; // 遍历下一个真实fitness
                        }
                    }
//                    System.out.println("selectedIndividual: " + selectedIndividual.toString());

                    // 6. 取出fitness最大的两个个体
                    if (N < 2) { // 只有一个个体，则该个体同时是两个parent
                        Individual individual = population.getIndividuals().get(N - 1);
                        res.add(individual);
                        res.add(individual);
                    } else { // 选择fitness最高的两个
                        // 根据fitness对所有个体降序排序
                        population.getIndividuals().sort((i1, i2) -> Double.compare(i2.getFitness(), i1.getFitness()));
                        Individual first = population.getIndividuals().get(0);
                        Individual second = population.getIndividuals().get(1);
                        res.add(first);
                        res.add(second);
                    }
                }
            } else {
                System.out.println("There are not individuals in the population.");
            }
        } else {
            System.out.println("Population is empty.");
        }
        return res;
    }

    /**
     * Crossover part of the GGA
     */
    protected static List<Individual> crossover(PreProcessData data, Population population, Double pCross, List<Individual> parents) {
        List<Individual> res = new ArrayList<>();

        if (population != null) {
            int N = parents.get(1).getRoutes().size(); // 第一个parent的路线数
            if (parents != null && !parents.isEmpty()) {
                Random random = data.getRandom();
                if (random != null) {
//                    // 1. 随机生成两个交叉点
//                    int point1=0,point2=0;
//                    while(point1==point2){
//                        point1=random.nextInt(N);
//                         point2=random.nextInt(N);
//                    }
                }
            } else {
                System.out.println("Parents are empty.");
            }
        } else {
            System.out.println("Population is empty.");
        }
        return res;
    }

    /**
     * Implementation of the GGA algorithm
     */
    protected static Individual GGA(PreProcessData data) throws IllegalArgumentException {
        /**
         * Individual: arrange of each vehicle
         * Population: all vehicles composing a scheduling plan  TODO  所有车组成的一个调度规划
         * Fitness: the time needed to finish all the customers TODO  交付所有订单所需的总工作时间
         */
        // 1. parameters
        int nPop = 1; // size of population(number of individuals in the population)
        double pCross = 1.0; // crossover probability
        double pMut = 0.5; // mutation probability
        int nMax = 15000; // maximum number of individual  TODO
        int nMaxWithoutImprovement = 3000; // unimproved maximum number of individual  TODO
        Individual bestIndividual = new Individual(); // best individual

        // 2. initialize the population P
        Population population = initializePopulationWithGreedy(data, nPop);

        // 3. set the iteration termination condition
        int termination = 0;

        // 4. loop if it does not meet the termination condition
        while (termination <= nMaxWithoutImprovement) {
            // 1)selection: according to the fitness, select a pair of individuals x and y from P as parents
//            TODO  根据适应度值，从P选择一对个体x、y作为parents
//            List<Individual> parents = selection(data, population);
//            Individual x = new Individual(), y = new Individual();
//            if (!parents.isEmpty()) {
//                x = parents.get(0);
//                y = parents.get(1);
//            }
//
            // 2)crossover: apply the crossover operator to x and y with probability pCross to generate two offspring x', y'
            // TODO  以pCross的概率应用交叉算子到x和y生成两个子代x',y'
//            List<Individual> children = crossover(data, population, pCross, parents);
//
            // 3)mutation: apply the mutation operator to x' and y' with probability pMut to generate two modified offspring x'', y'' respectively
            // TODO  以pMut的概率应用变异算子到x'和y'，分别生成两个修改后的子代x'',y''
//            mutation();
//
            // 4)update the population P: insert x'' and y'' into P, removing the two worst individuals from P accordingly
            // TODO  将x''和y''插入P，并相应地从P中移除两个最差的个体
//            updatePopulation();

            // 5)update the value of the termination condition
            termination++;
        }

        // 5. get the best individual from the population
        bestIndividual = population.getIndividuals().get(0); // default: first individual
        return bestIndividual;
    }

    protected static String formatDate(double time, String type) throws ParseException {
        String res = "";
        Date date = new Date((long) time);

        if (type.equals("H'h'm'm'")) {
            int hours = (int) (time / (60 * 60 * 1000));
            int minutes = (int) ((time % (60 * 60 * 1000)) / (60 * 1000)); // remaining minutes

            String h = hours < 10 ? "" + hours : String.valueOf(hours);
            String m = minutes < 10 ? "" + minutes : String.valueOf(minutes);
            res += h + "h" + m + "m";
        } else if (type.equals("HH:mm")) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
            res = sdf.format(date);
        } else if (type.equals("extract")) { // extract hours and minutes
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            res += calendar.get(Calendar.HOUR_OF_DAY) + "h" + calendar.get(Calendar.MINUTE) + "m";
        }
        return res;
    }

    protected static void getOutput(Individual bestIndividual) throws ParseException {
        if (bestIndividual != null) {
//            System.out.println("Best individual: " + bestIndividual + ";\n route size: "
//                    + bestIndividual.getRoutes().size());
            System.out.println("Best individual's route size: " + bestIndividual.getRoutes().size());

            // 1. output the header of the table
            System.out.println("VehicleName,JobId,JourneyTime,ArrivalTime,WaitTime," +
                    "DelayTime,ServiceTime,DepartureTime,Break1Time,Break1Duration," +
                    "Break2Time,Break2Duration,Distance,SequenceNo");

            List<Long> cusId = new ArrayList<>();

            // 2. get time nodes from each route
            for (Route route : bestIndividual.getRoutes()) {
                StringBuilder output = new StringBuilder(); // final output

                // 1)get five time lists
                List<Time> driveTimeList = route.getVehicle().getDriveTimeList();
                List<Time> otherTimeList = route.getVehicle().getOtherTimeList();
                List<Time> breakTimeList = route.getVehicle().getBreakTimeList();
                List<Time> waitTimeList = route.getVehicle().getWaitTimeList();
                List<Time> delayTimeList = route.getVehicle().getDelayTimeList();

                // 2)integrate five lists into one
                List<Time> timeList = new ArrayList<>();
                timeList.addAll(driveTimeList);
                timeList.addAll(otherTimeList);
                timeList.addAll(breakTimeList);
                timeList.addAll(waitTimeList);
                timeList.addAll(delayTimeList);

                // 3)将timeList按照customerId分组（使用java8的stream API）
                Map<Long, List<Time>> group = timeList.stream()
                        .collect(Collectors.groupingBy(Time::getCustomerId));

                // 4)遍历每个分组，其中eachTimeList是当前路线的每个请求所在分组
                List<Time> processedTimeList = new ArrayList<>(); // 存放最终的数据
                group.forEach((customerId, eachTimeList) -> {
                    boolean isCollected = false, isDelivered = false; // 判断是否只有collect/deliver
                    for (Time time : eachTimeList) {
                        if (time.getJobId().contains("C-")) isCollected = true;
                        if (time.getJobId().contains("D-")) isDelivered = true;
                    }
                    if (isCollected && isDelivered) { // 某个customer在某个路线中同时被collect、deliver，表明该请求在该路线中成功分配
                        processedTimeList.addAll(eachTimeList); // 加入列表
                        cusId.add(customerId); // test
                    }
                });
                timeList = processedTimeList; // 赋值处理后的timeList
//                System.out.println("TimeList: " + timeList);

                // 5)sort the integrated list
                timeList.sort((o1, o2) -> Long.compare(o1.getId(), o2.getId()));

                // 6)get the id of vehicle
//                long vehicleId = route.getVehicle().getId();
                if (timeList.isEmpty()) continue; // jump to next route
                long vehicleId = timeList.get(0).getVehicleId();

                // 7)add the first line
//                Time firstTime = timeList.get(0); // first time entity
                output.append(vehicleId).append(","); // VehicleName
                output.append("Vehicle ").append(vehicleId).append(" start").append(","); // JobId
                output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime
                output.append(formatDate(route.getVehicle().getStartTime().getTime(), "HH:mm")).append(","); // ArrivalTime(Vehicle's start time)
                output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals zero
                output.append(formatDate(0, "H'h'm'm'")).append(","); // DelayTime equals zero
                output.append(formatDate(0, "H'h'm'm'")).append(","); // ServiceTime equals zero
                output.append(formatDate(route.getVehicle().getStartTime().getTime(), "HH:mm")).append(","); // DepartureTime equals to ArrivalTime
                output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                output.append(0).append(","); // Distance equals to zero
                output.append(1).append("\n"); // SequenceNo with \n

                // 8)output each item in the integrated list
                int seqR = 0; // control the sequenceNo of the tail
                int seqCD = 1; // control the sequenceNo of collect and deliver jobs
                for (int i = 0; i < timeList.size(); i++) {
                    Time curTime = timeList.get(i); // obtain the i-th Time node
                    System.out.println("time: " + curTime);

                    // typesetting by job type
                    if (curTime.getJobId().equals("return")) { // return to the depot
                        output.append(vehicleId).append(","); // VehicleName
                        output.append("Vehicle ").append(vehicleId).append(" end").append(","); // JobId
                        output.append(formatDate(curTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                        output.append(formatDate(curTime.getEnd().getTime(), "HH:mm")).append(","); // ArrivalTime (the time to return to the start site)
                        output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals zero
                        output.append(formatDate(0, "H'h'm'm'")).append(","); // DelayTime equals zero
                        output.append(formatDate(0, "H'h'm'm'")).append(","); // ServiceTime equals zero
                        output.append(formatDate(curTime.getEnd().getTime(), "extract")).append(","); // DepartureTime
                        // BreakTime
                        if (i > 2) {
                            Time firstTime = timeList.get(i - 2);
                            Time secondTime = timeList.get(i - 1);
                            if (firstTime != null && secondTime != null
                                    && firstTime.getJobId().equals("break") && secondTime.getJobId().equals("break")) { // two breaks
                                output.append(formatDate(firstTime.getStart().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(firstTime.getDuration(), "H'h'm'm'")).append(",")
                                        .append(formatDate(secondTime.getEnd().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(secondTime.getDuration(), "H'h'm'm'")).append(",");
                            } else { // only one break
                                if (firstTime != null && secondTime != null
                                        && !firstTime.getJobId().equals("break") && secondTime.getJobId().equals("break")) {
                                    output.append(formatDate(secondTime.getStart().getTime(), "HH:mm")).append(",")
                                            .append(formatDate(secondTime.getDuration(), "H'h'm'm'")).append(",")
                                            .append(",").append(",");
                                } else {
                                    output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                                }
                            }
                        } else {
                            output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                        }
                        // Distance
                        output.append(curTime.getDistance()).append(",");
                        output.append(seqR + 2); // SequenceNo, j+2 means existing the head and tail
                    } else if (!curTime.getJobId().equals("drive") && !curTime.getJobId().equals("wait")
                            && !curTime.getJobId().equals("delay") && !curTime.getJobId().equals("break")) {
                        // collect/deliver
                        seqCD++; // update the pointer
                        output.append(vehicleId).append(","); // VehicleName
                        output.append(curTime.getJobId()).append(","); // JobId

                        // JourneyTime
                        if (i > 2) {
                            Time firstTime = timeList.get(i - 3);
                            Time secondTime = timeList.get(i - 2);
                            Time thirdTime = timeList.get(i - 1);
                            if (firstTime != null && secondTime != null && thirdTime != null
                                    && firstTime.getJobId().equals("drive")
                                    && secondTime.getJobId().equals("break")
                                    && thirdTime.getJobId().equals("break")) { // two breaks after a drive
                                output.append(formatDate(firstTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                            } else if (secondTime != null && thirdTime != null
                                    && secondTime.getJobId().equals("drive")
                                    && thirdTime.getJobId().equals("break")) { // a break after a drive, before this collect/deliver job
                                output.append(formatDate(secondTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                            } else if (thirdTime != null && thirdTime.getJobId().equals("drive")) { // no break before this job
                                output.append(formatDate(thirdTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                            } else {
                                output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime equals zero
                            }
                        } else if (i > 1) {
                            Time firstTime = timeList.get(i - 2);
                            Time secondTime = timeList.get(i - 1);
                            if (firstTime != null && secondTime != null
                                    && firstTime.getJobId().equals("drive")
                                    && secondTime.getJobId().equals("break")) { // a break after a drive
                                output.append(formatDate(firstTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                            } else if (secondTime != null && secondTime.getJobId().equals("drive")) { // no break before this job
                                output.append(formatDate(secondTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                            } else {
                                output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime equals zero
                            }
                        } else if (i == 1) {
                            Time lastTime = timeList.get(i - 1);
                            if (lastTime != null && lastTime.getJobId().equals("drive")) {
                                output.append(formatDate(lastTime.getDuration(), "H'h'm'm'")).append(","); // JourneyTime
                            } else {
                                output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime equals zero
                            }
                        } else {
                            output.append(formatDate(0, "H'h'm'm'")).append(","); // JourneyTime equals zero
                        }

                        // ArrivalTime
                        output.append(formatDate(curTime.getStart().getTime(), "HH:mm")).append(","); // ArrivalTime

                        // wait time
                        if (i > 1) {
                            Time firstTime = timeList.get(i - 2);
                            Time secondTime = timeList.get(i - 1);
                            if (firstTime != null && secondTime != null
                                    && firstTime.getJobId().equals("wait") && secondTime.getJobId().equals("drive")) {
                                output.append(formatDate(firstTime.getDuration(), "H'h'm'm'")).append(","); // WaitTime
                            } else if (secondTime != null && secondTime.getJobId().equals("wait")) {
                                output.append(formatDate(secondTime.getDuration(), "H'h'm'm'")).append(","); // WaitTime
                            } else {
                                output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals to zero
                            }
                        } else if (i == 1) {
                            Time lastTime = timeList.get(i - 1);
                            if (lastTime != null && lastTime.getJobId().equals("wait")) {
                                output.append(formatDate(lastTime.getDuration(), "H'h'm'm'")).append(","); // WaitTime
                            } else {
                                output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals to zero
                            }
                        } else {
                            output.append(formatDate(0, "H'h'm'm'")).append(","); // WaitTime equals to zero
                        }

                        // DelayTime
                        output.append(formatDate(0, "H'h'm'm'")).append(",");
                        // ServiceTime
                        output.append(formatDate(curTime.getDuration(), "H'h'm'm'")).append(",");

                        // DepartureTime
//                        output.append(formatDate(curTime.getEnd().getTime(), "extract")).append(","); // DepartureTime
                        if (i > 1) {
                            Time firstBreak = timeList.get(i - 2);
                            Time secondBreak = timeList.get(i - 1);
                            double break1 = (firstBreak != null && firstBreak.getDuration() > 0) ? firstBreak.getDuration() : 0;
                            double break2 = (secondBreak != null && secondBreak.getDuration() > 0) ? secondBreak.getDuration() : 0;
                            if (firstBreak != null && secondBreak != null
                                    && firstBreak.getJobId().equals("break") && secondBreak.getJobId().equals("break")) { // two breaks
                                output.append(formatDate(curTime.getEnd().getTime() + break1 + break2, "extract")).append(","); // DepartureTime
                            } else if (firstBreak != null && secondBreak != null
                                    && !firstBreak.getJobId().equals("break") && secondBreak.getJobId().equals("break")) { // one break
                                output.append(formatDate(curTime.getEnd().getTime() + break2, "extract")).append(","); // DepartureTime
                            } else { // the first item should not be break time
                                output.append(formatDate(curTime.getEnd().getTime(), "extract")).append(","); // DepartureTime
                            }
                        } else if (i == 1) {
                            Time lastBreak = timeList.get(i - 1);
                            double breakTime = (lastBreak != null && lastBreak.getDuration() > 0) ? lastBreak.getDuration() : 0;
                            if (lastBreak != null && lastBreak.getJobId().equals("break")) {
                                output.append(formatDate(curTime.getEnd().getTime() + breakTime, "extract")).append(","); // DepartureTime
                            } else {
                                output.append(formatDate(curTime.getEnd().getTime(), "extract")).append(","); // DepartureTime
                            }
                        } else {
                            output.append(formatDate(curTime.getEnd().getTime(), "extract")).append(","); // DepartureTime
                        }

                        // breakTime
                        if (i > 1) {
                            Time firstBreak = timeList.get(i - 2);
                            Time secondBreak = timeList.get(i - 1);
                            if (firstBreak != null && secondBreak != null
                                    && firstBreak.getJobId().equals("break") && secondBreak.getJobId().equals("break")) { // two breaks
                                output.append(formatDate(firstBreak.getStart().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(firstBreak.getDuration(), "H'h'm'm'")).append(",")
                                        .append(formatDate(secondBreak.getEnd().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(secondBreak.getDuration(), "H'h'm'm'")).append(",");
                            } else { // only one break
                                if (firstBreak != null && secondBreak != null
                                        && !firstBreak.getJobId().equals("break") && secondBreak.getJobId().equals("break")) {
                                    output.append(formatDate(secondBreak.getStart().getTime(), "HH:mm")).append(",")
                                            .append(formatDate(secondBreak.getDuration(), "H'h'm'm'")).append(",")
                                            .append(",").append(",");
                                } else if (firstBreak != null && secondBreak != null
                                        && firstBreak.getJobId().equals("break") && !secondBreak.getJobId().equals("break")) {
                                    output.append(formatDate(firstBreak.getStart().getTime(), "HH:mm")).append(",")
                                            .append(formatDate(firstBreak.getDuration(), "H'h'm'm'")).append(",")
                                            .append(",").append(",");
                                } else {
                                    output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                                }
                            }
                        } else if (i == 1) {
                            Time lastBreak = timeList.get(i - 1);
                            if (lastBreak != null && lastBreak.getJobId().equals("break")) {
                                output.append(formatDate(lastBreak.getStart().getTime(), "HH:mm")).append(",")
                                        .append(formatDate(lastBreak.getDuration(), "H'h'm'm'")).append(",")
                                        .append(",").append(",");
                            } else {
                                output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                            }
                        } else {
                            output.append(",").append(",").append(",").append(","); // related BreakTime are zero
                        }

                        // Distance
                        if (i > 2) {
                            Time firstTime = timeList.get(i - 3);
                            Time secondTime = timeList.get(i - 2);
                            Time thirdTime = timeList.get(i - 1);
                            if (firstTime != null && secondTime != null && thirdTime != null
                                    && firstTime.getJobId().equals("drive")
                                    && secondTime.getJobId().equals("break")
                                    && thirdTime.getJobId().equals("break")) { // two breaks after a drive
                                output.append(firstTime.getDistance()).append(",");
                            } else if (firstTime != null && secondTime != null && thirdTime != null
                                    && secondTime.getJobId().equals("drive")
                                    && thirdTime.getJobId().equals("break")) { // a break after a drive, before this collect/deliver job
                                output.append(secondTime.getDistance()).append(",");
                            } else if (firstTime != null && secondTime != null && thirdTime != null
                                    && thirdTime.getJobId().equals("drive")) { // no break before this job
                                output.append(thirdTime.getDistance()).append(",");
                            } else {
                                output.append(0).append(",");
                            }
                        } else if (i == 2) {
                            Time firstTime = timeList.get(i - 2);
                            Time secondTime = timeList.get(i - 1);
                            if (firstTime != null && secondTime != null
                                    && firstTime.getJobId().equals("drive") && secondTime.getJobId().equals("break")) { // one break
                                output.append(firstTime.getDistance()).append(",");
                            } else if (firstTime != null && secondTime != null
                                    && secondTime.getJobId().equals("drive")) { // no break
                                output.append(secondTime.getDistance()).append(",");
                            } else { // the first item should not be break time
                                output.append(0).append(","); // Distance equals to zero
                            }
                        } else if (i == 1) {
                            Time lastTime = timeList.get(i - 1);
                            if (lastTime != null && lastTime.getJobId().equals("drive")) { // obtain the drive distance as current job's Distance
                                output.append(lastTime.getDistance()).append(","); // Distance
                            } else {
                                output.append(0).append(","); // Distance equals to zero
                            }
                        } else {
                            output.append(0).append(","); // Distance equals to zero
                        }

                        output.append(seqCD).append("\n"); // SequenceNo with \n
                        seqR++; // update the pointer
                    }
                }

                // 9. output the answer
                System.out.println(output);
            }

            // test
//            Collections.sort(cusId);
//            System.out.println("There are " + cusId.size() + " customers in the output, their customerId are: \n" + cusId.toString());
        } else {
            System.out.println("There is no best individual.");
        }
    }

    public static void main(String[] args) throws IllegalAccessException, ParseException {
        // (0. test) start recording the time used
        long start = System.currentTimeMillis();

        // 1. read input from the input.json
        JSONObject rawData = getInput();

        // 2. preprocess the data
        PreProcessData data = preProcessData(rawData);
        tmpData = data; // assign to the global data 'tmpData'

        // 3. try to use the GGA algorithm
        Individual bestIndividual = GGA(data);

        // 4. output the best individual
        getOutput(bestIndividual);

        // (5. test) output the time used
        long end = System.currentTimeMillis();
        System.out.println("Overall time consuming: " + (end - start) * 1.0 / 1000 + "s");
    }
}
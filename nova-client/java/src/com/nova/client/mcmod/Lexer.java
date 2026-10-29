package com.nova.client.mcmod;

import java.util.ArrayList;
import java.util.List;

/** McMod Script tokens. Newlines are tokens (statement ends) except inside () and []. */
final class Lexer {
    static final int EOF = 0, NL = 1, NUM = 2, STR = 3, ID = 4, OP = 5;

    static final class Tok {
        final int type, line;
        final String text;       // identifier / operator / raw string
        final double num;
        Tok(int type, String text, double num, int line) { this.type = type; this.text = text; this.num = num; this.line = line; }
        boolean is(String s) { return (type == OP || type == ID) && text.equals(s); }
        public String toString() {
            switch (type) {
                case EOF: return "конец файла";
                case NL: return "конец строки";
                case STR: return "строка";
                case NUM: return "число";
                default: return "'" + text + "'";
            }
        }
    }

    private static final String[] OPS = {"..", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
            "+", "-", "*", "/", "%", "<", ">", "=", "!", "(", ")", "[", "]", "{", "}", ",", ".", ":", ";", "?"};

    static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<Tok>();
        int i = 0, n = src.length(), line = 1;
        StringBuilder nest = new StringBuilder();     // open brackets: '(' '[' hide newlines, '{' shows them
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                if (nest.length() == 0 || nest.charAt(nest.length() - 1) == '{') out.add(new Tok(NL, "\n", 0, line));
                line++; i++; continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '﻿') { i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '#') {                                   // # comments too
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                if (e < 0) throw new ScriptError("незакрытый комментарий /*", line);
                for (int k = i; k < e; k++) if (src.charAt(k) == '\n') line++;
                i = e + 2; continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)) && !(i > 0 && src.charAt(i - 1) == '.'))) {
                int s = i;
                if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < n && Character.digit(src.charAt(i), 16) >= 0) i++;
                    out.add(new Tok(NUM, src.substring(s, i), Long.parseLong(src.substring(s + 2, i), 16), line));
                    continue;
                }
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                if (i + 1 < n && src.charAt(i) == '.' && Character.isDigit(src.charAt(i + 1))) {
                    i++;
                    while (i < n && Character.isDigit(src.charAt(i))) i++;
                }
                String t = src.substring(s, i).replace("_", "");
                out.add(new Tok(NUM, t, Double.parseDouble(t), line));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '$')) i++;
                out.add(new Tok(ID, src.substring(s, i), 0, line));
                continue;
            }
            if (c == '"' || c == '\'') {
                int startLine = line;
                StringBuilder b = new StringBuilder();
                i++;
                int braces = 0;                      // inside {expr}: quotes belong to the expression
                while (true) {
                    if (i >= n) throw new ScriptError("незакрытая строка", startLine);
                    char d = src.charAt(i);
                    if (braces > 0 && (d == '"' || d == '\'')) {
                        int e = i + 1;
                        while (e < n && src.charAt(e) != d && src.charAt(e) != '\n') { if (src.charAt(e) == '\\') e++; e++; }
                        b.append(src, i, Math.min(n, e + 1));
                        i = e + 1;
                        continue;
                    }
                    if (d == '{') braces++;
                    if (d == '}' && braces > 0) braces--;
                    if (d == c) { i++; break; }
                    if (d == '\n') line++;
                    if (d == '\\' && i + 1 < n) {
                        char e = src.charAt(i + 1);
                        // keep \{ as an escape marker for the interpolation parser
                        if (e == 'n') b.append('\n');
                        else if (e == 't') b.append('\t');
                        else if (e == '{') b.append("\u0001");
                        else if (e == '}') b.append("\u0002");
                        else b.append(e);
                        i += 2; continue;
                    }
                    b.append(d); i++;
                }
                out.add(new Tok(STR, b.toString(), 0, startLine));
                continue;
            }
            String op = null;
            for (String o : OPS) if (src.startsWith(o, i)) { op = o; break; }
            if (op == null) throw new ScriptError("непонятный символ '" + c + "'", line);
            if (op.equals("(") || op.equals("[") || op.equals("{")) nest.append(op.charAt(0));
            if ((op.equals(")") || op.equals("]") || op.equals("}")) && nest.length() > 0) nest.setLength(nest.length() - 1);
            out.add(new Tok(OP, op, 0, line));
            i += op.length();
        }
        out.add(new Tok(NL, "\n", 0, line));
        out.add(new Tok(EOF, "", 0, line));
        return out;
    }
}

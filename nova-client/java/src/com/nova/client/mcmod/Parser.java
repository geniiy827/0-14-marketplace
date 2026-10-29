package com.nova.client.mcmod;

import java.util.ArrayList;
import java.util.List;

import com.nova.client.mcmod.Ast.*;
import com.nova.client.mcmod.Lexer.Tok;

/**
 * McMod Script parser.
 *
 * top level:  on world.join(e) { }      every 20 ticks { }   every 2 seconds { }
 *             fn name(a, b = 1) { }     override chat.send(text) { original(text) }
 *             item name { key: value }  (declarations)      any statement
 * statements: var x = 1   x += 2   if c { } else if c { } else { }   while c { }
 *             for i in 1..10 { }   for x in list { }   return x   break   continue
 * expressions: or/||  and/&&  not/!  == != < > <= >=  ..  + -  * / %  -x  a ? b : c
 *             f(x)  a.b  a[i]  [1, 2]  {k: v}  "text {expr}"  fn(x) { }
 */
final class Parser {
    private final List<Tok> t;
    private int p;

    private Parser(List<Tok> toks) { t = toks; }

    static Program parse(String src) {
        Parser ps = new Parser(Lexer.lex(src));
        return ps.program();
    }

    // ------------------------------------------------------------------ helpers
    private Tok peek() { return t.get(p); }
    private Tok peek(int k) { return t.get(Math.min(p + k, t.size() - 1)); }
    private Tok next() { return t.get(p++); }
    private boolean at(String s) { return peek().is(s); }
    private boolean accept(String s) { if (at(s)) { p++; return true; } return false; }
    private void skipNl() { while (peek().type == Lexer.NL || at(";")) p++; }

    private ScriptError err(String msg) { return new ScriptError(msg, peek().line); }

    private Tok expect(String s) {
        if (!at(s)) throw err("ожидалось '" + s + "', а встретилось " + peek());
        return next();
    }

    private String ident(String what) {
        Tok k = peek();
        if (k.type != Lexer.ID) throw err("ожидалось " + what + ", а встретилось " + k);
        p++;
        return k.text;
    }

    private void endStmt() {
        Tok k = peek();
        if (k.type == Lexer.NL || k.is(";")) { p++; return; }
        if (k.is("}") || k.type == Lexer.EOF) return;
        throw err("лишнее " + k + " - каждая команда с новой строки");
    }

    // ------------------------------------------------------------------ program
    private Program program() {
        Program prog = new Program();
        skipNl();
        while (peek().type != Lexer.EOF) {
            Tok k = peek();
            int line = k.line;
            if (k.is("on") && peek(1).type == Lexer.ID) {
                next();
                String ev = dotted();
                String param = "e";
                if (accept("(")) {
                    if (!at(")")) param = ident("имя параметра события");
                    expect(")");
                }
                prog.events.add(new OnEvent(ev, param, block(), line));
            } else if (k.is("every")) {
                next();
                Node n = expr();
                String unit = ident("'ticks' или 'seconds'");
                boolean sec;
                if (unit.equals("tick") || unit.equals("ticks")) sec = false;
                else if (unit.equals("second") || unit.equals("seconds") || unit.equals("sec")) sec = true;
                else throw new ScriptError("после every N нужно ticks или seconds", line);
                prog.every.add(new Every(n, sec, block(), line));
            } else if (k.is("override")) {
                next();
                String ns = ident("пространство имён (chat, world ...)");
                expect(".");
                String name = ident("имя функции");
                Lambda fn = lambdaRest(ns + "." + name, line);
                prog.overrides.add(new OverrideDecl(ns, name, fn));
            } else if (k.is("fn") && peek(1).type == Lexer.ID) {
                next();
                String name = ident("имя функции");
                Lambda fn = lambdaRest(name, line);
                prog.init.add(new Var(line, name, fn));
            } else if ((k.is("item") || k.is("block") || k.is("recipe") || k.is("food")) && peek(1).type == Lexer.ID
                    && peek(2).is("{")) {
                next();
                String name = ident("имя");
                prog.decls.add(new Decl(k.text, name, mapLit(), line));
            } else {
                prog.init.add(statement());
            }
            skipNl();
        }
        return prog;
    }

    private String dotted() {
        StringBuilder b = new StringBuilder(ident("имя"));
        while (accept(".")) b.append('.').append(ident("имя"));
        return b.toString();
    }

    private Lambda lambdaRest(String name, int line) {
        expect("(");
        List<String> ps = new ArrayList<String>();
        List<Node> defs = new ArrayList<Node>();
        if (!at(")")) {
            do {
                ps.add(ident("имя параметра"));
                defs.add(accept("=") ? expr() : null);
            } while (accept(","));
        }
        expect(")");
        return new Lambda(line, name, ps.toArray(new String[0]), defs.toArray(new Node[0]), block());
    }

    private Block block() {
        int line = peek().line;
        while (peek().type == Lexer.NL) p++;          // "{" may be on the next line
        expect("{");
        List<Node> body = new ArrayList<Node>();
        skipNl();
        while (!at("}")) {
            if (peek().type == Lexer.EOF) throw new ScriptError("не хватает '}' (блок открыт на строке " + line + ")", peek().line);
            body.add(statement());
            skipNl();
        }
        expect("}");
        return new Block(line, body.toArray(new Node[0]));
    }

    // ------------------------------------------------------------------ statements
    private Node statement() {
        Tok k = peek();
        int line = k.line;
        if (k.type == Lexer.ID) {
            switch (k.text) {
                case "var": case "let": {
                    next();
                    String n = ident("имя переменной");
                    Node init = accept("=") ? expr() : null;
                    endStmt();
                    return new Var(line, n, init);
                }
                case "if": next(); return ifRest(line);
                case "while": {
                    next();
                    Node c = expr();
                    return new While(line, c, block());
                }
                case "for": {
                    next();
                    String v = ident("имя переменной цикла");
                    expect("in");
                    Node src = expr();
                    return new For(line, v, src, block());
                }
                case "return": {
                    next();
                    Node v = null;
                    if (peek().type != Lexer.NL && !at("}") && !at(";")) v = expr();
                    endStmt();
                    return new Ret(line, v);
                }
                case "break": next(); endStmt(); return new Jump(line, true);
                case "continue": next(); endStmt(); return new Jump(line, false);
                case "fn":
                    if (peek(1).type == Lexer.ID) {
                        next();
                        String name = ident("имя функции");
                        return new Var(line, name, lambdaRest(name, line));
                    }
                    break;
                default:
            }
        }
        Node e = expr();
        Tok o = peek();
        if (o.type == Lexer.OP && (o.text.equals("=") || o.text.equals("+=") || o.text.equals("-=")
                || o.text.equals("*=") || o.text.equals("/=") || o.text.equals("%="))) {
            next();
            Node v = expr();
            endStmt();
            return new Assign(line, e, o.text, v);
        }
        endStmt();
        return new ExprStmt(line, e);
    }

    private Node ifRest(int line) {
        Node c = expr();
        Block then = block();
        int save = p;
        while (peek().type == Lexer.NL) p++;           // "} \n else {" is fine too
        if (accept("else")) {
            if (at("if")) { int l = next().line; return new If(line, c, then, ifRest(l)); }
            return new If(line, c, then, block());
        }
        p = save;
        return new If(line, c, then, null);
    }

    // ------------------------------------------------------------------ expressions
    Node expr() {
        Node c = or();
        if (at("?")) {
            int line = next().line;
            Node a = expr();
            expect(":");
            Node b = expr();
            return new Cond(line, c, a, b);
        }
        return c;
    }

    private Node or() {
        Node a = and();
        while (at("or") || at("||")) { int l = next().line; skipNlInside(); a = new Binary(l, "or", a, and()); }
        return a;
    }

    private Node and() {
        Node a = not();
        while (at("and") || at("&&")) { int l = next().line; skipNlInside(); a = new Binary(l, "and", a, not()); }
        return a;
    }

    private Node not() {
        if (at("not") || at("!")) { int l = next().line; return new Unary(l, "!", not()); }
        return cmp();
    }

    private Node cmp() {
        Node a = range();
        while (at("==") || at("!=") || at("<") || at(">") || at("<=") || at(">=")) {
            Tok o = next();
            skipNlInside();
            a = new Binary(o.line, o.text, a, range());
        }
        return a;
    }

    private Node range() {
        Node a = add();
        if (at("..")) { int l = next().line; a = new Binary(l, "..", a, add()); }
        return a;
    }

    private Node add() {
        Node a = mul();
        while (at("+") || at("-")) { Tok o = next(); skipNlInside(); a = new Binary(o.line, o.text, a, mul()); }
        return a;
    }

    private Node mul() {
        Node a = unary();
        while (at("*") || at("/") || at("%")) { Tok o = next(); skipNlInside(); a = new Binary(o.line, o.text, a, unary()); }
        return a;
    }

    private void skipNlInside() { while (peek().type == Lexer.NL) p++; }

    private Node unary() {
        if (at("-")) { int l = next().line; return new Unary(l, "-", unary()); }
        if (at("+")) { next(); return unary(); }
        return postfix(primary());
    }

    private Node postfix(Node e) {
        while (true) {
            Tok k = peek();
            if (k.is("(")) {
                next();
                List<Node> args = new ArrayList<Node>();
                if (!at(")")) do { args.add(expr()); } while (accept(",") && !at(")"));
                expect(")");
                e = new Call(k.line, e, args.toArray(new Node[0]));
            } else if (k.is(".") ) {
                next();
                e = new Member(k.line, e, ident("имя после точки"));
            } else if (k.is("[")) {
                next();
                Node i = expr();
                expect("]");
                e = new Index(k.line, e, i);
            } else return e;
        }
    }

    private Node primary() {
        Tok k = next();
        int line = k.line;
        switch (k.type) {
            case Lexer.NUM: return new Lit(line, k.num);
            case Lexer.STR: return string(k);
            case Lexer.ID:
                switch (k.text) {
                    case "true": case "yes": return new Lit(line, Boolean.TRUE);
                    case "false": case "no": return new Lit(line, Boolean.FALSE);
                    case "null": case "none": return new Lit(line, null);
                    case "fn": return lambdaRest("<fn>", line);
                    default: return new Name(line, k.text);
                }
            default:
        }
        if (k.is("(")) { Node e = expr(); expect(")"); return e; }
        if (k.is("[")) {
            List<Node> items = new ArrayList<Node>();
            skipNl();
            if (!at("]")) do { skipNl(); if (at("]")) break; items.add(expr()); skipNl(); } while (accept(","));
            skipNl();
            expect("]");
            return new ListLit(line, items.toArray(new Node[0]));
        }
        if (k.is("{")) { p--; return mapLit(); }
        p--;
        throw err("ожидалось значение, а встретилось " + k);
    }

    private MapLit mapLit() {
        int line = expect("{").line;
        List<String> keys = new ArrayList<String>();
        List<Node> vals = new ArrayList<Node>();
        skipNl();
        while (!at("}")) {
            Tok k = next();
            String key;
            if (k.type == Lexer.ID || k.type == Lexer.STR) key = k.text;
            else if (k.type == Lexer.NUM) key = Interp.str(k.num);
            else { p--; throw err("ожидался ключ, а встретилось " + k); }
            expect(":");
            keys.add(key);
            vals.add(expr());
            boolean nl = peek().type == Lexer.NL;            // entries: "," or a new line
            skipNl();
            if (!accept(",") && !nl && !at("}")) throw err("ожидалось ',' или '}'");
            skipNl();
        }
        expect("}");
        return new MapLit(line, keys.toArray(new String[0]), vals.toArray(new Node[0]));
    }

    /** "a {expr} b" -> parts; \{ stays a brace */
    private Node string(Tok k) {
        String s = k.text;
        if (s.indexOf('{') < 0) return new Lit(k.line, unesc(s));
        List<Object> parts = new ArrayList<Object>();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf('{', i);
            if (a < 0) { parts.add(unesc(s.substring(i))); break; }
            if (a > i) parts.add(unesc(s.substring(i, a)));
            int depth = 0, b = a;
            for (; b < s.length(); b++) {
                char c = s.charAt(b);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
            if (b >= s.length()) throw new ScriptError("в строке не закрыта '{' (используй \\{ для обычной скобки)", k.line);
            String inner = s.substring(a + 1, b);
            List<Tok> toks = Lexer.lex(inner);
            List<Tok> fixed = new ArrayList<Tok>();
            for (Tok x : toks) fixed.add(new Tok(x.type, x.text, x.num, k.line));
            Parser sub = new Parser(fixed);
            Node e = sub.expr();
            sub.skipNl();
            if (sub.peek().type != Lexer.EOF) throw new ScriptError("ошибка в {" + inner + "} внутри строки", k.line);
            parts.add(e);
            i = b + 1;
        }
        return new Str(k.line, parts.toArray());
    }

    private static String unesc(String s) { return s.replace('\u0001', '{').replace('\u0002', '}'); }
}

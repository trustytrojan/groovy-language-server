package net.prominic.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import net.prominic.groovyls.gdsl.JenkinsSymbol;
import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.ClassNode;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;
import net.prominic.lsp.utils.Ranges; // this NEEDS to be imported even if not used here (?????)

/**
 * Semantic tokens provider that emits tokens for functions/methods (including GDSL methods)
 * and local/global variables by analyzing the AST.
 *
 * This implementation uses the Groovy AST to precisely locate declarations and method calls,
 * ensuring that tokens are only emitted for actual code elements and never for string literals
 * or comments. Tokens are encoded using the LSP delta format.
 */
public class SemanticTokensProvider {
    private final FileContentsTracker fileContentsTracker;
    private final List<JenkinsSymbol> gdslSymbols;

    // token types used in the legend (must match order below)
    public static final List<String> TOKEN_TYPES = Collections.unmodifiableList(Arrays.asList(
            "namespace","class","enum","interface","struct","typeParameter","type",
            "parameter","variable","property","enumMember","event","function","method",
            "macro","keyword","modifier","comment","string","number","regexp","operator"
    ));

    private final ASTNodeVisitor astVisitor;

    public SemanticTokensProvider(FileContentsTracker fileContentsTracker, List<JenkinsSymbol> gdslSymbols,
            ASTNodeVisitor astVisitor) {
        this.fileContentsTracker = fileContentsTracker;
        this.gdslSymbols = gdslSymbols != null ? gdslSymbols : Collections.emptyList();
        this.astVisitor = astVisitor;
    }

    public SemanticTokensLegend getLegend() {
        return new SemanticTokensLegend(TOKEN_TYPES, Collections.emptyList());
    }

    public SemanticTokens provideFull(TextDocumentIdentifier textDocument) {
        URI uri = URI.create(textDocument.getUri());
        String text = fileContentsTracker.getContents(uri);
        if (text == null) {
            return new SemanticTokens(new ArrayList<>());
        }

        List<Token> tokens = new ArrayList<>();

        // 1) Use AST to gather variable and method declarations (precise ranges)
        if (astVisitor != null) {
            if (uri != null) {
                List<ASTNode> nodes = astVisitor.getNodes(uri);
                java.util.Set<String> emitted = new java.util.HashSet<>();
                for (ASTNode node : nodes) {
                    // 0) Method calls: color method name when we can resolve a method on the receiver
                    if (node instanceof MethodCallExpression) {
                        MethodCallExpression call = (MethodCallExpression) node;
                        String methodName = call.getMethodAsString();
                        if (methodName != null && !methodName.isEmpty()) {
                            java.util.List<MethodNode> overloads = GroovyASTUtils.getMethodOverloadsFromCallExpression(call, astVisitor);
                            if (overloads != null && !overloads.isEmpty()) {
                                org.eclipse.lsp4j.Range methodRange = GroovyLanguageServerUtils.astNodeToRange(call.getMethod());
                                if (methodRange != null) {
                                    Position pos = new Position((int) methodRange.getStart().getLine(), (int) methodRange.getStart().getCharacter());
                                    String key = pos.line + ":" + pos.col + ":" + methodName + ":" + "method";
                                    if (!emitted.contains(key)) {
                                        tokens.add(new Token(pos.line, pos.col, methodName.length(), tokenTypeIndex("method")));
                                        emitted.add(key);
                                    }
                                }
                            }
                        }
                    }
                    // 1) Handle property/field access expressions (e.g., this.x, Closure.DELEGATE_FIRST)
                    if (node instanceof PropertyExpression) {
                        PropertyExpression pe = (PropertyExpression) node;
                        String propName = pe.getPropertyAsString();
                        if (propName != null && !propName.isEmpty()) {
                            ASTNode propNode = (ASTNode) pe.getProperty();
                            org.eclipse.lsp4j.Range propRange = GroovyLanguageServerUtils.astNodeToRange(propNode);
                            if (propRange != null) {
                                boolean fieldExists = false;

                                // Check 1: Check the enclosing class scope
                                ClassNode enclosing = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(node, ClassNode.class, astVisitor);
                                if (enclosing != null) {
                                    if (enclosing.getField(propName) != null) fieldExists = true;
                                    else if (enclosing.getProperty(propName) != null) fieldExists = true;
                                }

                                // Check 2: Check the target object expression's resolved type (Fixes Closure.DELEGATE_FIRST)
                                if (!fieldExists && pe.getObjectExpression() != null) {
                                    ClassNode targetClass = null;
                                    if (pe.getObjectExpression() instanceof org.codehaus.groovy.ast.expr.ClassExpression) {
                                        targetClass = pe.getObjectExpression().getType();
                                    } else {
                                        targetClass = GroovyASTUtils.getTypeOfNode(pe.getObjectExpression(), astVisitor);
                                    }
                                    
                                    if (targetClass != null) {
                                        if (targetClass.getField(propName) != null || targetClass.getProperty(propName) != null) {
                                            fieldExists = true;
                                        }
                                    }
                                }

                                if (fieldExists) {
                                    Position pos = new Position((int) propRange.getStart().getLine(), (int) propRange.getStart().getCharacter());
                                    String key = pos.line + ":" + pos.col + ":" + propName + ":" + "property";
                                    if (!emitted.contains(key)) {
                                        tokens.add(new Token(pos.line, pos.col, propName.length(), tokenTypeIndex("property")));
                                        emitted.add(key);
                                    }
                                }
                            }
                        }
                    }

                    // 2) Declarations: methods, variables, fields, properties, parameters
                    org.eclipse.lsp4j.Range range = GroovyLanguageServerUtils.astNodeToRange(node);
                    if (range == null) continue;
                    String name = null;
                    if (node instanceof MethodNode) {
                        MethodNode mn = (MethodNode) node;
                        if (mn.isConstructor()) {
                            ClassNode declaringClass = mn.getDeclaringClass();
                            String className = declaringClass != null ? declaringClass.getNameWithoutPackage() : null;
                            if (className != null && !className.isEmpty()) {
                                int startOffsetCtor = lineColToOffset(text, (int) range.getStart().getLine(), (int) range.getStart().getCharacter());
                                int endOffsetCtor = lineColToOffset(text, (int) range.getEnd().getLine(), (int) range.getEnd().getCharacter());
                                
                                int foundCtor = startOffsetCtor;
                                while (foundCtor >= 0) {
                                    foundCtor = text.indexOf(className, foundCtor);
                                    if (foundCtor == -1 || foundCtor >= endOffsetCtor) {
                                        foundCtor = -1;
                                        break;
                                    }
                                    boolean beforeValid = (foundCtor == 0) || !Character.isJavaIdentifierPart(text.charAt(foundCtor - 1));
                                    boolean afterValid = (foundCtor + className.length() >= text.length()) || !Character.isJavaIdentifierPart(text.charAt(foundCtor + className.length()));
                                    if (beforeValid && afterValid) {
                                        break;
                                    }
                                    foundCtor++;
                                }

                                if (foundCtor != -1) {
                                    Position posCtor = toLineCol(text, foundCtor);
                                    int tokenTypeCtor = tokenTypeIndex("class");
                                    String keyCtor = posCtor.line + ":" + posCtor.col + ":" + className + ":" + tokenTypeCtor;
                                    if (!emitted.contains(keyCtor)) {
                                        tokens.add(new Token(posCtor.line, posCtor.col, className.length(), tokenTypeCtor));
                                        emitted.add(keyCtor);
                                    }
                                }
                            }
                            continue;
                        }
                        name = mn.getName();
                    } else if (node instanceof Variable) {
                        name = ((Variable) node).getName();
                    } else if (node instanceof FieldNode) {
                        name = ((FieldNode) node).getName();
                    } else if (node instanceof PropertyNode) {
                        name = ((PropertyNode) node).getName();
                    } else if (node instanceof Parameter) {
                        name = ((Parameter) node).getName();
                    }
                    if (name == null) continue;
                    if ("this".equals(name) || "super".equals(name)) continue;
                    int startOffset = lineColToOffset(text, (int) range.getStart().getLine(), (int) range.getStart().getCharacter());
                    int endOffset = lineColToOffset(text, (int) range.getEnd().getLine(), (int) range.getEnd().getCharacter());
                    if (startOffset < 0 || endOffset <= startOffset) continue;

                    int found = startOffset;
                    while (found >= 0) {
                        found = text.indexOf(name, found);
                        if (found == -1 || found >= endOffset) {
                            found = -1;
                            break;
                        }
                        boolean beforeValid = (found == 0) || !Character.isJavaIdentifierPart(text.charAt(found - 1));
                        boolean afterValid = (found + name.length() >= text.length()) || !Character.isJavaIdentifierPart(text.charAt(found + name.length()));
                        if (beforeValid && afterValid) {
                            break;
                        }
                        found++;
                    }

                    if (found != -1) {
                        Position pos = toLineCol(text, found);
                        int tokenType = (node instanceof MethodNode) ? tokenTypeIndex("function") : tokenTypeIndex("variable");
                        String key = pos.line + ":" + pos.col + ":" + name + ":" + tokenType;
                        if (!emitted.contains(key)) {
                            tokens.add(new Token(pos.line, pos.col, name.length(), tokenType));
                            emitted.add(key);
                        }
                    }
                }
            }
        }

        // 3) GDSL symbols: find via AST MethodCallExpression nodes only
        if (astVisitor != null && uri != null) {
            java.util.Set<String> gdslSymbolNames = new java.util.HashSet<>();
            for (JenkinsSymbol s : gdslSymbols) {
                if (s.name != null && !s.name.isEmpty()) {
                    gdslSymbolNames.add(s.name);
                }
            }
            
            if (!gdslSymbolNames.isEmpty()) {
                List<ASTNode> allNodes = astVisitor.getNodes(uri);
                for (ASTNode node : allNodes) {
                    if (node instanceof MethodCallExpression) {
                        MethodCallExpression call = (MethodCallExpression) node;
                        String methodName = call.getMethodAsString();
                        if (methodName != null && gdslSymbolNames.contains(methodName)) {
                            org.eclipse.lsp4j.Range range = GroovyLanguageServerUtils.astNodeToRange(call);
                            if (range != null) {
                                Position pos = new Position((int) range.getStart().getLine(), (int) range.getStart().getCharacter());
                                tokens.add(new Token(pos.line, pos.col, methodName.length(), tokenTypeIndex("function")));
                            }
                        }
                    }
                }
            }
        }

        if (tokens.isEmpty()) {
            return new SemanticTokens(new ArrayList<>());
        }

        tokens.sort(Comparator.comparingInt((Token t) -> t.line).thenComparingInt(t -> t.startChar));

        List<Integer> data = new ArrayList<>();
        int prevLine = 0;
        int prevChar = 0;
        boolean first = true;
        for (Token t : tokens) {
            int deltaLine = first ? t.line : t.line - prevLine;
            int deltaStart = first ? t.startChar : (deltaLine == 0 ? t.startChar - prevChar : t.startChar);
            data.add(deltaLine);
            data.add(deltaStart);
            data.add(t.length);
            data.add(t.tokenType);
            data.add(0); // modifiers bitset

            prevLine = t.line;
            prevChar = t.startChar;
            first = false;
        }

        return new SemanticTokens(data);
    }

    private int lineColToOffset(String text, int line, int col) {
        if (line < 0) return -1;
        int curLine = 0;
        int offset = 0;
        int len = text.length();
        while (offset < len && curLine < line) {
            if (text.charAt(offset) == '\n') {
                curLine++;
            }
            offset++;
        }
        if (curLine != line) return -1;
        return Math.min(offset + col, len);
    }

    private int tokenTypeIndex(String type) {
        int idx = TOKEN_TYPES.indexOf(type);
        return idx >= 0 ? idx : 0;
    }

    private static class Token {
        final int line;
        final int startChar;
        final int length;
        final int tokenType;

        Token(int line, int startChar, int length, int tokenType) {
            this.line = line;
            this.startChar = startChar;
            this.length = length;
            this.tokenType = tokenType;
        }
    }

    private static class Position {
        final int line;
        final int col;

        Position(int line, int col) {
            this.line = line;
            this.col = col;
        }
    }

    private Position toLineCol(String text, int offset) {
        int line = 0;
        int col = 0;
        int i = 0;
        while (i < offset) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
            i++;
        }
        return new Position(line, col);
    }
}
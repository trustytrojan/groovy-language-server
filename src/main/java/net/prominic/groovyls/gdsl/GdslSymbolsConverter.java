////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Prominic.NET, Inc.
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package net.prominic.groovyls.gdsl;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.ParameterInformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts JenkinsSymbol objects to LSP (Language Server Protocol) primitives.
 * Handles the transformation from GDSL symbol representations to standard LSP types.
 */
public class GdslSymbolsConverter {

    public static CompletionItem toCompletionItem(JenkinsSymbol symbol, String namePrefix, Set<String> existingNames) {
        if (!symbol.name.startsWith(namePrefix)) {
            return null;
        }
        if (existingNames.contains(symbol.name)) {
            return null;
        }
        existingNames.add(symbol.name);

        CompletionItem item = new CompletionItem();
        item.setLabel(symbol.name);

        CompletionItemKind kind = determineKind(symbol.type);
        item.setKind(kind);

        if (symbol.doc != null && !symbol.doc.isEmpty()) {
            MarkupContent markup = new MarkupContent();
            markup.setKind(MarkupKind.MARKDOWN);
            markup.setValue(formatDocumentation(symbol));
            item.setDocumentation(markup);
        }

        item.setDetail(symbol.type);

        String insertText = buildSnippet(symbol);
        if (insertText != null) {
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setInsertText(insertText);

            Command command = new Command();
            command.setTitle("Trigger Parameter Hints");
            command.setCommand("editor.action.triggerParameterHints");
            item.setCommand(command);
        } else if (symbol.params != null && !symbol.params.isEmpty()) {
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setInsertText(symbol.name + "($0)");

            Command command = new Command();
            command.setTitle("Trigger Parameter Hints");
            command.setCommand("editor.action.triggerParameterHints");
            item.setCommand(command);
        }

        return item;
    }

    public static Hover toHover(JenkinsSymbol symbol) {
        Hover hover = new Hover();

        StringBuilder content = new StringBuilder();

        // Add code block with signature
        content.append("```groovy\n");
        content.append(simplifyType(symbol.type != null ? symbol.type : "Object")).append(" ").append(symbol.name);

        // Build parameter list from namedParams (preferred) or params map
        content.append("(");
        boolean first = true;
        if (symbol.namedParams != null && !symbol.namedParams.isEmpty()) {
            for (int i = 0; i < symbol.namedParams.size(); i++) {
                if (!first) content.append(", ");
                first = false;
                Map<String, Object> param = symbol.namedParams.get(i);
                String pName = (String) param.getOrDefault("name", "param" + (i + 1));
                String pType = simplifyType(param.get("type"));
                if (pType != null) {
                    content.append(pType).append(" ").append(pName);
                } else {
                    content.append(pName);
                }
            }
        } else if (symbol.params != null && !symbol.params.isEmpty()) {
            int i = 0;
            for (Map.Entry<String, Object> entry : symbol.params.entrySet()) {
                if (!first) content.append(", ");
                first = false;
                String pName = entry.getKey();
                String pType = simplifyType(entry.getValue());
                if (pType != null) {
                    content.append(pType).append(" ").append(pName);
                } else {
                    content.append(pName);
                }
                i++;
            }
        }
        content.append(")");

        if (symbol.isNodeScoped) {
            content.append(" // node-scoped");
        }

        content.append("\n```");

        // Add documentation
        if (symbol.doc != null && !symbol.doc.isEmpty()) {
            content.append("\n\n");
            content.append(symbol.doc);
        }

        MarkupContent markup = new MarkupContent();
        markup.setKind(MarkupKind.MARKDOWN);
        markup.setValue(content.toString());
        hover.setContents(markup);

        return hover;
    }

    private static CompletionItemKind determineKind(String type) {
        if (type == null) {
            return CompletionItemKind.Method;
        }

        switch (type.toLowerCase()) {
            case "method":
            case "step":
                return CompletionItemKind.Method;
            case "property":
            case "field":
                return CompletionItemKind.Property;
            case "variable":
                return CompletionItemKind.Variable;
            case "class":
                return CompletionItemKind.Class;
            default:
                return CompletionItemKind.Method;
        }
    }

    private static String buildSnippet(JenkinsSymbol symbol) {
        if (symbol.namedParams == null || symbol.namedParams.isEmpty()) {
            return null;
        }

        StringBuilder snippet = new StringBuilder();
        snippet.append(symbol.name).append("(");

        for (int i = 0; i < symbol.namedParams.size(); i++) {
            if (i > 0) snippet.append(", ");
            Map<String, Object> param = symbol.namedParams.get(i);
            String paramName = (String) param.getOrDefault("name", "param" + (i + 1));
            snippet.append(paramName).append(": ${").append(i + 1).append(":").append(paramName).append("}");
        }

        snippet.append(")");
        return snippet.toString();
    }

    private static String formatDocumentation(JenkinsSymbol symbol) {
        return symbol.doc;
    }

    public static List<CompletionItem> toCompletionItems(List<JenkinsSymbol> symbols, String namePrefix, Set<String> existingNames) {
        List<CompletionItem> items = new ArrayList<>();
        for (JenkinsSymbol symbol : symbols) {
            CompletionItem item = toCompletionItem(symbol, namePrefix, existingNames);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    public static SignatureInformation toSignatureInformation(JenkinsSymbol symbol) {
        SignatureInformation info = new SignatureInformation();

        StringBuilder label = new StringBuilder(symbol.name);
        label.append("(");

        List<ParameterInformation> paramsInfo = new ArrayList<>();

        if (symbol.namedParams != null && !symbol.namedParams.isEmpty()) {
            for (int i = 0; i < symbol.namedParams.size(); i++) {
                if (i > 0) label.append(", ");
                Map<String, Object> param = symbol.namedParams.get(i);
                String pName = (String) param.getOrDefault("name", "param" + i);
                String pType = simplifyType(param.get("type"));

                String pLabel = pType != null ? pType + " " + pName : pName;
                label.append(pLabel);

                ParameterInformation pInfo = new ParameterInformation(pLabel);
                paramsInfo.add(pInfo);
            }
        } else if (symbol.params != null && !symbol.params.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, Object> entry : symbol.params.entrySet()) {
                if (!first) label.append(", ");
                first = false;

                String pName = entry.getKey();
                String pType = simplifyType(entry.getValue());

                String pLabel = pType != null ? pType + " " + pName : pName;
                label.append(pLabel);

                ParameterInformation pInfo = new ParameterInformation(pLabel);
                paramsInfo.add(pInfo);
            }
        }

        label.append(")");

        info.setLabel(label.toString());
        info.setParameters(paramsInfo);

        if (symbol.doc != null && !symbol.doc.isEmpty()) {
            MarkupContent markup = new MarkupContent();
            markup.setKind(MarkupKind.MARKDOWN);
            markup.setValue(formatDocumentation(symbol));
            info.setDocumentation(markup);
        }

        return info;
    }

    /**
     * Simplifies a raw type representation produced by the GDSL parser.
     * - Strips a leading "class " prefix (Groovy prints types like "class java.lang.String").
     * - Shortens fully-qualified names to their simple class name (java.lang.String -> String).
     */
    private static String simplifyType(Object raw) {
        if (raw == null) return null;
        String s = raw.toString();
        if (s.startsWith("class ")) {
            s = s.substring("class ".length());
        }
        // Reduce fully-qualified names inside the string by taking last segment of dotted identifiers
        StringBuilder out = new StringBuilder();
        StringBuilder tok = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '$' || c == '_') {
                tok.append(c);
            } else {
                if (tok.length() > 0) {
                    String t = tok.toString();
                    int lastDot = t.lastIndexOf('.');
                    if (lastDot != -1) t = t.substring(lastDot + 1);
                    out.append(t);
                    tok.setLength(0);
                }
                out.append(c);
            }
        }
        if (tok.length() > 0) {
            String t = tok.toString();
            int lastDot = t.lastIndexOf('.');
            if (lastDot != -1) t = t.substring(lastDot + 1);
            out.append(t);
        }
        String res = out.toString().trim();
        return res.isEmpty() ? null : res;
    }
}

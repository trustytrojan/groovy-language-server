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

    /**
     * Converts a JenkinsSymbol to a CompletionItem for use in autocompletion.
     * 
     * @param symbol the GDSL symbol to convert
     * @param namePrefix the prefix to filter by (the symbol name must start with this)
     * @param existingNames a set of already-used names to avoid duplicates
     * @return a CompletionItem, or null if the symbol doesn't match the prefix
     */
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
        
        // Determine kind based on type
        CompletionItemKind kind = determineKind(symbol.type);
        item.setKind(kind);

        // Add documentation if available
        if (symbol.doc != null && !symbol.doc.isEmpty()) {
            MarkupContent markup = new MarkupContent();
            markup.setKind(MarkupKind.MARKDOWN);
            markup.setValue(formatDocumentation(symbol));
            item.setDocumentation(markup);
        }

        // Add detail with node-scoped information
        item.setDetail(symbol.type);

        // Build snippet with parameters if available
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

    /**
     * Converts a JenkinsSymbol to a Hover object for displaying documentation on hover.
     * 
     * @param symbol the GDSL symbol to convert
     * @return a Hover object containing the symbol's documentation
     */
    public static Hover toHover(JenkinsSymbol symbol) {
        Hover hover = new Hover();
        
        StringBuilder content = new StringBuilder();
        
        // Add code block with signature
        content.append("```groovy\n");
        content.append(symbol.type).append(" ").append(symbol.name);
        
        if (symbol.namedParams != null && !symbol.namedParams.isEmpty()) {
            content.append("(");
            for (int i = 0; i < symbol.namedParams.size(); i++) {
                if (i > 0) content.append(", ");
                Map<String, Object> param = symbol.namedParams.get(i);
                content.append(param.getOrDefault("name", "param"));
            }
            content.append(")");
        }
        
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

    /**
     * Determines the CompletionItemKind based on the symbol's type.
     */
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

    /**
     * Builds a formatted snippet with parameter placeholders.
     */
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

    /**
     * Formats the documentation for display in completion items and hovers.
     */
    private static String formatDocumentation(JenkinsSymbol symbol) {
        return symbol.doc;
    }

    /**
     * Converts a list of JenkinsSymbols to CompletionItems.
     */
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
                String pType = (String) param.get("type");
                
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
                String pType = entry.getValue() != null ? entry.getValue().toString() : null;
                
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
}

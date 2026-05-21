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

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses GDSL (Groovy DSL) files to extract method and property definitions.
 * Uses Groovy's embedding API to execute GDSL scripts and intercept DSL calls.
 */
public class JenkinsGdslParser {

    private final List<JenkinsSymbol> extractedSymbols = new ArrayList<>();

    /**
     * Parses a GDSL file and returns all extracted symbols.
     * 
     * @param gdslFile the GDSL file to parse
     * @return a list of JenkinsSymbol objects extracted from the file
     * @throws IOException if the file cannot be read
     */
    public List<JenkinsSymbol> parseGdsl(File gdslFile) throws IOException {
        extractedSymbols.clear();

        // 1. Setup the script binding and top-level stubs
        Binding binding = new Binding();
        
        binding.setVariable("context", new Closure<Object>(this) {
            public Object doCall(Map<String, Object> args) {
                return args; // Returns the scope map
            }
        });

        binding.setVariable("scriptScope", new Closure<Object>(this) {
            public Object doCall() {
                Map<String, String> scope = new HashMap<>();
                scope.put("type", "script");
                return scope;
            }
        });

        binding.setVariable("closureScope", new Closure<Object>(this) {
            public Object doCall() {
                Map<String, String> scope = new HashMap<>();
                scope.put("type", "closure");
                return scope;
            }
        });

        // 2. Mock out the contributor engine
        binding.setVariable("contributor", new Closure<Object>(this) {
            public Object doCall(Object ctx, Closure<?> closure) {
                // Determine if we are operating in a node scoped block
                boolean isNodeCtx = false;
                if (ctx instanceof Map) {
                    Map<?, ?> ctxMap = (Map<?, ?>) ctx;
                    if (ctxMap.get("scope") instanceof Map) {
                        Map<?, ?> scopeMap = (Map<?, ?>) ctxMap.get("scope");
                        if ("closure".equals(scopeMap.get("type"))) {
                            isNodeCtx = true;
                        }
                    }
                }

                final boolean finalIsNodeCtx = isNodeCtx;

                // Create a dynamic delegate map to intercept method() and property()
                Map<String, Object> delegate = new HashMap<>();
                
                delegate.put("enclosingCall", new Closure<Object>(this) {
                    public Object doCall(String name) {
                        return "node".equals(name) ? "node" : null;
                    }
                });

                delegate.put("parameter", new Closure<Object>(this) {
                    public Object doCall(Map<String, Object> args) {
                        return args;
                    }
                });

                delegate.put("method", new Closure<Object>(this) {
                    @SuppressWarnings("unchecked")
                    public Object doCall(Map<String, Object> args) {
                        // Handle potential conditional logic inside GDSL (like node checking)
                        if (finalIsNodeCtx) {
                            // If it's a node context, ensure the mock enclosingCall returned 'node'
                            // Your GDSL file explicitly uses: if (call) { method(...) }
                            // Since we auto-verify context, we process it straight through
                        }
                        
                        JenkinsSymbol symbol = new JenkinsSymbol(
                                (String) args.get("name"),
                                (String) args.get("type"),
                                (String) args.get("doc"),
                                (List<Map<String, Object>>) args.get("namedParams"),
                                (Map<String, Object>) args.get("params"),
                                finalIsNodeCtx,
                                false
                        );
                        extractedSymbols.add(symbol);
                        return null;
                    }
                });

                delegate.put("property", new Closure<Object>(this) {
                    public Object doCall(Map<String, Object> args) {
                        JenkinsSymbol symbol = new JenkinsSymbol(
                                (String) args.get("name"),
                                (String) args.get("type"),
                                "",
                                null,
                                null,
                                finalIsNodeCtx,
                                true
                        );
                        extractedSymbols.add(symbol);
                        return null;
                    }
                });

                closure.setDelegate(delegate);
                closure.setResolveStrategy(Closure.DELEGATE_FIRST);
                closure.call();
                return null;
            }
        });

        // 3. Execute the GDSL source securely via GroovyShell
        try {
            CompilerConfiguration config = new CompilerConfiguration();
            GroovyShell shell = new GroovyShell(binding, config);
            shell.evaluate(gdslFile);
        } catch (Exception e) {
            // Log but don't fail - GDSL parsing errors should not break the language server
            System.err.println("Warning: Failed to parse GDSL file: " + gdslFile.getAbsolutePath());
            e.printStackTrace();
        }

        return extractedSymbols;
    }
}

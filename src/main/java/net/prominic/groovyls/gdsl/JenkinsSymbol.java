////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 trustytrojan
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
// Author: trustytrojan
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package net.prominic.groovyls.gdsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a symbol extracted from a GDSL (Groovy DSL) file.
 * These symbols typically represent pipeline steps, methods, or properties
 * defined in a DSL like Jenkins Pipeline.
 */
public class JenkinsSymbol {
    public final String name;
    public final String type;
    public final String doc;
    public final List<Map<String, Object>> namedParams;
    public final Map<String, Object> params;
    public final boolean isNodeScoped;
    public final boolean isProperty;

    public JenkinsSymbol(String name, String type, String doc, 
                         List<Map<String, Object>> namedParams, 
                         Map<String, Object> params, boolean isNodeScoped, boolean isProperty) {
        this.name = name;
        this.type = type;
        this.doc = doc;
        this.namedParams = namedParams != null ? namedParams : new ArrayList<>();
        this.params = params;
        this.isNodeScoped = isNodeScoped;
        this.isProperty = isProperty;
    }

    @Override
    public String toString() {
        return "JenkinsSymbol{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", isNodeScoped=" + isNodeScoped +
                '}';
    }
}

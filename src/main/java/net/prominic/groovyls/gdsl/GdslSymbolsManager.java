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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages GDSL symbol loading and caching for the language server.
 * Searches for gdsl.groovy files in the workspace and maintains a cache of parsed symbols.
 */
public class GdslSymbolsManager {
    private List<JenkinsSymbol> cachedSymbols = Collections.emptyList();
    private long lastModified = 0;
    private File currentGdslFile = null;

    private final JenkinsGdslParser parser = new JenkinsGdslParser();

    /**
     * Loads GDSL symbols from the workspace.
     * Searches for gdsl.groovy in the workspace root and caches the results.
     * 
     * @param workspaceRoot the root path of the workspace
     */
    public void loadGdslSymbols(Path workspaceRoot) {
        if (workspaceRoot == null) {
            cachedSymbols = Collections.emptyList();
            return;
        }

        // Search for gdsl.groovy files in the workspace root and one level deep
        List<File> gdslFiles = findGdslFiles(workspaceRoot.toFile());
        
        if (gdslFiles.isEmpty()) {
            cachedSymbols = Collections.emptyList();
            currentGdslFile = null;
            return;
        }

        // Use the first gdsl.groovy file found
        File gdslFile = gdslFiles.get(0);
        
        // Check if file has been modified
        long fileLastModified = gdslFile.lastModified();
        if (gdslFile.equals(currentGdslFile) && fileLastModified == lastModified && !cachedSymbols.isEmpty()) {
            // Cache is still valid
            return;
        }

        // Parse the GDSL file
        try {
            List<JenkinsSymbol> symbols = parser.parseGdsl(gdslFile);
            cachedSymbols = symbols;
            currentGdslFile = gdslFile;
            lastModified = fileLastModified;
            System.out.println("Loaded " + symbols.size() + " GDSL symbols from: " + gdslFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to load GDSL file: " + gdslFile.getAbsolutePath());
            e.printStackTrace();
            cachedSymbols = Collections.emptyList();
            currentGdslFile = null;
        }
    }

    /**
     * Gets the cached GDSL symbols.
     * 
     * @return a list of cached JenkinsSymbol objects
     */
    public List<JenkinsSymbol> getSymbols() {
        return cachedSymbols;
    }

    /**
     * Searches for GDSL files in the workspace.
     * Looks for files named "gdsl.groovy" or ending in ".gdsl"
     */
    private List<File> findGdslFiles(File root) {
        List<File> result = new ArrayList<>();
        
        if (!root.exists() || !root.isDirectory()) {
            return result;
        }

        // Search root directory
        File[] files = root.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && isGdslFile(file)) {
                    result.add(file);
                }
            }
        }

        // Search one level deep (but skip common build directories)
        if (files != null) {
            for (File dir : files) {
                if (dir.isDirectory() && !isExcludedDirectory(dir.getName())) {
                    File[] subFiles = dir.listFiles();
                    if (subFiles != null) {
                        for (File file : subFiles) {
                            if (file.isFile() && isGdslFile(file)) {
                                result.add(file);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Checks if a file is a GDSL file.
     */
    private boolean isGdslFile(File file) {
        String name = file.getName();
        return "gdsl.groovy".equalsIgnoreCase(name) || 
               name.endsWith(".gdsl") || 
               name.endsWith(".gdsl.groovy");
    }

    /**
     * Checks if a directory should be excluded from GDSL search.
     */
    private boolean isExcludedDirectory(String dirName) {
        return dirName.startsWith(".") || 
               "build".equals(dirName) || 
               "dist".equals(dirName) || 
               "node_modules".equals(dirName) || 
               "target".equals(dirName);
    }

    /**
     * Clears the cached symbols.
     */
    public void clear() {
        cachedSymbols = Collections.emptyList();
        currentGdslFile = null;
        lastModified = 0;
    }
}
